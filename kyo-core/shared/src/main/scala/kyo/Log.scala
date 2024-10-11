package kyo

import kyo.internal.LogPlatformSpecific

/** Logging utility object for Kyo applications. */
object Log extends LogPlatformSpecific:

    private val local = Local.init[Unsafe](unsafe)

    /** Executes a function with a custom Unsafe logger.
      *
      * @param u
      *   The Unsafe logger to use
      * @param f
      *   The function to execute
      * @return
      *   The result of the function execution
      */
    def let[A, S](u: Unsafe)(f: => A < (IO & S))(using Frame): A < (IO & S) =
        local.let(u)(f)

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe:
        def traceEnabled: Boolean
        def debugEnabled: Boolean
        def infoEnabled: Boolean
        def warnEnabled: Boolean
        def errorEnabled: Boolean

        def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit
        def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        class ConsoleLogger(name: String) extends Log.Unsafe:
            inline def traceEnabled: Boolean = true

            inline def debugEnabled: Boolean = true

            inline def infoEnabled: Boolean = true

            inline def warnEnabled: Boolean = true

            inline def errorEnabled: Boolean = true

            inline def trace(msg: => String)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if traceEnabled then println(s"TRACE $name -- [${frame.parse.position}] $msg")

            inline def trace(msg: => String, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if traceEnabled then println(s"TRACE $name -- [${frame.parse.position}] $msg $t")

            inline def debug(msg: => String)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if debugEnabled then println(s"DEBUG $name -- [${frame.parse.position}] $msg")

            inline def debug(msg: => String, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if debugEnabled then println(s"DEBUG $name -- [${frame.parse.position}] $msg $t")

            inline def info(msg: => String)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if infoEnabled then println(s"INFO $name -- [${frame.parse.position}] $msg")

            inline def info(msg: => String, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if infoEnabled then println(s"INFO $name -- [${frame.parse.position}] $msg $t")

            inline def warn(msg: => String)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if warnEnabled then println(s"WARN $name -- [${frame.parse.position}] $msg")

            inline def warn(msg: => String, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if warnEnabled then println(s"WARN $name -- [${frame.parse.position}] $msg $t")

            inline def error(msg: => String)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if errorEnabled then println(s"ERROR $name -- [${frame.parse.position}] $msg")

            inline def error(msg: => String, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if errorEnabled then println(s"ERROR $name -- [${frame.parse.position}] $msg $t")
        end ConsoleLogger
    end Unsafe

    private inline def logWhen(inline enabled: Unsafe => Boolean)(inline log: AllowUnsafe ?=> Unsafe => Unit)(using
        inline frame: Frame
    ): Unit < IO =
        local.use { unsafe =>
            if enabled(unsafe) then
                IO.Unsafe(log(unsafe))
            else
                (
            )
        }

    /** Logs a trace message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An IO effect that logs the message
      */
    inline def trace(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.traceEnabled)(_.trace(msg))

    /** Logs a trace message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An IO effect that logs the message and exception
      */
    inline def trace(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.traceEnabled)(_.trace(msg, t))

    /** Logs a debug message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An IO effect that logs the message
      */
    inline def debug(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.debugEnabled)(_.debug(msg))

    /** Logs a debug message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An IO effect that logs the message and exception
      */
    inline def debug(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.debugEnabled)(_.debug(msg, t))

    /** Logs an info message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An IO effect that logs the message
      */
    inline def info(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.infoEnabled)(_.info(msg))

    /** Logs an info message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An IO effect that logs the message and exception
      */
    inline def info(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.infoEnabled)(_.info(msg, t))

    /** Logs a warning message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An IO effect that logs the message
      */
    inline def warn(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.warnEnabled)(_.warn(msg))

    /** Logs a warning message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An IO effect that logs the message and exception
      */
    inline def warn(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.warnEnabled)(_.warn(msg, t))

    /** Logs an error message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An IO effect that logs the message
      */
    inline def error(inline msg: => String)(using inline frame: Frame): Unit < IO =
        logWhen(_.errorEnabled)(_.error(msg))

    /** Logs an error message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An IO effect that logs the message and exception
      */
    inline def error(inline msg: => String, inline t: => Throwable)(using inline frame: Frame): Unit < IO =
        logWhen(_.errorEnabled)(_.error(msg, t))

end Log
