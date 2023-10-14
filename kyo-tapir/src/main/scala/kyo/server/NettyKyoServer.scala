package kyo.server

import kyo._
import kyo.routes._
import kyo.ios._
import kyo.tries._
import kyo.concurrent.fibers._
import kyo.server.internal.KyoMonadError._

import io.netty.channel._
import io.netty.channel.unix.DomainSocketAddress
import sttp.monad.MonadError
import sttp.tapir.server.ServerEndpoint
import kyo.server.internal.KyoUtil.{nettyChannelFutureToScala, nettyFutureToScala}
import sttp.tapir.server.netty.internal.{NettyBootstrap, NettyServerHandler}
import sttp.tapir.server.netty.internal.RunAsync
import sttp.tapir.server.netty.{NettyConfig, Route}

import java.net.{InetSocketAddress, SocketAddress}
import java.nio.file.{Path, Paths}
import java.util.UUID

case class NettyKyoServer(
    routes: Vector[Route[kyo.routes.internal.M]],
    options: NettyKyoServerOptions,
    config: NettyConfig
) {
  def addEndpoint(se: ServerEndpoint[Any, kyo.routes.internal.M]): NettyKyoServer =
    addEndpoints(List(se))
  def addEndpoint(
      se: ServerEndpoint[Any, kyo.routes.internal.M],
      overrideOptions: NettyKyoServerOptions
  ): NettyKyoServer =
    addEndpoints(List(se), overrideOptions)
  def addEndpoints(ses: List[ServerEndpoint[Any, kyo.routes.internal.M]]): NettyKyoServer =
    addRoute(
        NettyKyoServerInterpreter(options).toRoute(ses)
    )
  def addEndpoints(
      ses: List[ServerEndpoint[Any, kyo.routes.internal.M]],
      overrideOptions: NettyKyoServerOptions
  ): NettyKyoServer = addRoute(
      NettyKyoServerInterpreter(overrideOptions).toRoute(ses)
  )

  def addRoute(r: Route[kyo.routes.internal.M]): NettyKyoServer = copy(routes = routes :+ r)
  def addRoutes(r: Iterable[Route[kyo.routes.internal.M]]): NettyKyoServer =
    copy(routes = routes ++ r)

  def options(o: NettyKyoServerOptions): NettyKyoServer = copy(options = o)

  def config(c: NettyConfig): NettyKyoServer                      = copy(config = c)
  def modifyConfig(f: NettyConfig => NettyConfig): NettyKyoServer = config(f(config))

  def host(h: String): NettyKyoServer = modifyConfig(_.host(h))

  def port(p: Int): NettyKyoServer = modifyConfig(_.port(p))

  def start(): NettyKyoServerBinding > Routes =
    startUsingSocketOverride[InetSocketAddress](None).map { case (socket, stop) =>
      NettyKyoServerBinding(socket, stop)
    }

  def startUsingDomainSocket(path: Option[Path] = None): NettyKyoDomainSocketBinding > Routes =
    startUsingDomainSocket(path.getOrElse(Paths.get(
        System.getProperty("java.io.tmpdir"),
        UUID.randomUUID().toString
    )))

  def startUsingDomainSocket(path: Path): NettyKyoDomainSocketBinding > Routes =
    startUsingSocketOverride(Some(new DomainSocketAddress(path.toFile))).map {
      case (socket, stop) =>
        NettyKyoDomainSocketBinding(socket, stop)
    }

  private def startUsingSocketOverride[SA <: SocketAddress](socketOverride: Option[SA])
      : (SA, () => Unit > (Fibers with IOs)) > (Fibers with IOs) = {
    val eventLoopGroup                      = config.eventLoopConfig.initEventLoopGroup()
    val route: Route[kyo.routes.internal.M] = Route.combine(routes)

    val channelFuture =
      NettyBootstrap(
          config,
          new NettyServerHandler[kyo.routes.internal.M](
              route,
              (f: () => Unit > (Fibers with IOs)) =>
                NettyKyoServer.runAsync(f()),
              config.maxContentLength
          ),
          eventLoopGroup,
          socketOverride
      )

    nettyChannelFutureToScala(channelFuture).map(ch =>
      (ch.localAddress().asInstanceOf[SA], () => stop(ch, eventLoopGroup))
    )
  }

  private def stop(ch: Channel, eventLoopGroup: EventLoopGroup): Unit > (Fibers with IOs) = {
    IOs {
      nettyFutureToScala(ch.close()).flatMap { _ =>
        if (config.shutdownEventLoopGroupOnClose) {
          nettyFutureToScala(eventLoopGroup.shutdownGracefully()).unit
        } else
          ()
      }
    }
  }
}

object NettyKyoServer {

  private[kyo] val runAsync = new RunAsync[kyo.routes.internal.M] {
    override def apply[T](f: => T > (Fibers with IOs)): Unit =
      IOs.run {
        Fibers.forkFiber {
          IOs.run(Fibers.run(IOs.runLazy(f)).unit)
        }
      }
  }

  def apply(): NettyKyoServer =
    NettyKyoServer(
        Vector.empty,
        NettyKyoServerOptions.default(),
        NettyConfig.defaultWithStreaming
    )
  def apply(options: NettyKyoServerOptions): NettyKyoServer =
    NettyKyoServer(Vector.empty, options, NettyConfig.defaultWithStreaming)
  def apply(config: NettyConfig): NettyKyoServer =
    NettyKyoServer(Vector.empty, NettyKyoServerOptions.default(), config)
  def apply(
      options: NettyKyoServerOptions,
      config: NettyConfig
  ): NettyKyoServer =
    NettyKyoServer(Vector.empty, options, config)
}

case class NettyKyoServerBinding(localSocket: InetSocketAddress, stop: () => Unit > Routes) {
  def hostName: String = localSocket.getHostName
  def port: Int        = localSocket.getPort
}

case class NettyKyoDomainSocketBinding(
    localSocket: DomainSocketAddress,
    stop: () => Unit > (Fibers with IOs)
) {
  def path: String = localSocket.path()
}
