package kyo

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.tasty.query.BundledSnapshotProbe
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Probe behavior against real jars built with java.util.zip:
  *   - jar with no snapshot entry returns Maybe.Absent
  *   - jar with valid snapshot and matching digest returns Maybe.Present
  *   - jar with stale digest raises TastyError.DigestMismatch
  *   - repeated calls return the same bytes
  *
  * Since `PlatformDigest.digestForJarRoot` excludes the snapshot entry from the digest, the digest of a jar is stable
  * whether or not the snapshot entry is present. This allows the test to write a jar with the snapshot entry already
  * embedded and verify that the probe round-trips correctly.
  */
class BundledSnapshotProbeJarTest extends kyo.test.Test[Any]:

    /** Build a valid KRFL snapshot bytes for a synthetic classpath with `n` Package symbols. */
    private def syntheticSnapshotBytes(n: Int, digest: Long): Array[Byte] =
        val syms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until n).map { i =>
                Tasty.Symbol.Package(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"pkg$i"),
                    flags = Tasty.Flags.empty,
                    ownerId = Tasty.SymbolId(0),
                    memberIds = Chunk.empty
                )
            }
        )
        val classpath = Tasty.Classpath(
            symbols = syms,
            indices = Tasty.Classpath.Indices(
                byFullName = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"pkg$i" -> Tasty.SymbolId(i)).toMap),
                bySimpleName = Dict.empty[String, Chunk[Tasty.SymbolId]],
                packageIndex = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"pkg$i" -> Tasty.SymbolId(i)).toMap),
                subclassIndex = Dict.empty[Tasty.SymbolId, Chunk[Tasty.SymbolId]],
                companionIndex = Dict.empty[Tasty.SymbolId, Tasty.SymbolId],
                modulesIndex = Dict.empty[String, Tasty.Java.Module.Descriptor],
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.from(syms.map(_.id)),
                unresolvedFullNameByNegId = Dict.empty[Tasty.SymbolId, String],
                diagnostics = Chunk.empty,
                bySourceFile = Dict.empty[String, Chunk[Tasty.SymbolId]]
            ),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val digestBytes = DigestComputer.longToBytes(digest)
        SnapshotWriter.serializeToBytes(classpath, digestBytes)
    end syntheticSnapshotBytes

    /** Build a zip as Array[Byte] containing the given entries. */
    private def buildZipBytes(entries: (String, Array[Byte])*): Array[Byte] =
        val baos = new ByteArrayOutputStream()
        val zos  = new ZipOutputStream(baos)
        for (name, bytes) <- entries do
            zos.putNextEntry(new ZipEntry(name))
            zos.write(bytes)
            zos.closeEntry()
        end for
        zos.close()
        baos.toByteArray
    end buildZipBytes

    /** Write zip bytes to a temp file and return the path. Temp file is deleted on JVM exit. */
    private def writeTempJar(zipBytes: Array[Byte]): String =
        val tmp = java.io.File.createTempFile("kyo-probe-", ".jar")
        tmp.deleteOnExit()
        val fos = new java.io.FileOutputStream(tmp)
        try fos.write(zipBytes)
        finally fos.close()
        tmp.getAbsolutePath
    end writeTempJar

    "jar with no snapshot entry returns Maybe.Absent" in {
        val noSnapBytes = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val jarPath     = writeTempJar(noSnapBytes)
        Scope.run {
            BundledSnapshotProbe.probe(jarPath).map { result =>
                assert(result == Maybe.Absent, s"expected Absent, got $result")
            }
        }
    }

    "jar with valid snapshot and matching digest returns Maybe.Present" in {
        // Content entries only (no snapshot) are used to compute the stable digest.
        val contentBytes = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        // Compute the digest as it will be computed at probe time: excludes snapshot entry.
        // Write a jar with the snapshot to get the digest from that jar (snapshot entry excluded).
        // Since PlatformDigest.digestForJarRoot excludes the snapshot entry, compute from the content jar first.
        val contentJarPath = writeTempJar(contentBytes)
        val digest         = DigestComputer.digestForRoot(contentJarPath)
        val snapshotBytes  = syntheticSnapshotBytes(3, digest)
        // Write final jar with both content and snapshot. The probe will recompute the digest
        // excluding the snapshot entry, giving the same value as above.
        val finalJarBytes = buildZipBytes(
            "Foo.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val finalJarPath = writeTempJar(finalJarBytes)
        Scope.run {
            BundledSnapshotProbe.probe(finalJarPath).map { result =>
                assert(result.isDefined, "expected Maybe.Present for valid snapshot")
                result match
                    case Maybe.Present(bytes) => assert(bytes.length > 0, "snapshot bytes must be non-empty")
                    case Maybe.Absent         => assert(false, "unreachable")
            }
        }
    }

    "digest mismatch raises TastyError.DigestMismatch" in {
        val staleDigest   = 0xdeadbeefL // intentionally wrong
        val snapshotBytes = syntheticSnapshotBytes(2, staleDigest)
        val jarBytes = buildZipBytes(
            "B.class"                              -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val jarPath = writeTempJar(jarBytes)
        Scope.run {
            Abort.run[TastyError] {
                BundledSnapshotProbe.probe(jarPath)
            }.map {
                case Result.Failure(e: TastyError.DigestMismatch) =>
                    assert(e.expected.nonEmpty, "expected hex must be non-empty")
                    assert(e.actual.nonEmpty, "actual hex must be non-empty")
                    assert(e.expected != e.actual, "expected and actual must differ for stale digest")
                case other =>
                    assert(false, s"expected DigestMismatch failure, got: $other")
            }
        }
    }

    "probe is idempotent; same bytes returned on two calls" in {
        val contentBytes  = buildZipBytes("Bar.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val contentPath   = writeTempJar(contentBytes)
        val digest        = DigestComputer.digestForRoot(contentPath)
        val snapshotBytes = syntheticSnapshotBytes(2, digest)
        val jarBytes = buildZipBytes(
            "Bar.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val jarPath = writeTempJar(jarBytes)
        Scope.run {
            BundledSnapshotProbe.probe(jarPath).map { r1 =>
                BundledSnapshotProbe.probe(jarPath).map { r2 =>
                    assert(r1.isDefined && r2.isDefined, "both probe calls must return Present")
                    (r1, r2) match
                        case (Maybe.Present(b1), Maybe.Present(b2)) =>
                            assert(
                                java.util.Arrays.equals(b1, b2),
                                s"probe must return byte-identical results on repeated calls; len1=${b1.length} len2=${b2.length}"
                            )
                        case _ => assert(false, "unreachable")
                    end match
                }
            }
        }
    }

end BundledSnapshotProbeJarTest
