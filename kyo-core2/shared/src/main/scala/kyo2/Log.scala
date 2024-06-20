package kyo2

import kyo2.internal.LogPlatformSpecific

object Log extends LogPlatformSpecific:

    private val local = Local.init[Unsafe](unsafe)

    def let[T, S](u: Unsafe)(f: => T < (IO & S))(using Frame): T < (IO & S) =
        local.let(u)(f)

    trait Unsafe:
        def traceEnabled: Boolean
        def debugEnabled: Boolean
        def infoEnabled: Boolean
        def warnEnabled: Boolean
        def errorEnabled: Boolean

        def trace(msg: => String)(using frame: Frame): Unit
        def trace(msg: => String, t: => Throwable)(using frame: Frame): Unit
        def debug(msg: => String)(using frame: Frame): Unit
        def debug(msg: => String, t: => Throwable)(using frame: Frame): Unit
        def info(msg: => String)(using frame: Frame): Unit
        def info(msg: => String, t: => Throwable)(using frame: Frame): Unit
        def warn(msg: => String)(using frame: Frame): Unit
        def warn(msg: => String, t: => Throwable)(using frame: Frame): Unit
        def error(msg: => String)(using frame: Frame): Unit
        def error(msg: => String, t: => Throwable)(using frame: Frame): Unit
    end Unsafe

    object Unsafe:
        class ConsoleLogger(name: String) extends Log.Unsafe:
            inline def traceEnabled: Boolean = true

            inline def debugEnabled: Boolean = true

            inline def infoEnabled: Boolean = true

            inline def warnEnabled: Boolean = true

            inline def errorEnabled: Boolean = true

            inline def trace(msg: => String)(
                using frame: Frame
            ): Unit = if traceEnabled then println(s"TRACE $name -- [${frame.parse.position}] $msg")

            inline def trace(msg: => String, t: => Throwable)(
                using frame: Frame
            ): Unit = if traceEnabled then println(s"TRACE $name -- [${frame.parse.position}] $msg $t")

            inline def debug(msg: => String)(
                using frame: Frame
            ): Unit = if debugEnabled then println(s"DEBUG $name -- [${frame.parse.position}] $msg")

            inline def debug(msg: => String, t: => Throwable)(
                using frame: Frame
            ): Unit = if debugEnabled then println(s"DEBUG $name -- [${frame.parse.position}] $msg $t")

            inline def info(msg: => String)(
                using frame: Frame
            ): Unit = if infoEnabled then println(s"INFO $name -- [${frame.parse.position}] $msg")

            inline def info(msg: => String, t: => Throwable)(
                using frame: Frame
            ): Unit = if infoEnabled then println(s"INFO $name -- [${frame.parse.position}] $msg $t")

            inline def warn(msg: => String)(
                using frame: Frame
            ): Unit = if warnEnabled then println(s"WARN $name -- [${frame.parse.position}] $msg")

            inline def warn(msg: => String, t: => Throwable)(
                using frame: Frame
            ): Unit = if warnEnabled then println(s"WARN $name -- [${frame.parse.position}] $msg $t")

            inline def error(msg: => String)(
                using frame: Frame
            ): Unit = if errorEnabled then println(s"ERROR $name -- [${frame.parse.position}] $msg")

            inline def error(msg: => String, t: => Throwable)(
                using frame: Frame
            ): Unit = if errorEnabled then println(s"ERROR $name -- [${frame.parse.position}] $msg $t")
        end ConsoleLogger
    end Unsafe

    private inline def logWhen(inline enabled: Unsafe => Boolean)(inline log: Unsafe => Unit): Unit < IO =
        local.use { unsafe =>
            if enabled(unsafe) then
                IO(log(unsafe))
            else
                (
            )
        }
    inline def trace(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.traceEnabled)(_.trace(msg))

    inline def trace(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.traceEnabled)(_.trace(msg, t))

    inline def debug(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.debugEnabled)(_.debug(msg))

    inline def debug(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.debugEnabled)(_.debug(msg, t))

    inline def info(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.infoEnabled)(_.info(msg))

    inline def info(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.infoEnabled)(_.info(msg, t))

    inline def warn(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.warnEnabled)(_.warn(msg))

    inline def warn(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.warnEnabled)(_.warn(msg, t))

    inline def error(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.errorEnabled)(_.error(msg))

    inline def error(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.errorEnabled)(_.error(msg, t))

end Log
