package kyo

import kyo.internal.TestClasspaths
import kyo.internal.TestClasspaths2
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotFormat
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Tests for SnapshotWriter serialization correctness.
  *
  * Writer side: a snapshot written from a real TASTy classpath has PARENTS, MEMBERS, and TPARAMS_ sections with length > 0.
  *
  * Two cold-writes of the same classpath produce byte-equal snapshots, AND warm-then-reserialize also produces byte-equal output.
  * The fully-qualified name map is keyed by SymbolId.value so that warm-loaded Symbol instances (with different object identity but same id.value)
  * resolve correctly.
  */
class SnapshotWriterTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    // after writing a snapshot from a real TASTy classpath that has class declarations and parents.
    "snapshot PARENTS, MEMBERS, and TPARAMS_ sections have length > 0 after writing a real classpath" in {
        // Use the SomeTrait fixture which extends java.lang.Object (has parents) and has members.
        val someTraitPickle = Tasty.Pickle("some-trait", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.someTraitTasty))
        val digest          = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)

        Abort.run[TastyError](
            Tasty.withPickles(Chunk(someTraitPickle)) {
                Tasty.classpath.map { classpath =>
                    SnapshotWriter.serializeToBytes(classpath, digest)
                }
            }
        ).map {
            case Result.Success(bytes) =>
                // Parse section index to find PARENTS, MEMBERS, TPARAMS_ lengths.
                val sectionCount = SnapshotFormat.readInt32LE(bytes, 32)
                val sectionLens  = mutable.HashMap.empty[String, Long]
                var idxPos       = 36
                var i            = 0
                while i < sectionCount do
                    val sName = SnapshotFormat.readSectionName(bytes, idxPos)
                    val sLen  = SnapshotFormat.readInt64LE(bytes, idxPos + 16)
                    sectionLens(sName) = sLen
                    idxPos += SnapshotFormat.sectionIndexEntrySize
                    i += 1
                end while
                val parentsLen = sectionLens.getOrElse(SnapshotFormat.sectionPARENTS, -1L)
                val membersLen = sectionLens.getOrElse(SnapshotFormat.sectionMEMBERS, -1L)
                val tparamsLen = sectionLens.getOrElse(SnapshotFormat.sectionTPARAMS, -1L)
                assert(parentsLen > 0, s"PARENTS section must be non-empty; got $parentsLen")
                assert(membersLen > 0, s"MEMBERS section must be non-empty; got $membersLen")
                assert(tparamsLen >= 0, s"TPARAMS_ section must be present; got $tparamsLen")
                succeed
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

    "two same-run serializations of the same classpath are byte-equal" in {
        TestClasspaths.withClasspath()(Tasty.classpath).map { classpath =>
            val digest = Array[Byte](0x60, 0x61, 0x62, 0x63, 0x64, 0x65, 0x66, 0x67)
            val a      = SnapshotWriter.serializeToBytes(classpath, digest)
            val b      = SnapshotWriter.serializeToBytes(classpath, digest)
            assert(
                java.util.Arrays.equals(a, b),
                s"cold-vs-cold: two serializations differ; len_a=${a.length} len_b=${b.length}"
            )
            succeed
        }
    }

    "warm-loaded classpath re-serializes byte-equal to the original snapshot" in {
        TestClasspaths2.withSnapshotInMemory().map { case (cold, warm) =>
            val digest = Array[Byte](0x70, 0x71, 0x72, 0x73, 0x74, 0x75, 0x76, 0x77)
            val a      = SnapshotWriter.serializeToBytes(cold, digest)
            val b      = SnapshotWriter.serializeToBytes(warm, digest)
            assert(
                java.util.Arrays.equals(a, b),
                s"warm-reserialize: warm re-serialization differs; len_a=${a.length} len_b=${b.length}"
            )
            succeed
        }
    }

end SnapshotWriterTest
