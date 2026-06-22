package kyo

import kyo.Log.Level
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/** Integration between Kyo's `Log` effect and SLF4J.
  *
  * Wraps any `org.slf4j.Logger` as a `Log` instance, routing each log level through
  * the corresponding SLF4J method. Level gating delegates to the underlying SLF4J
  * implementation, so runtime reconfiguration (e.g., Logback's `setLevel`) is reflected
  * immediately on each call without restarting the application.
  *
  * Obtain a logger by name or by supplying an existing SLF4J `Logger`:
  * {{{
  *   val log: Log = SLF4JLog("com.example.MyService")
  *   val log2: Log = SLF4JLog(LoggerFactory.getLogger(classOf[MyService]))
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
object SLF4JLog:

    def apply(name: String): Log = Log(new Unsafe.SLF4J(LoggerFactory.getLogger(name)))

    def apply(logger: Logger): Log = Log(new Unsafe.SLF4J(logger))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        final class SLF4J(logger: Logger) extends Log.Unsafe:
            def name: String                       = logger.getName
            def withName(name: String): Log.Unsafe = new SLF4J(LoggerFactory.getLogger(name))

            def level: Log.Level =
                if logger.isTraceEnabled() then Level.trace
                else if logger.isDebugEnabled() then Level.debug
                else if logger.isInfoEnabled() then Level.info
                else if logger.isWarnEnabled() then Level.warn
                else if logger.isErrorEnabled() then Level.error
                else Level.silent

            inline def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.trace(s"[${frame.position.show}] $msg")

            inline def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.trace(s"[${frame.position.show}] $msg", t)

            inline def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.debug(s"[${frame.position.show}] $msg")

            inline def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.debug(s"[${frame.position.show}] $msg", t)

            inline def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.info(s"[${frame.position.show}] $msg")

            inline def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.info(s"[${frame.position.show}] $msg", t)

            inline def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.warn(s"[${frame.position.show}] $msg")

            inline def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.warn(s"[${frame.position.show}] $msg", t)

            inline def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.error(s"[${frame.position.show}] $msg")

            inline def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.error(s"[${frame.position.show}] $msg", t)
        end SLF4J
    end Unsafe
end SLF4JLog
