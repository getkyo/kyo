package sttp.tapir.server.netty

import kyo.internal.KyoSttpMonad
import org.slf4j.LoggerFactory
import sttp.tapir.Defaults
import sttp.tapir.TapirFile
import sttp.tapir.model.ServerRequest
import sttp.tapir.server.interceptor.CustomiseInterceptors
import sttp.tapir.server.interceptor.Interceptor
import sttp.tapir.server.interceptor.log.DefaultServerLog
import sttp.tapir.server.netty.internal.NettyDefaults

case class NettyKyoServerOptions(
    interceptors: List[Interceptor[KyoSttpMonad.M]],
    createFile: ServerRequest => KyoSttpMonad.M[TapirFile],
    deleteFile: TapirFile => KyoSttpMonad.M[Unit],
    forkExecution: Boolean
):
    def prependInterceptor(i: Interceptor[KyoSttpMonad.M]): NettyKyoServerOptions =
        copy(interceptors = i :: interceptors)
    def appendInterceptor(i: Interceptor[KyoSttpMonad.M]): NettyKyoServerOptions =
        copy(interceptors = interceptors :+ i)
    def forkExecution(b: Boolean = true): NettyKyoServerOptions =
        copy(forkExecution = b)
end NettyKyoServerOptions

object NettyKyoServerOptions:

    def default(enableLogging: Boolean = true): NettyKyoServerOptions =
        customiseInterceptors(enableLogging).options

    private def default(interceptors: List[Interceptor[KyoSttpMonad.M]]): NettyKyoServerOptions =
        NettyKyoServerOptions(
            interceptors,
            _ =>
                Defaults.createTempFile(),
            file =>
                Defaults.deleteFile()(file),
            true
        )

    def customiseInterceptors(enableLogging: Boolean = true): CustomiseInterceptors[KyoSttpMonad.M, NettyKyoServerOptions] =
        val ci =
            CustomiseInterceptors(
                createOptions =
                    (ci: CustomiseInterceptors[KyoSttpMonad.M, NettyKyoServerOptions]) =>
                        default(ci.interceptors)
            )
        if !enableLogging then ci
        else ci.serverLog(defaultServerLog)
    end customiseInterceptors

    private val log = LoggerFactory.getLogger(getClass.getName)

    lazy val defaultServerLog: DefaultServerLog[KyoSttpMonad.M] =
        DefaultServerLog(
            doLogWhenReceived = debugLog(_, None),
            doLogWhenHandled = debugLog,
            doLogAllDecodeFailures = debugLog,
            doLogExceptions = (msg: String, ex: Throwable) => log.error(msg, ex),
            noLog = ()
        )

    private def debugLog(msg: String, exOpt: Option[Throwable]): KyoSttpMonad.M[Unit] =
        NettyDefaults.debugLog(log, msg, exOpt)
end NettyKyoServerOptions
