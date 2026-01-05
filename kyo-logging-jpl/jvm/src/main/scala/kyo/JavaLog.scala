package kyo

import java.lang.System.Logger
import java.lang.System.Logger.Level
import kyo.AllowUnsafe
import kyo.Frame
import kyo.Log
import kyo.Text

object JavaLog:

    def apply(name: String): Log = Log(new Unsafe.JPL(java.lang.System.getLogger(name)))

    def apply(logger: Logger): Log = Log(new Unsafe.JPL(logger))

    /** WARNING: Low-level API meant for integrations, libraries, and performance-sensitive code. See AllowUnsafe for more details. */
    object Unsafe:

        final class JPL(logger: Logger) extends Log.Unsafe:

            val level: Log.Level =
                if logger.isLoggable(Level.TRACE) then Log.Level.trace
                else if logger.isLoggable(Level.DEBUG) then Log.Level.debug
                else if logger.isLoggable(Level.INFO) then Log.Level.info
                else if logger.isLoggable(Level.WARNING) then Log.Level.warn
                else if logger.isLoggable(Level.ERROR) then Log.Level.error
                else Log.Level.silent

            inline def trace(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.TRACE, s"[${frame.position.show}] $msg")

            inline def trace(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.TRACE, s"[${frame.position.show}] $msg", t)

            inline def debug(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.DEBUG, s"[${frame.position.show}] $msg")

            inline def debug(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.DEBUG, s"[${frame.position.show}] $msg", t)

            inline def info(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.INFO, s"[${frame.position.show}] $msg")

            inline def info(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.INFO, s"[${frame.position.show}] $msg", t)

            inline def warn(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.WARNING, s"[${frame.position.show}] $msg")

            inline def warn(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.WARNING, s"[${frame.position.show}] $msg", t)

            inline def error(msg: => Text)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.ERROR, s"[${frame.position.show}] $msg")

            inline def error(msg: => Text, t: => Throwable)(using frame: Frame, allow: AllowUnsafe): Unit =
                logger.log(Level.ERROR, s"[${frame.position.show}] $msg", t)

        end JPL
    end Unsafe
end JavaLog
