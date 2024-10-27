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

        class SLF4J(logger: org.slf4j.Logger) extends Log.Unsafe:
            def level =
                if logger.isTraceEnabled() then Level.Trace
                else if logger.isDebugEnabled() then Level.Debug
                else if logger.isInfoEnabled() then Level.Info
                else if logger.isWarnEnabled() then Level.Warn
                else Level.Error

            inline def trace(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Trace.enabled(level) then logger.trace(s"[${frame.parse.position}] $msg")

            inline def trace(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Trace.enabled(level) then logger.trace(s"[${frame.parse.position}] $msg", t)

            inline def debug(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Debug.enabled(level) then logger.debug(s"[${frame.parse.position}] $msg")

            inline def debug(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Debug.enabled(level) then logger.debug(s"[${frame.parse.position}] $msg", t)

            inline def info(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Info.enabled(level) then logger.info(s"[${frame.parse.position}] $msg")

            inline def info(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Info.enabled(level) then logger.info(s"[${frame.parse.position}] $msg", t)

            inline def warn(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Warn.enabled(level) then logger.warn(s"[${frame.parse.position}] $msg")

            inline def warn(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Warn.enabled(level) then logger.warn(s"[${frame.parse.position}] $msg", t)

            inline def error(msg: => String)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Error.enabled(level) then logger.error(s"[${frame.parse.position}] $msg")

            inline def error(msg: => String, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                if Level.Error.enabled(level) then logger.error(s"[${frame.parse.position}] $msg", t)
        end SLF4J
    end Unsafe
end LogPlatformSpecific
