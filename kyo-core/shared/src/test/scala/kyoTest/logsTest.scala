package kyoTest

import kyo.*
import scala.util.control.NoStackTrace

class logsTest extends KyoTest:

    case object ex extends NoStackTrace

    "log" in IOs.run {
        for
            _ <- Logs.trace(1 + 1)
            _ <- Logs.trace("trace")
            _ <- Logs.debug(Seq(1, 2))
            _ <- Logs.debug("debug")
            _ <- Logs.info(Seq(1, 2), "a")
            _ <- Logs.info("info")
            _ <- Logs.warn(1, Seq(1, 2), "a")
            _ <- Logs.warn("warn")
            _ <- Logs.error()
            _ <- Logs.error("error")
            _ <- Logs.trace("trace", ex)
            _ <- Logs.debug("debug", ex)
            _ <- Logs.info("info", ex)
            _ <- Logs.warn("warn", ex)
            _ <- Logs.error("error", ex)
        yield succeed
        end for
    }

    "unsafe" in {
        Logs.unsafe.trace(42)
        Logs.unsafe.trace("trace")
        Logs.unsafe.debug(Seq(1), 1)
        Logs.unsafe.debug("debug")
        Logs.unsafe.info()
        Logs.unsafe.info("info")
        Logs.unsafe.warn(Seq(1), 1, 3, "a")
        Logs.unsafe.warn("warn")
        Logs.unsafe.error(Seq(1), 1, "a")
        Logs.unsafe.error("error")
        Logs.unsafe.trace(1 + 1, 2 + 2)
        Logs.unsafe.trace("trace", ex)
        Logs.unsafe.debug("debug", ex)
        Logs.unsafe.info("info", ex)
        Logs.unsafe.warn("warn", ex)
        Logs.unsafe.error("error", ex)
        succeed
    }
end logsTest
