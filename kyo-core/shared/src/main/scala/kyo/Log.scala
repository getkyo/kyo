package kyo

import kyo.internal.LogPlatformSpecific

final case class Log(unsafe: Log.Unsafe):
    def level: Log.Level                                                          = unsafe.level
    inline def trace(inline msg: => Text)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.trace(msg))
    inline def trace(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        Sync.Unsafe(unsafe.trace(msg, t))
    inline def debug(inline msg: => Text)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.debug(msg))
    inline def debug(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        Sync.Unsafe(unsafe.debug(msg, t))
    inline def info(inline msg: => Text)(using inline frame: Frame): Unit < Sync                         = Sync.Unsafe(unsafe.info(msg))
    inline def info(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync = Sync.Unsafe(unsafe.info(msg, t))
    inline def warn(inline msg: => Text)(using inline frame: Frame): Unit < Sync                         = Sync.Unsafe(unsafe.warn(msg))
    inline def warn(inline msg: => Text, t: => Throwable)(using inline frame: Frame): Unit < Sync        = Sync.Unsafe(unsafe.warn(msg, t))
    inline def error(inline msg: => Text)(using inline frame: Frame): Unit < Sync                        = Sync.Unsafe(unsafe.error(msg))
    inline def error(inline msg: => Text, t: => Throwable)(using inline frame: Frame): Unit < Sync       = Sync.Unsafe(unsafe.error(msg, t))
end Log

/** Logging utility object for Kyo applications. */
object Log extends LogPlatformSpecific:

    enum Level(private val priority: Int) derives CanEqual:
        def enabled(other: Level): Boolean = other.priority <= priority
        case trace  extends Level(10)
        case debug  extends Level(20)
        case info   extends Level(30)
        case warn   extends Level(40)
        case error  extends Level(50)
        case silent extends Level(60)
    end Level

    private val local = Local.init(live)

    /** Executes a function with a custom Unsafe logger.
      *
      * @param u
      *   The Unsafe logger to use
      * @param f
      *   The function to execute
      * @return
      *   The result of the function execution
      */
    def let[A, S](log: Log)(f: => A < S)(using Frame): A < S =
        local.let(log)(f)

    /** Gets the current logger from the local context.
      *
      * @return
      *   The current Log instance wrapped in an effect
      */
    def get(using Frame): Log < Any = local.get

    /** Executes a function with access to the current logger.
      *
      * @param f
      *   The function to execute, which takes a Log instance as input
      * @return
      *   The result of the function execution
      */
    def use[A, S](f: Log => A < S)(using Frame): A < S = local.use(f)

    /** Executes an effect with a console logger using the default name "kyo.logs" and debug level.
      *
      * @param v
      *   The effect to execute with the console logger
      * @return
      *   The result of executing the effect with the console logger
      */
    def withConsoleLogger[A, S](v: A < S)(using Frame): A < S =
        withConsoleLogger()(v)

    /** Executes an effect with a console logger using a custom name and log level.
      *
      * @param name
      *   The name to use for the console logger
      * @param level
      *   The log level
      * @param v
      *   The effect to execute with the console logger
      * @return
      *   The result of executing the effect with the console logger
      */
    def withConsoleLogger[A, S](name: String = "kyo.logs", level: Level = Level.debug)(v: A < S)(using Frame): A < S =
        let(Log(Unsafe.ConsoleLogger(name, level)))(v)

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    abstract class Unsafe extends Serializable:
        def level: Level

        def trace(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit
        def trace(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def debug(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit
        def debug(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def info(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit
        def info(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def warn(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit
        def warn(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit
        def error(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit
        def error(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit

        def safe: Log = Log(this)
    end Unsafe

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:
        case class ConsoleLogger(name: String, level: Level) extends Log.Unsafe:
            inline def trace(msg: => Text)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.trace.enabled(level) then println(s"TRACE $name -- [${frame.position.show}] $msg")

            inline def trace(msg: => Text, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.trace.enabled(level) then println(s"TRACE $name -- [${frame.position.show}] $msg $t")

            inline def debug(msg: => Text)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit =
                if Level.debug.enabled(level) then println(s"DEBUG $name -- [${frame.position.show}] $msg")

            inline def debug(msg: => Text, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.debug.enabled(level) then println(s"DEBUG $name -- [${frame.position.show}] $msg $t")

            inline def info(msg: => Text)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.info.enabled(level) then println(s"INFO $name -- [${frame.position.show}] $msg")

            inline def info(msg: => Text, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.info.enabled(level) then println(s"INFO $name -- [${frame.position.show}] $msg $t")

            inline def warn(msg: => Text)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.warn.enabled(level) then println(s"WARN $name -- [${frame.position.show}] $msg")

            inline def warn(msg: => Text, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.warn.enabled(level) then println(s"WARN $name -- [${frame.position.show}] $msg $t")

            inline def error(msg: => Text)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.error.enabled(level) then println(s"ERROR $name -- [${frame.position.show}] $msg")

            inline def error(msg: => Text, t: => Throwable)(
                using
                frame: Frame,
                allow: AllowUnsafe
            ): Unit = if Level.error.enabled(level) then println(s"ERROR $name -- [${frame.position.show}] $msg $t")
        end ConsoleLogger
    end Unsafe

    private inline def logWhen(inline level: Level)(inline doLog: Log => Unit < Sync)(using
        inline frame: Frame
    ): Unit < Sync =
        Sync.Unsafe.withLocal(local) { log =>
            if level.enabled(log.level) then
                doLog(log)
            else
                (
            )
        }

    /** Logs a trace message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def trace(inline msg: => Text)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.trace)(_.trace(msg))

    /** Logs a trace message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def trace(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.trace)(_.trace(msg, t))

    /** Logs a debug message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def debug(inline msg: => Text)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.debug)(_.debug(msg))

    /** Logs a debug message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def debug(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.debug)(_.debug(msg, t))

    /** Logs an info message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def info(inline msg: => Text)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.info)(_.info(msg))

    /** Logs an info message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def info(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.info)(_.info(msg, t))

    /** Logs a warning message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def warn(inline msg: => Text)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.warn)(_.warn(msg))

    /** Logs a warning message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def warn(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.warn)(_.warn(msg, t))

    /** Logs an error message.
      *
      * @param msg
      *   The message to log
      * @return
      *   An Sync effect that logs the message
      */
    inline def error(inline msg: => Text)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.error)(_.error(msg))

    /** Logs an error message with an exception.
      *
      * @param msg
      *   The message to log
      * @param t
      *   The exception to log
      * @return
      *   An Sync effect that logs the message and exception
      */
    inline def error(inline msg: => Text, inline t: => Throwable)(using inline frame: Frame): Unit < Sync =
        logWhen(Level.error)(_.error(msg, t))

end Log
