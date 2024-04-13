package kyo

import org.slf4j.LoggerFactory

object Logs:
    val unsafe: Unsafe = Unsafe(LoggerFactory.getLogger("kyo.logs"))
    private val local  = Locals.init(unsafe)

    def let[T, S](u: Unsafe)(f: => T < (IOs & S)): T < (IOs & S) =
        local.let(u)(f)

    trait Unsafe:
        def traceEnabled: Boolean
        def debugEnabled: Boolean
        def infoEnabled: Boolean
        def warnEnabled: Boolean
        def errorEnabled: Boolean

        def trace(msg: => String)(
            using file: internal.FileNameWithLine
        ): Unit

        def trace(msg: => String, t: => Throwable)(
            using file: internal.FileNameWithLine
        ): Unit

        def debug(msg: => String)(
            using file: internal.FileNameWithLine
        ): Unit

        def debug(msg: => String, t: => Throwable)(
            using file: internal.FileNameWithLine
        ): Unit

        def info(msg: => String)(
            using file: internal.FileNameWithLine
        ): Unit

        def info(msg: => String, t: => Throwable)(
            using file: internal.FileNameWithLine
        ): Unit

        def warn(msg: => String)(
            using file: internal.FileNameWithLine
        ): Unit

        def warn(msg: => String, t: => Throwable)(
            using file: internal.FileNameWithLine
        ): Unit

        def error(msg: => String)(
            using file: internal.FileNameWithLine
        ): Unit

        def error(msg: => String, t: => Throwable)(
            using file: internal.FileNameWithLine
        ): Unit
    end Unsafe

    object Unsafe:
        def apply(logger: org.slf4j.Logger): Unsafe = new Unsafe:
            inline def traceEnabled: Boolean = logger.isTraceEnabled
            inline def debugEnabled: Boolean = logger.isDebugEnabled
            inline def infoEnabled: Boolean  = logger.isInfoEnabled
            inline def warnEnabled: Boolean  = logger.isWarnEnabled
            inline def errorEnabled: Boolean = logger.isErrorEnabled

            inline def trace(msg: => String)(
                using file: internal.FileNameWithLine
            ): Unit = if traceEnabled then logger.trace(s"[$file] $msg")

            inline def trace(msg: => String, t: => Throwable)(
                using file: internal.FileNameWithLine
            ): Unit = if traceEnabled then logger.trace(s"[$file] $msg", t)

            inline def debug(msg: => String)(
                using file: internal.FileNameWithLine
            ): Unit = if debugEnabled then logger.debug(s"[$file] $msg")

            inline def debug(msg: => String, t: => Throwable)(
                using file: internal.FileNameWithLine
            ): Unit = if debugEnabled then logger.debug(s"[$file] $msg", t)

            inline def info(msg: => String)(
                using file: internal.FileNameWithLine
            ): Unit = if infoEnabled then logger.info(s"[$file] $msg")

            inline def info(msg: => String, t: => Throwable)(
                using file: internal.FileNameWithLine
            ): Unit = if infoEnabled then logger.info(s"[$file] $msg", t)

            inline def warn(msg: => String)(
                using file: internal.FileNameWithLine
            ): Unit = if warnEnabled then logger.warn(s"[$file] $msg")

            inline def warn(msg: => String, t: => Throwable)(
                using file: internal.FileNameWithLine
            ): Unit = if warnEnabled then logger.warn(s"[$file] $msg", t)

            inline def error(msg: => String)(
                using file: internal.FileNameWithLine
            ): Unit = if errorEnabled then logger.error(s"[$file] $msg")

            inline def error(msg: => String, t: => Throwable)(
                using file: internal.FileNameWithLine
            ): Unit = if errorEnabled then logger.error(s"[$file] $msg", t)

    end Unsafe

    private inline def logWhen(inline enabled: Unsafe => Boolean)(inline log: Unsafe => Unit): Unit < IOs =
        local.use { unsafe =>
            if enabled(unsafe) then
                IOs(log(unsafe))
            else
                IOs.unit
        }

    inline def trace(inline msg: => String)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.traceEnabled)(_.trace(msg))

    inline def trace(inline msg: => String, inline t: => Throwable)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.traceEnabled)(_.trace(msg, t))

    inline def debug(inline msg: => String)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.debugEnabled)(_.debug(msg))

    inline def debug(inline msg: => String, inline t: => Throwable)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.debugEnabled)(_.debug(msg, t))

    inline def info(inline msg: => String)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.infoEnabled)(_.info(msg))

    inline def info(inline msg: => String, inline t: => Throwable)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.infoEnabled)(_.info(msg, t))

    inline def warn(inline msg: => String)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.warnEnabled)(_.warn(msg))

    inline def warn(inline msg: => String, inline t: => Throwable)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.warnEnabled)(_.warn(msg, t))

    inline def error(inline msg: => String)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.errorEnabled)(_.error(msg))

    inline def error(inline msg: => String, inline t: => Throwable)(
        using file: internal.FileNameWithLine
    ): Unit < IOs =
        logWhen(_.errorEnabled)(_.error(msg, t))

end Logs
