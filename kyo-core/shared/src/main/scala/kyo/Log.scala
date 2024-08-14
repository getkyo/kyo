package kyo

import kyo.internal.Param
import kyo.internal.Trace

object Logs extends logsPlatformSpecific:
    private val local = Locals.init(unsafe)

    def let[T, S](u: Unsafe)(f: => T < (IOs & S))(using Trace): T < (IOs & S) =
        local.let(u)(f)

    trait Unsafe:
        def traceEnabled: Boolean
        def debugEnabled: Boolean
        def infoEnabled: Boolean
        def warnEnabled: Boolean
        def errorEnabled: Boolean

        inline def trace(inline params: Param[?]*)(
            using inline file: internal.Position
        ): Unit =
            trace(Param.show(params*))

        def trace(msg: => String)(
            using file: internal.Position
        ): Unit

        def trace(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        inline def debug(inline params: Param[?]*)(
            using inline file: internal.Position
        ): Unit =
            debug(Param.show(params*))

        def debug(msg: => String)(
            using file: internal.Position
        ): Unit

        def debug(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        inline def info(inline params: Param[?]*)(
            using inline file: internal.Position
        ): Unit =
            info(Param.show(params*))

        def info(msg: => String)(
            using file: internal.Position
        ): Unit

        def info(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        inline def warn(inline params: Param[?]*)(
            using inline file: internal.Position
        ): Unit =
            warn(Param.show(params*))

        def warn(msg: => String)(
            using file: internal.Position
        ): Unit

        def warn(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        def error(msg: => String)(
            using file: internal.Position
        ): Unit

        inline def error(inline params: Param[?]*)(
            using inline file: internal.Position
        ): Unit =
            error(Param.show(params*))

        def error(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit
    end Unsafe

    object Unsafe:
        class ConsoleLogger(name: String) extends Logs.Unsafe:
            inline def traceEnabled: Boolean = true

            inline def debugEnabled: Boolean = true

            inline def infoEnabled: Boolean = true

            inline def warnEnabled: Boolean = true

            inline def errorEnabled: Boolean = true

            inline def trace(msg: => String)(
                using file: internal.Position
            ): Unit = if traceEnabled then println(s"TRACE $name -- [$file] $msg")

            inline def trace(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if traceEnabled then println(s"TRACE $name -- [$file] $msg $t")

            inline def debug(msg: => String)(
                using file: internal.Position
            ): Unit = if debugEnabled then println(s"DEBUG $name -- [$file] $msg")

            inline def debug(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if debugEnabled then println(s"DEBUG $name -- [$file] $msg $t")

            inline def info(msg: => String)(
                using file: internal.Position
            ): Unit = if infoEnabled then println(s"INFO $name -- [$file] $msg")

            inline def info(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if infoEnabled then println(s"INFO $name -- [$file] $msg $t")

            inline def warn(msg: => String)(
                using file: internal.Position
            ): Unit = if warnEnabled then println(s"WARN $name -- [$file] $msg")

            inline def warn(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if warnEnabled then println(s"WARN $name -- [$file] $msg $t")

            inline def error(msg: => String)(
                using file: internal.Position
            ): Unit = if errorEnabled then println(s"ERROR $name -- [$file] $msg")

            inline def error(msg: => String, t: => Throwable)(
                using file: internal.Position
            ): Unit = if errorEnabled then println(s"ERROR $name -- [$file] $msg $t")
        end ConsoleLogger
    end Unsafe

    private inline def logWhen(inline enabled: Unsafe => Boolean)(inline log: Unsafe => Unit)(using Trace): Unit < IOs =
        local.use { unsafe =>
            if enabled(unsafe) then
                IOs(log(unsafe))
            else
                IOs.unit
        }

    inline def trace(inline params: Param[?]*)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        trace(Param.show(params*))

    inline def trace(inline msg: => String)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.traceEnabled)(_.trace(msg))

    inline def trace(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.traceEnabled)(_.trace(msg, t))

    inline def debug(inline params: Param[?]*)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        debug(Param.show(params*))

    inline def debug(inline msg: => String)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.debugEnabled)(_.debug(msg))

    inline def debug(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.debugEnabled)(_.debug(msg, t))

    inline def info(inline params: Param[?]*)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        info(Param.show(params*))

    inline def info(inline msg: => String)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.infoEnabled)(_.info(msg))

    inline def info(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.infoEnabled)(_.info(msg, t))

    inline def warn(inline params: Param[?]*)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        warn(Param.show(params*))

    inline def warn(inline msg: => String)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.warnEnabled)(_.warn(msg))

    inline def warn(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.warnEnabled)(_.warn(msg, t))

    inline def error(inline params: Param[?]*)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        error(Param.show(params*))

    inline def error(inline msg: => String)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.errorEnabled)(_.error(msg))

    inline def error(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    )(using Trace): Unit < IOs =
        logWhen(_.errorEnabled)(_.error(msg, t))

end Logs
