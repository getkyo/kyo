package kyo.internal

import kyo.Log.Level
import kyo.Test
import org.slf4j.LoggerFactory

class LogPlatformSpecificTest extends Test:
    private def loggerWithLevel(level: ch.qos.logback.classic.Level) =
        val logger = LoggerFactory.getLogger("test")
        logger.asInstanceOf[ch.qos.logback.classic.Logger].setLevel(level)
        new LogPlatformSpecific.Unsafe.SLF4J(logger)
    end loggerWithLevel

    "SLF4J logger" - {
        "trace" in {
            assert(loggerWithLevel(ch.qos.logback.classic.Level.TRACE).level == Level.trace)
        }

        "debug" in {
            assert(loggerWithLevel(ch.qos.logback.classic.Level.DEBUG).level == Level.debug)
        }

        "info" in {
            assert(loggerWithLevel(ch.qos.logback.classic.Level.INFO).level == Level.info)
        }

        "warn" in {
            assert(loggerWithLevel(ch.qos.logback.classic.Level.WARN).level == Level.warn)
        }

        "error" in {
            assert(loggerWithLevel(ch.qos.logback.classic.Level.ERROR).level == Level.error)
        }

        "silent" in {
            assert(loggerWithLevel(ch.qos.logback.classic.Level.OFF).level == Level.silent)
        }
    }
end LogPlatformSpecificTest
