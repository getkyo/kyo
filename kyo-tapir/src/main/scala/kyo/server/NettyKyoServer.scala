package kyo.server

import io.netty.channel._
import io.netty.channel.unix.DomainSocketAddress
import kyo._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.routes._
import kyo.server.internal.KyoUtil._
import kyo.tries._
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.model._
import sttp.tapir.server.netty._
import sttp.tapir.server.netty.Route
import sttp.tapir.server.netty.internal.NettyBootstrap
import sttp.tapir.server.netty.internal.NettyServerHandler
import sttp.tapir.server.netty.internal.RunAsync
import scala.concurrent.Future

import java.net.InetSocketAddress
import java.net.SocketAddress
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad._

case class NettyKyoServer(
    routes: Vector[Route[KyoSttpMonad.M]],
    options: NettyKyoServerOptions,
    config: NettyConfig
) {
  def addEndpoint(se: ServerEndpoint[Any, KyoSttpMonad.M]): NettyKyoServer =
    addEndpoints(List(se))
  def addEndpoint(
      se: ServerEndpoint[Any, KyoSttpMonad.M],
      overrideOptions: NettyKyoServerOptions
  ): NettyKyoServer =
    addEndpoints(List(se), overrideOptions)
  def addEndpoints(ses: List[ServerEndpoint[Any, KyoSttpMonad.M]]): NettyKyoServer =
    addRoute(
        NettyKyoServerInterpreter(options).toRoute(ses)
    )
  def addEndpoints(
      ses: List[ServerEndpoint[Any, KyoSttpMonad.M]],
      overrideOptions: NettyKyoServerOptions
  ): NettyKyoServer = addRoute(
      NettyKyoServerInterpreter(overrideOptions).toRoute(ses)
  )

  def addRoute(r: Route[KyoSttpMonad.M]): NettyKyoServer = copy(routes = routes :+ r)
  def addRoutes(r: Iterable[Route[KyoSttpMonad.M]]): NettyKyoServer =
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
      : (SA, () => Unit > Fibers) > Fibers = {
    val eventLoopGroup               = config.eventLoopConfig.initEventLoopGroup()
    val route: Route[KyoSttpMonad.M] = Route.combine(routes)
    val handler: (() => KyoSttpMonad.M[ServerResponse[NettyResponse]]) => (
        Future[ServerResponse[NettyResponse]],
        () => Future[Unit]
    ) =
      f => {
        val fiber: Fiber[ServerResponse[NettyResponse]] =
          IOs.run(Fibers.run(IOs.runLazy(Fibers.init[ServerResponse[NettyResponse]](f())))
            .map(_.transform(identity(_))))
        (
            IOs.run(fiber.toFuture),
            () => {
              IOs.run(fiber.interrupt)
              Future.unit
            }
        )
      }
    val channelFuture =
      NettyBootstrap(
          config,
          new NettyServerHandler[KyoSttpMonad.M](
              route,
              handler,
              config.maxContentLength
          ),
          eventLoopGroup,
          socketOverride
      )

    nettyChannelFutureToScala(channelFuture).map(ch =>
      (ch.localAddress().asInstanceOf[SA], () => stop(ch, eventLoopGroup))
    )
  }

  private def stop(ch: Channel, eventLoopGroup: EventLoopGroup): Unit > Fibers = {
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

  private[kyo] val runAsync = new RunAsync[KyoSttpMonad.M] {
    override def apply[T](f: => T > Fibers): Unit =
      IOs.run {
        Fibers.init {
          import Flat.unsafe.unchecked
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
    stop: () => Unit > Fibers
) {
  def path: String = localSocket.path()
}
