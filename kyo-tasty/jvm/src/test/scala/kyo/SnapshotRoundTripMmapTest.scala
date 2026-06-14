package kyo

import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotReader
import kyo.internal.tasty.snapshot.SnapshotWriter

/** Writes a snapshot to a real temp file and reads it back via FileChannel.map, exercising the mmap reader path
  * (PlatformMmapReader.readMapped) end-to-end.
  *
  * Requires JVM: mmap is JVM-specific (FileChannel.map).
  */
class SnapshotRoundTripMmapTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val plainClassPickle =
        Tasty.Pickle("plain-class", Tasty.Version(28, 3, 0), Span.from(kyo.fixtures.Embedded.plainClassTasty))

    "mmap-loaded snapshot has same fully-qualified name set as cold-loaded classpath" in {
        val digest = Array[Byte](0x50, 0x51, 0x52, 0x53, 0x54, 0x55, 0x56, 0x57)
        val tmpDir = java.io.File.createTempFile("kyo-tasty-mmap-test", "").getAbsolutePath
        val _      = new java.io.File(tmpDir).delete()
        val _      = new java.io.File(tmpDir).mkdirs()

        Abort.run[TastyError](
            Tasty.withPickles(Chunk(plainClassPickle)) {
                Tasty.classpath.map { origCp =>
                    val origClasses = origCp.topLevelClasses
                    SnapshotWriter.write(origCp, tmpDir, digest).andThen {
                        val hex      = DigestComputer.toHexString(digest)
                        val snapPath = s"$tmpDir/$hex.krfl"
                        SnapshotReader.readMapped(snapPath).map { warmCp =>
                            val warmClasses = warmCp.topLevelClasses
                            (
                                origClasses.map(_.name.asString).toSet,
                                warmClasses.map(_.name.asString).toSet
                            )
                        }
                    }
                }
            }
        ).map {
            case Result.Success((origFullNames: Set[String] @unchecked, warmFullNames: Set[String] @unchecked)) =>
                assert(
                    origFullNames == warmFullNames,
                    s"mmap-loaded fully-qualified names must match cold-loaded fully-qualified names: cold=$origFullNames mmap=$warmFullNames"
                )
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
        }
    }

end SnapshotRoundTripMmapTest
