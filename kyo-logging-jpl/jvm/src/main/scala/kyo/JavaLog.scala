package kyo

import java.lang.System.Logger
import java.lang.System.Logger.Level

/** Integration between Kyo's `Log` effect and the Java Platform Logger (JPL) API.
  *
  * Wraps any `java.lang.System.Logger` as a `Log` instance, routing each log level
  * through the corresponding JPL method. Level gating delegates to the underlying JPL
  * implementation, so runtime reconfiguration (e.g., via `java.util.logging.Logger.setLevel`)
  * is reflected immediately on each call without restarting the application.
  *
  * Obtain a logger by name or by supplying an existing JPL `Logger`:
  * {{{
  *   val log: Log = JavaLog("com.example.MyService")
  *   val log2: Log = JavaLog(System.getLogger("com.example.MyService"))
  * }}}
  *
  * Bind it for a computation scope with `Log.let`:
  * {{{
  *   Log.let(log)(myEffect)
  * }}}
  *
  * Each dispatched message carries the source call-site position in brackets before the
  * message text, formatted as `[file:line]`.
  */
object JavaLog:

    def apply(name: String): Log = Log(new Unsafe.JPL(java.lang.System.getLogger(name)))

    def apply(logger: Logger): Log = Log(new Unsafe.JPL(logger))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        final class JPL(logger: Logger) extends Log.Unsafe:

            def name: String                       = logger.getName
            def withName(name: String): Log.Unsafe = new JPL(java.lang.System.getLogger(name))

            def level: Log.Level =
                if logger.isLoggable(Level.TRACE) then Log.Level.trace
                else if logger.isLoggable(Level.DEBUG) then Log.Level.debug
                else if logger.isLoggable(Level.INFO) then Log.Level.info
                else if logger.isLoggable(Level.WARNING) then Log.Level.warn
                else if logger.isLoggable(Level.ERROR) then Log.Level.error
                else Log.Level.silent

            inline def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.TRACE, s"[${frame.position.show}] $msg")

            inline def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.TRACE, s"[${frame.position.show}] $msg", t)

            inline def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.DEBUG, s"[${frame.position.show}] $msg")

            inline def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.DEBUG, s"[${frame.position.show}] $msg", t)

            inline def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.INFO, s"[${frame.position.show}] $msg")

            inline def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.INFO, s"[${frame.position.show}] $msg", t)

            inline def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.WARNING, s"[${frame.position.show}] $msg")

            inline def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.WARNING, s"[${frame.position.show}] $msg", t)

            inline def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.ERROR, s"[${frame.position.show}] $msg")

            inline def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.ERROR, s"[${frame.position.show}] $msg", t)

        end JPL
    end Unsafe
end JavaLog
