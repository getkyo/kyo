package kyo

import kyo.Log.Level
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object SLF4JLog:

    def apply(name: String): Log = Log(new Unsafe.SLF4J(LoggerFactory.getLogger(name)))

    def apply(logger: Logger): Log = Log(new Unsafe.SLF4J(logger))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        final class SLF4J(logger: Logger) extends Log.Unsafe:
            val level =
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
