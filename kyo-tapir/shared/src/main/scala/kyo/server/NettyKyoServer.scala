package sttp.tapir.server.netty

import io.netty.channel.*
import io.netty.channel.group.ChannelGroup
import io.netty.channel.group.DefaultChannelGroup
import io.netty.channel.unix.DomainSocketAddress
import io.netty.util.concurrent.DefaultEventExecutor
import java.lang.System as JSystem
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kyo.{Channel as _, *}
import kyo.internal.KyoSttpMonad
import kyo.server.internal.KyoUtil.*
import scala.concurrent.Future
import scala.concurrent.duration.*
import scala.concurrent.duration.FiniteDuration
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.model.ServerResponse
import sttp.tapir.server.netty.Route
import sttp.tapir.server.netty.internal.NettyBootstrap
import sttp.tapir.server.netty.internal.NettyServerHandler

case class NettyKyoServer(
    routes: Vector[Route[KyoSttpMonad.M]],
    options: NettyKyoServerOptions,
    config: NettyConfig
):
    def addEndpoint(se: ServerEndpoint[Any, KyoSttpMonad.M]): NettyKyoServer =
        addEndpoints(List(se))
    def addEndpoint(
        se: ServerEndpoint[Any, KyoSttpMonad.M],
        overrideOptions: NettyKyoServerOptions
    ): NettyKyoServer =
        addEndpoints(List(se), overrideOptions)
    def addEndpoints(ses: List[ServerEndpoint[Any, KyoSttpMonad.M]]): NettyKyoServer = addRoute(
        NettyKyoServerInterpreter(options).toRoute(ses)
    )
    def addEndpoints(
        ses: List[ServerEndpoint[Any, KyoSttpMonad.M]],
        overrideOptions: NettyKyoServerOptions
    ): NettyKyoServer =
        addRoute(NettyKyoServerInterpreter(overrideOptions).toRoute(ses))

    def addRoute(r: Route[KyoSttpMonad.M]): NettyKyoServer            = copy(routes = routes :+ r)
    def addRoutes(r: Iterable[Route[KyoSttpMonad.M]]): NettyKyoServer = copy(routes = routes ++ r)

    def options(o: NettyKyoServerOptions): NettyKyoServer = copy(options = o)

    def config(c: NettyConfig): NettyKyoServer                      = copy(config = c)
    def modifyConfig(f: NettyConfig => NettyConfig): NettyKyoServer = config(f(config))

    def host(h: String): NettyKyoServer = modifyConfig(_.host(h))

    def port(p: Int): NettyKyoServer = modifyConfig(_.port(p))

    def start(): KyoSttpMonad.M[NettyKyoServerBinding] =
        startUsingSocketOverride[InetSocketAddress](None).map { case (socket, stop) =>
            NettyKyoServerBinding(socket, stop)
        }

    def startUsingDomainSocket(path: Option[Path] = None): KyoSttpMonad.M[NettyKyoDomainSocketBinding] =
        startUsingDomainSocket(path.getOrElse(Paths.get(
            JSystem.getProperty("java.io.tmpdir"),
            UUID.randomUUID().toString
        )))

    def startUsingDomainSocket(path: Path): KyoSttpMonad.M[NettyKyoDomainSocketBinding] =
        startUsingSocketOverride(Some(new DomainSocketAddress(path.toFile))).map {
            case (socket, stop) =>
                NettyKyoDomainSocketBinding(socket, stop)
        }

    private def unsafeRunAsync(
        forkExecution: Boolean,
        block: () => KyoSttpMonad.M[ServerResponse[NettyResponse]]
    ): (Future[ServerResponse[NettyResponse]], () => Future[Unit]) =
        import AllowUnsafe.embrace.danger
        val fiber  = Sync.Unsafe.evalOrThrow(Async.run(block()))
        val future = Sync.Unsafe.evalOrThrow(fiber.toFuture)
        val cancel = () =>
            val _ = Sync.Unsafe.evalOrThrow(fiber.interrupt)
            Future.unit
        (future, cancel)
    end unsafeRunAsync

    private def startUsingSocketOverride[SA <: SocketAddress](socketOverride: Option[SA])
        : KyoSttpMonad.M[(SA, () => KyoSttpMonad.M[Unit])] =
        val eventLoopGroup                           = config.eventLoopConfig.initEventLoopGroup()
        given monadError: MonadError[KyoSttpMonad.M] = KyoSttpMonad
        val route                                    = Route.combine(routes)
        val eventExecutor                            = new DefaultEventExecutor()
        val channelGroup                             = new DefaultChannelGroup(eventExecutor) // thread safe
        val isShuttingDown: AtomicBoolean            = new AtomicBoolean(false)

        val channelFuture =
            NettyBootstrap(
                config,
                new NettyServerHandler[KyoSttpMonad.M](
                    route,
                    unsafeRunAsync(options.forkExecution, _),
                    channelGroup,
                    isShuttingDown,
                    config
                ),
                eventLoopGroup,
                socketOverride
            )

        nettyChannelFutureToScala(channelFuture).map(ch =>
            (
                ch.localAddress().asInstanceOf[SA],
                () =>
                    stop(
                        ch,
                        eventLoopGroup,
                        channelGroup,
                        eventExecutor,
                        isShuttingDown,
                        config.gracefulShutdownTimeout
                    )
            )
        )
    end startUsingSocketOverride

    private def waitForClosedChannels(
        channelGroup: ChannelGroup,
        startNanos: Long,
        gracefulShutdownTimeoutNanos: Option[Long]
    ): KyoSttpMonad.M[Unit] =
        if !channelGroup.isEmpty && gracefulShutdownTimeoutNanos.exists(
                _ >= JSystem.nanoTime() - startNanos
            )
        then
            Async.sleep(100.millis).andThen(waitForClosedChannels(
                channelGroup,
                startNanos,
                gracefulShutdownTimeoutNanos
            ): Unit < Async)
        else
            nettyFutureToScala(channelGroup.close()).unit

    private def stop(
        ch: Channel,
        eventLoopGroup: EventLoopGroup,
        channelGroup: ChannelGroup,
        eventExecutor: DefaultEventExecutor,
        isShuttingDown: AtomicBoolean,
        gracefulShutdownTimeout: Option[FiniteDuration]
    ): KyoSttpMonad.M[Unit] =
        isShuttingDown.set(true)
        val timeout = gracefulShutdownTimeout.fold(Long.MaxValue)(_.toNanos)
        waitForClosedChannels(
            channelGroup,
            startNanos = JSystem.nanoTime(),
            gracefulShutdownTimeoutNanos = gracefulShutdownTimeout.map(_.toNanos)
        ).flatMap { _ =>
            nettyFutureToScala(ch.close()).flatMap { _ =>
                if config.shutdownEventLoopGroupOnClose then
                    nettyFutureToScala(eventLoopGroup.shutdownGracefully(
                        timeout,
                        timeout,
                        java.util.concurrent.TimeUnit.NANOSECONDS
                    )).unit.andThen {
                        nettyFutureToScala(eventExecutor.shutdownGracefully(
                            timeout,
                            timeout,
                            java.util.concurrent.TimeUnit.NANOSECONDS
                        )).unit
                    }
                else ()
            }
        }
    end stop
end NettyKyoServer

object NettyKyoServer:
    def apply(): NettyKyoServer =
        NettyKyoServer(Vector.empty, NettyKyoServerOptions.default(), NettyConfig.default)

    def apply(serverOptions: NettyKyoServerOptions): NettyKyoServer =
        NettyKyoServer(Vector.empty, serverOptions, NettyConfig.default)

    def apply(config: NettyConfig): NettyKyoServer =
        NettyKyoServer(Vector.empty, NettyKyoServerOptions.default(), config)

    def apply(serverOptions: NettyKyoServerOptions, config: NettyConfig): NettyKyoServer =
        NettyKyoServer(Vector.empty, serverOptions, config)
end NettyKyoServer

case class NettyKyoServerBinding(localSocket: InetSocketAddress, stop: () => KyoSttpMonad.M[Unit]):
    def hostName: String = localSocket.getHostName
    def port: Int        = localSocket.getPort

case class NettyKyoDomainSocketBinding(
    localSocket: DomainSocketAddress,
    stop: () => KyoSttpMonad.M[Unit]
):
    def path: String = localSocket.path()
end NettyKyoDomainSocketBinding
