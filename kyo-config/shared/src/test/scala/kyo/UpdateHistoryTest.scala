package kyo

import AllowUnsafe.embrace.danger
import org.scalatest.freespec.AnyFreeSpec

class UpdateHistoryTest extends AnyFreeSpec {

    "DynamicFlag update history" - {

        "history records update entry" in {
            val flag = HistoryTestFlags.recordEntry
            flag.update("new@expr")
            val hist = flag.updateHistory
            assert(hist.size == 1)
            assert(hist.head.newExpression == "new@expr")
            assert(hist.head.previousExpression == "")
        }

        "history preserves order — newest first" in {
            val flag = HistoryTestFlags.orderTest
            flag.update("first")
            flag.update("second")
            flag.update("third")
            val hist = flag.updateHistory
            assert(hist.size == 3)
            assert(hist(0).newExpression == "third")
            assert(hist(1).newExpression == "second")
            assert(hist(2).newExpression == "first")
        }

        "history bounded at maxHistory (10)" in {
            val flag = HistoryTestFlags.bounded
            for (i <- 1 to 15) {
                flag.update(s"expr-$i")
            }
            val hist = flag.updateHistory
            assert(hist.size == 10)
            // Newest first
            assert(hist.head.newExpression == "expr-15")
            // Oldest still present
            assert(hist.last.newExpression == "expr-6")
        }

        "history includes timestamp" in {
            val before = java.lang.System.currentTimeMillis()
            val flag   = HistoryTestFlags.timestamp
            flag.update("new@expr")
            val after = java.lang.System.currentTimeMillis()
            val hist  = flag.updateHistory
            assert(hist.head.timestamp >= before)
            assert(hist.head.timestamp <= after)
        }

        "history includes previous expression" in {
            val flag = HistoryTestFlags.prevExpr
            flag.update("first@x")
            flag.update("second@y")
            val hist = flag.updateHistory
            assert(hist(0).previousExpression == "first@x")
            assert(hist(0).newExpression == "second@y")
            assert(hist(1).previousExpression == "")
            assert(hist(1).newExpression == "first@x")
        }

        "history survives across multiple updates" in {
            val flag = HistoryTestFlags.survives
            flag.update("a")
            flag.update("b")
            flag.update("c")
            val hist = flag.updateHistory
            assert(hist.size == 3)
            // All 3 entries present
            assert(hist.map(_.newExpression) == List("c", "b", "a"))
        }

        "history is empty initially" in {
            val flag = HistoryTestFlags.emptyInit
            assert(flag.updateHistory == Nil)
        }

        "history not added on failed update" in {
            val flag = HistoryTestFlags.failedUpdate
            flag.update("100@enterprise")
            assert(flag.updateHistory.size == 1)
            try flag.update("notanumber")
            catch { case _: FlagException => () }
            // History should not have grown
            assert(flag.updateHistory.size == 1)
        }
    }

}

object HistoryTestFlags {
    object recordEntry  extends DynamicFlag[String]("default")
    object orderTest    extends DynamicFlag[String]("default")
    object bounded      extends DynamicFlag[String]("default")
    object timestamp    extends DynamicFlag[String]("default")
    object prevExpr     extends DynamicFlag[String]("default")
    object survives     extends DynamicFlag[String]("default")
    object emptyInit    extends DynamicFlag[String]("default")
    object failedUpdate extends DynamicFlag[Int](0)
}
