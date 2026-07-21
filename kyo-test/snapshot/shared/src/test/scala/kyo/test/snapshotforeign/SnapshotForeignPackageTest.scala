package kyo.test.snapshotforeign

import kyo.Chunk
import kyo.Maybe
import kyo.Result
import kyo.Schema
import kyo.Yaml
import kyo.test.AssertScope
import kyo.test.internal.TestContext
import kyo.test.snapshot.SnapshotTest
import kyo.test.snapshot.internal.SnapshotStore
import org.scalatest.NonImplicitAssertions
import org.scalatest.funsuite.AnyFunSuite

/** Compile-and-run proof that `assertSchemaSnapshot`'s config-lambda form resolves from a package where `SnapshotConfig`'s
  * `private[snapshot] modify` accessor is genuinely inaccessible.
  *
  * Package `kyo.test.snapshotforeign` is a sibling of `kyo.test.snapshot`. The suite still sits under `kyo.test`, so the
  * test-internal `AssertScope` and `TestContext` constructors stay reachable here; the isolated axis under test is
  * `private[snapshot]` accessibility, proving the inline `assertSchemaSnapshot` body resolves its `normalizeWith` call
  * through a compiler-generated accessor rather than a direct cross-package field read.
  */
class SnapshotForeignPackageTest extends AnyFunSuite with NonImplicitAssertions:

    private given AssertScope = new AssertScope(Chunk.empty)

    private def tmpDir(): String =
        s"target/snap-foreign-test-${java.lang.System.nanoTime()}"

    private def installContexts(): Unit =
        TestContext.setForInstantiation(new TestContext(Chunk.empty))

    case class Rec(id: Int, ts: Long) derives Schema

    /** Minimal `SnapshotTest[Any]` subclass; the config-lambda call site lives here, in package `kyo.test.snapshotforeign`. */
    private class ForeignFixture(dir: String) extends SnapshotTest[Any]:
        override protected def snapshotDir: String         = dir
        override protected def snapshotUpdateMode: Boolean = true

        def storeRec(actual: Rec, name: String)(using AssertScope): Unit =
            assertSchemaSnapshot(actual, name, _.normalize(_.set(_.ts)(0L))): Unit

    end ForeignFixture

    test("a suite in a sibling package (outside kyo.test.snapshot) compiles and runs assertSchemaSnapshot with the config lambda") {
        val dir = tmpDir()
        installContexts()
        val fixture = new ForeignFixture(dir)
        fixture.storeRec(Rec(1, 999L), "rec")

        val path = s"$dir/ForeignFixture/rec.snap.yaml"
        SnapshotStore.read(path) match
            case Maybe.Present(stored) =>
                Schema[Rec].decodeString[Yaml](stored) match
                    case Result.Success(decoded) => assert(decoded.ts == 0L, s"Expected the stored ts scrubbed to 0, got: $decoded")
                    case other                   => fail(s"Expected a decodable Rec, got $other")
            case Maybe.Absent =>
                fail(s"expected the update-mode write to produce $path")
        end match
    }

end SnapshotForeignPackageTest
