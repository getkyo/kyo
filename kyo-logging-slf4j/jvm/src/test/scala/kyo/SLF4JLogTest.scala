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
import kyo.Log
import kyo.Test
import kyo.Text
import org.slf4j.LoggerFactory
import scala.util.control.NoStackTrace

class SLF4JLogTest extends Test:

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

    "log" in run {
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

        val text: Text = "info message - hidden"
        Log.withLogger(SLF4JLog("kyo.logging")) {
            for
                _ <- Log.trace("won't show up")
                _ <- Log.debug("test message")
                _ <- Log.info(text.dropRight(9))
                _ <- Log.warn("warning", ex)
            yield
                appender.stop()
                val logs = buffer.toString.trim.split('\n')
                assert(logs.length == 4)
                assert(logs(0).matches("DEBUG kyo.logging \\[.*\\] test message"))
                assert(logs(1).matches("INFO kyo.logging \\[.*\\] info message"))
                assert(logs(2).matches("WARN kyo.logging \\[.*\\] warning"))
                assert(logs(3).matches("kyo.SLF4JLogTest\\$ex\\$: null"))
            end for
        }
    }
end SLF4JLogTest
