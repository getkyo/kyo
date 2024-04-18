package kyo
trait logsPlatformSpecific:
    val unsafe: Logs.Unsafe = logsPlatformSpecific.Unsafe.SL4J("kyo.logs")

object logsPlatformSpecific:
    object Unsafe:
        object SL4J:
            def apply(name: String) = new SL4J(org.slf4j.LoggerFactory.getLogger(name))
        class SL4J(logger: org.slf4j.Logger) extends Logs.Unsafe:
            inline def traceEnabled: Boolean = logger.isTraceEnabled

            inline def debugEnabled: Boolean = logger.isDebugEnabled

            inline def infoEnabled: Boolean = logger.isInfoEnabled

            inline def warnEnabled: Boolean = logger.isWarnEnabled

            inline def errorEnabled: Boolean = logger.isErrorEnabled

            inline def trace(msg: => String)(
                using file: internal.Position
            ): Unit = if traceEnabled then logger.trace(s"[$file] $msg")

            inline def trace(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if traceEnabled then logger.trace(s"[$file] $msg", t)

            inline def debug(msg: => String)(
                using file: internal.Position
            ): Unit = if debugEnabled then logger.debug(s"[$file] $msg")

            inline def debug(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if debugEnabled then logger.debug(s"[$file] $msg", t)

            inline def info(msg: => String)(
                using file: internal.Position
            ): Unit = if infoEnabled then logger.info(s"[$file] $msg")

            inline def info(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if infoEnabled then logger.info(s"[$file] $msg", t)

            inline def warn(msg: => String)(
                using file: internal.Position
            ): Unit = if warnEnabled then logger.warn(s"[$file] $msg")

            inline def warn(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if warnEnabled then logger.warn(s"[$file] $msg", t)

            inline def error(msg: => String)(
                using file: internal.Position
            ): Unit = if errorEnabled then logger.error(s"[$file] $msg")

            inline def error(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if errorEnabled then logger.error(s"[$file] $msg", t)
        end SL4J
    end Unsafe
end logsPlatformSpecific
