package kyo.test.snapshot

import kyo.Chunk
import kyo.internal.Platform
import kyo.internal.PlatformJs
import kyo.test.internal.TestContext
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite
import scala.scalajs.js as sjs

/** The default `snapshotUpdateMode` on Scala.js, where `java.lang.System.getenv` always returns `null`: `KYO_TEST_SNAPSHOT=update` comes
  * from `process.env` on Node. A page has no `process`, so the leaf cancels on the browser rows.
  */
class SnapshotUpdateModeJsWasmTest extends AnyFunSuite with NonImplicitAssertions:

    private class DefaultMode extends SnapshotTest[Any]:
        def updateModeValue: Boolean = snapshotUpdateMode

    test("KYO_TEST_SNAPSHOT=update in process.env turns update mode on") {
        assume(Platform.isNodeLike, "sets a variable in Node's process.env, which a page has not")
        val env = PlatformJs.jsGlobal("process").get.env
        TestContext.setForInstantiation(new TestContext(Chunk.empty))
        val suite = new DefaultMode
        assert(!suite.updateModeValue, "update mode should be off while KYO_TEST_SNAPSHOT is unset")
        env.updateDynamic("KYO_TEST_SNAPSHOT")("update")
        try assert(suite.updateModeValue, "update mode should be on with KYO_TEST_SNAPSHOT=update in process.env")
        finally sjs.special.delete(env, "KYO_TEST_SNAPSHOT"): Unit
    }

end SnapshotUpdateModeJsWasmTest
