package kyo.logging

import System.Logger.Level
import java.util.logging.Level as jul
import java.util.logging.Logger
import java.util.logging.SimpleFormatter
import java.util.logging.StreamHandler
import kyo.Log
import kyo.Test
import kyo.Text
import scala.util.control.NoStackTrace

class LogPlatformSpecificTest extends Test:

    case object ex extends NoStackTrace

    given CanEqual[Level, Level] = CanEqual.derived

    /** @see [[System.Logger.Level]] */
    private def toJUL(level: Level) = level match
        case Level.ALL     => jul.ALL
        case Level.TRACE   => jul.FINER
        case Level.DEBUG   => jul.FINE
        case Level.INFO    => jul.INFO
        case Level.WARNING => jul.WARNING
        case Level.ERROR   => jul.SEVERE
        case Level.OFF     => jul.OFF

    private def loggerWithLevel(level: Level) =
        val logger = System.getLogger("kyo.logging")
        Logger.getLogger("kyo.logging").setLevel(toJUL(level))
        new LogPlatformSpecific.Unsafe.JPL(logger)
    end loggerWithLevel

    "trace" in {
        assert(loggerWithLevel(Level.TRACE).level == Log.Level.trace)
    }

    "debug" in {
        assert(loggerWithLevel(Level.DEBUG).level == Log.Level.debug)
    }

    "info" in {
        assert(loggerWithLevel(Level.INFO).level == Log.Level.info)
    }

    "warn" in {
        assert(loggerWithLevel(Level.WARNING).level == Log.Level.warn)
    }

    "error" in {
        assert(loggerWithLevel(Level.ERROR).level == Log.Level.error)
    }

    "silent" in {
        assert(loggerWithLevel(Level.OFF).level == Log.Level.silent)
    }

    "log" in run {
        val buffer = new StringBuilder()
        val out = new java.io.OutputStream:
            def write(b: Int): Unit = buffer.append(b.toChar)

        // Remove root console handler as it pollutes System.err
        val root = Logger.getLogger("")
        root.getHandlers.foreach(root.removeHandler)

        val logger  = Logger.getLogger("kyo.logging")
        val handler = new StreamHandler(out, new SimpleFormatter)
        handler.setLevel(toJUL(Level.DEBUG)) // logger.setLevel isn't enough
        logger.addHandler(handler)
        val text: Text = "info message - hidden"
        Log.withLogger(Log(loggerWithLevel(Level.DEBUG))) {
            for
                _ <- Log.trace("won't show up")
                _ <- Log.debug("test message")
                _ <- Log.info(text.dropRight(9))
                _ <- Log.warn("warning", ex)
            yield
                handler.close()
                val logs = buffer.toString.trim.split('\n')
                assert(logs.length == 7)
                assert(logs(1).matches("FINE: \\[.*\\] test message"))
                assert(logs(3).matches("INFO: \\[.*\\] info message"))
                assert(logs(5).matches("WARNING: \\[.*\\] warning"))
                assert(logs(6).matches("kyo.logging.LogPlatformSpecificTest\\$ex\\$"))
            end for
        }
    }
end LogPlatformSpecificTest
