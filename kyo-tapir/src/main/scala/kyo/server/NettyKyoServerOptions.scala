package kyo.server

import com.typesafe.scalalogging.Logger
import kyo._
import kyo.concurrent.fibers._
import kyo.ios._
import kyo.routes._
import kyo.tries._
import sttp.tapir.Defaults
import sttp.tapir.TapirFile
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.CustomiseInterceptors
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.netty.internal.NettyDefaults
import kyo.internal.KyoSttpMonad
import kyo.internal.KyoSttpMonad._

case class NettyKyoServerOptions(
    interceptors: List[Interceptor[KyoSttpMonad.M]],
    createFile: ServerRequest => TapirFile > Routes,
    deleteFile: TapirFile => Unit > (Fibers with IOs)
) {
  def prependInterceptor(i: Interceptor[KyoSttpMonad.M]): NettyKyoServerOptions =
    copy(interceptors = i :: interceptors)
  def appendInterceptor(i: Interceptor[KyoSttpMonad.M]): NettyKyoServerOptions =
    copy(interceptors = interceptors :+ i)
}

object NettyKyoServerOptions {

  def default(): NettyKyoServerOptions =
    customiseInterceptors().options

  private def default(
      interceptors: List[Interceptor[KyoSttpMonad.M]]
  ): NettyKyoServerOptions =
    NettyKyoServerOptions(
        interceptors,
        _ => IOs(Defaults.createTempFile()),
        file => IOs(Defaults.deleteFile()(file))
    )

  def customiseInterceptors(): CustomiseInterceptors[KyoSttpMonad.M, NettyKyoServerOptions] =
    CustomiseInterceptors(
        createOptions = (ci: CustomiseInterceptors[KyoSttpMonad.M, NettyKyoServerOptions]) =>
          default(ci.interceptors)
    ).serverLog(defaultServerLog)

  private val log = Logger[NettyKyoServerInterpreter]

  def defaultServerLog: DefaultServerLog[KyoSttpMonad.M] =
    DefaultServerLog[KyoSttpMonad.M](
        doLogWhenReceived = debugLog(_, None),
        doLogWhenHandled = debugLog,
        doLogAllDecodeFailures = debugLog,
        doLogExceptions = errorLog,
        noLog = ()
    )

  private def debugLog(msg: String, exOpt: Option[Throwable]) =
    NettyDefaults.debugLog(log, msg, exOpt)

  private def errorLog(msg: String, ex: Throwable): Unit > IOs =
    log.error(msg, ex)
}
