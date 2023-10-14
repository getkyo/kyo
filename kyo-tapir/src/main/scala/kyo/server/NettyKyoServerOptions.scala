package kyo.server

import kyo._
import kyo.ios._
import kyo.tries._
import kyo.concurrent.fibers._
import kyo.routes._
import com.typesafe.scalalogging.Logger
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.interceptor.{CustomiseInterceptors, Interceptor}
import sttp.tapir.server.netty.internal.NettyDefaults
import sttp.tapir.{Defaults, TapirFile}

/** Options configuring the [[NettyKyoServerInterpreter]], which is being used by [[NettyKyoServer]]
  * to interpret tapir's [[sttp.tapir.server.ServerEndpoint]]s so that they can be served using a
  * Netty server. Contains the interceptors stack and functions for file handling.
  */
case class NettyKyoServerOptions(
    interceptors: List[Interceptor[kyo.routes.internal.M]],
    createFile: ServerRequest => TapirFile > Routes,
    deleteFile: TapirFile => Unit > (Fibers with IOs)
) {
  def prependInterceptor(i: Interceptor[kyo.routes.internal.M]): NettyKyoServerOptions =
    copy(interceptors = i :: interceptors)
  def appendInterceptor(i: Interceptor[kyo.routes.internal.M]): NettyKyoServerOptions =
    copy(interceptors = interceptors :+ i)
}

object NettyKyoServerOptions {

  def default(): NettyKyoServerOptions =
    customiseInterceptors().options

  private def default(
      interceptors: List[Interceptor[kyo.routes.internal.M]]
  ): NettyKyoServerOptions =
    NettyKyoServerOptions(
        interceptors,
        _ => IOs(Defaults.createTempFile()),
        file => IOs(Defaults.deleteFile()(file))
    )

  def customiseInterceptors(): CustomiseInterceptors[kyo.routes.internal.M, NettyKyoServerOptions] =
    CustomiseInterceptors(
        createOptions = (ci: CustomiseInterceptors[kyo.routes.internal.M, NettyKyoServerOptions]) =>
          default(ci.interceptors)
    ).serverLog(defaultServerLog)

  private val log = Logger[NettyKyoServerInterpreter]

  def defaultServerLog: DefaultServerLog[kyo.routes.internal.M] =
    DefaultServerLog[kyo.routes.internal.M](
        doLogWhenReceived = debugLog(_, None),
        doLogWhenHandled = debugLog,
        doLogAllDecodeFailures = debugLog,
        doLogExceptions = errorLog,
        noLog = ()
    )

  private def debugLog(msg: String, exOpt: Option[Throwable]) =
    IOs {
      NettyDefaults.debugLog(log, msg, exOpt)
    }

  private def errorLog(msg: String, ex: Throwable): Unit > IOs = IOs {
    log.error(msg, ex)
  }
}
