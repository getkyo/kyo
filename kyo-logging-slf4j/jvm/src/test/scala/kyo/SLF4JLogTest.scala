package kyo

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.Context
import ch.qos.logback.core.OutputStreamAppender
import ch.qos.logback.core.encoder.Encoder
import ch.qos.logback.core.status.Status
import org.slf4j.LoggerFactory
import scala.util.control.NoStackTrace

class SLF4JLogTest extends kyo.test.Test[Any]:

    // Every leaf reconfigures the SAME global Logback logger named "kyo.logging" (and the "log" leaf also
    // detaches the ROOT logger's appenders), then reads the level back through the SLF4J wrapper.
    // Leaves run in parallel by default; concurrent leaves clobber each other's level and appenders.
    // Serialize this suite's leaves to eliminate the race.
    override def config = super.config.sequential

    case object ex extends NoStackTrace

    private def loggerWithLevel(level: Level) =
        val logger = LoggerFactory.getLogger("kyo.logging")
        logger.asInstanceOf[Logger].setLevel(level)
        new SLF4JLog.Unsafe.SLF4J(logger)
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
        assert(loggerWithLevel(Level.WARN).level == Log.Level.warn)
    }

    "error" in {
        assert(loggerWithLevel(Level.ERROR).level == Log.Level.error)
    }

    "silent" in {
        assert(loggerWithLevel(Level.OFF).level == Log.Level.silent)
    }

    "SLF4J level reflects runtime reconfiguration" in {
        // Construct the wrapper once at INFO level; hold the same instance across the flip.
        // If .level were a val, it would capture the construction-time snapshot and the
        // post-setLevel assertion below would still return Log.Level.info (the stale value),
        // causing the test to fail. With def, each read re-queries the underlying logger.
        val logger = LoggerFactory.getLogger("kyo.logging")
        logger.asInstanceOf[Logger].setLevel(Level.INFO)
        val wrapper = new SLF4JLog.Unsafe.SLF4J(logger)

        // Before flip: level reads INFO (construction-time level).
        assert(wrapper.level == Log.Level.info)

        // Flip the underlying Logback logger to DEBUG. Do NOT reconstruct wrapper.
        // Logback setLevel writes a volatile field; isDebugEnabled() reads it immediately.
        logger.asInstanceOf[Logger].setLevel(Level.DEBUG)

        // After flip: same wrapper instance must now report DEBUG, not the stale INFO.
        assert(wrapper.level == Log.Level.debug)

        // Flip to ERROR (strictest non-silent level). The Log tier gate reads unsafe.level
        // on each call, so it must now reject info calls. Verify via the gate predicate.
        logger.asInstanceOf[Logger].setLevel(Level.ERROR)
        assert(wrapper.level == Log.Level.error)

        // Gate coupling: info is below ERROR threshold, so Log.Level.info.enabled(wrapper.level)
        // must be false. This is the predicate the Log case class evaluates before dispatching.
        assert(!Log.Level.info.enabled(wrapper.level))
    }

    "log" in {
        val buffer = new StringBuilder()
        val out = new java.io.OutputStream:
            def write(b: Int): Unit = buffer.append(b.toChar)

        LoggerFactory.getLogger("ROOT").asInstanceOf[Logger].detachAndStopAllAppenders()
        val logger = LoggerFactory.getLogger("kyo.logging").asInstanceOf[Logger]
        logger.setLevel(Level.DEBUG)

        val encoder = new PatternLayoutEncoder
        encoder.setPattern("%level %logger %msg%n");
        encoder.setContext(logger.getLoggerContext)
        encoder.start()

        val appender = new OutputStreamAppender[ILoggingEvent]
        appender.setEncoder(encoder)
        appender.setOutputStream(out)
        appender.setImmediateFlush(true)
        appender.setContext(logger.getLoggerContext)
        appender.start()
        logger.addAppender(appender)

        val text: String = "info message - hidden"
        Log.let(SLF4JLog("kyo.logging")) {
            for
                _ <- Log.trace("won't show up")
                _ <- Log.debug("test message")
                _ <- Log.info(text.dropRight(9))
                _ <- Log.warn("warning", ex)
                _ <- Log.flush
            yield
                appender.stop()
                val logs = buffer.toString.trim.split("\\r?\\n")
                assert(logs.length == 4)
                assert(logs(0).matches("DEBUG kyo.logging \\[.*\\] test message"))
                assert(logs(1).matches("INFO kyo.logging \\[.*\\] info message"))
                assert(logs(2).matches("WARN kyo.logging \\[.*\\] warning"))
                assert(logs(3) == "kyo.SLF4JLogTest$ex$")
            end for
        }
    }
end SLF4JLogTest
