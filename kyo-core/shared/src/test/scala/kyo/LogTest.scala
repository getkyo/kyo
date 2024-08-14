package kyo

import scala.util.control.NoStackTrace

class LogTest extends Test:

    case object ex extends NoStackTrace

    "log" in run {
        for
            _ <- Log.trace("trace")
            _ <- Log.debug("debug")
            _ <- Log.info("info")
            _ <- Log.warn("warn")
            _ <- Log.error("error")
            _ <- Log.trace("trace", ex)
            _ <- Log.debug("debug", ex)
            _ <- Log.info("info", ex)
            _ <- Log.warn("warn", ex)
            _ <- Log.error("error", ex)
        yield succeed
        end for
    }

    "unsafe" in {
        Log.unsafe.trace("trace")
        Log.unsafe.debug("debug")
        Log.unsafe.info("info")
        Log.unsafe.warn("warn")
        Log.unsafe.error("error")
        Log.unsafe.trace("trace", ex)
        Log.unsafe.debug("debug", ex)
        Log.unsafe.info("info", ex)
        Log.unsafe.warn("warn", ex)
        Log.unsafe.error("error", ex)
        succeed
    }
end LogTest
