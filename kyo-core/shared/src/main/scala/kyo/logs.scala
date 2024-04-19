package kyo

object Logs extends logsPlatformSpecific:
    private val local = Locals.init(unsafe)

    def let[T, S](u: Unsafe)(f: => T < (IOs & S)): T < (IOs & S) =
        local.let(u)(f)

    trait Unsafe:
        def traceEnabled: Boolean
        def debugEnabled: Boolean
        def infoEnabled: Boolean
        def warnEnabled: Boolean
        def errorEnabled: Boolean

        def trace(msg: => String)(
            using file: internal.Position
        ): Unit

        def trace(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        def debug(msg: => String)(
            using file: internal.Position
        ): Unit

        def debug(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        def info(msg: => String)(
            using file: internal.Position
        ): Unit

        def info(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        def warn(msg: => String)(
            using file: internal.Position
        ): Unit

        def warn(msg: => String, t: => Throwable)(
            using file: internal.Position
        ): Unit

        def error(msg: => String)(
            using file: internal.Position
        ): Unit

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

    private inline def logWhen(inline enabled: Unsafe => Boolean)(inline log: Unsafe => Unit): Unit < IOs =
        local.use { unsafe =>
            if enabled(unsafe) then
                IOs(log(unsafe))
            else
                IOs.unit
        }

    inline def trace(inline msg: => String)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.traceEnabled)(_.trace(msg))

    inline def trace(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.traceEnabled)(_.trace(msg, t))

    inline def debug(inline msg: => String)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.debugEnabled)(_.debug(msg))

    inline def debug(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.debugEnabled)(_.debug(msg, t))

    inline def info(inline msg: => String)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.infoEnabled)(_.info(msg))

    inline def info(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.infoEnabled)(_.info(msg, t))

    inline def warn(inline msg: => String)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.warnEnabled)(_.warn(msg))

    inline def warn(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.warnEnabled)(_.warn(msg, t))

    inline def error(inline msg: => String)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.errorEnabled)(_.error(msg))

    inline def error(inline msg: => String, inline t: => Throwable)(
        using inline file: internal.Position
    ): Unit < IOs =
        logWhen(_.errorEnabled)(_.error(msg, t))

end Logs
