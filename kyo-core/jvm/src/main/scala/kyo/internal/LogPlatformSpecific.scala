package kyo.internal

import kyo.AllowUnsafe
import kyo.Frame
import kyo.Log
import kyo.Log.Level

trait LogPlatformSpecific:
    val live: Log = Log(LogPlatformSpecific.Unsafe.SLF4J("kyo.logs"))

object LogPlatformSpecific:

    /* WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        object SLF4J:
            def apply(name: String) = new SLF4J(org.slf4j.LoggerFactory.getLogger(name))

        final class SLF4J(logger: org.slf4j.Logger) extends Log.Unsafe:
            val level =
                if logger.isTraceEnabled() then Level.trace
                else if logger.isDebugEnabled() then Level.debug
                else if logger.isInfoEnabled() then Level.info
                else if logger.isWarnEnabled() then Level.warn
                else if logger.isErrorEnabled() then Level.error
                else Level.silent

            inline def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.trace.enabled(level) then logger.trace(s"[${frame.position.show}] $msg")

            inline def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.trace.enabled(level) then logger.trace(s"[${frame.position.show}] $msg", t)

            inline def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.debug.enabled(level) then logger.debug(s"[${frame.position.show}] $msg")

            inline def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.debug.enabled(level) then logger.debug(s"[${frame.position.show}] $msg", t)

            inline def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.info.enabled(level) then logger.info(s"[${frame.position.show}] $msg")

            inline def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.info.enabled(level) then logger.info(s"[${frame.position.show}] $msg", t)

            inline def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.warn.enabled(level) then logger.warn(s"[${frame.position.show}] $msg")

            inline def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.warn.enabled(level) then logger.warn(s"[${frame.position.show}] $msg", t)

            inline def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.error.enabled(level) then logger.error(s"[${frame.position.show}] $msg")

            inline def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.error.enabled(level) then logger.error(s"[${frame.position.show}] $msg", t)
        end SLF4J
    end Unsafe
end LogPlatformSpecific
