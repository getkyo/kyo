package kyo.test.snapshot

import kyo.test.AssertionFailed
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Tests for `SnapshotSchemaEvolution`: the distinct decode-failure factory. All assertions are synchronous plain value checks (no file
  * I/O); uses ScalaTest directly, mirroring `SnapshotDiffTest`.
  */
class SnapshotSchemaEvolutionTest extends AnyFunSuite with NonImplicitAssertions:

    test("the factory message is distinctly prefixed and never the mismatch prefix") {
        val failure: AssertionFailed = SnapshotSchemaEvolution("dir/name.snap.yaml", "boom")

        assert(
            failure.diagram == "SnapshotSchemaEvolution: stored snapshot at dir/name.snap.yaml could not be decoded: boom",
            s"Expected the distinct decode-failure diagram, got: ${failure.diagram}"
        )
        assert(!failure.getMessage.contains("Snapshot mismatch"), s"Expected no 'Snapshot mismatch' prefix, got: ${failure.getMessage}")
    }

end SnapshotSchemaEvolutionTest
