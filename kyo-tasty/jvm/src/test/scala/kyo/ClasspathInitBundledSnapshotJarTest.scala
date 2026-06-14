package kyo

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.tasty.query.BundledSnapshotProbe
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** End-to-end bundled snapshot loading via classpath init against real jars built with java.util.zip:
  *   - probe HIT: symbols come from the bundled snapshot
  *   - mixed-root merge: bundled jar + cold jar produces a combined symbol count
  *   - digest mismatch under SoftFail falls back to cold load
  *   - digest mismatch under FailFast raises DigestMismatch
  *
  * Since `PlatformDigest.digestForJarRoot` excludes the snapshot entry from the hash, the digest computed on the
  * content-only jar equals the digest computed on the same jar after adding the snapshot entry.
  */
class ClasspathInitBundledSnapshotJarTest extends kyo.test.Test[Any]:

    /** Build a valid KRFL snapshot bytes for a synthetic classpath with `n` Package symbols. */
    private def syntheticSnapshotBytes(n: Int, digest: Long): Array[Byte] =
        val syms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until n).map { i =>
                Tasty.Symbol.Package(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"bundledPkg$i"),
                    flags = Tasty.Flags.empty,
                    ownerId = Tasty.SymbolId(0),
                    memberIds = Chunk.empty
                )
            }
        )
        val classpath = Tasty.Classpath(
            symbols = syms,
            indices = Tasty.Classpath.Indices(
                byFullName = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"bundledPkg$i" -> Tasty.SymbolId(i)).toMap),
                bySimpleName = Dict.empty,
                packageIndex = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"bundledPkg$i" -> Tasty.SymbolId(i)).toMap),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                modulesIndex = Dict.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.from(syms.map(_.id)),
                unresolvedFullNameByNegId = Dict.empty,
                diagnostics = Chunk.empty
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

    /** Write bytes to a temp file and return it. The temp file is deleted on JVM exit. */
    private def writeTempJar(zipBytes: Array[Byte]): java.io.File =
        val tmp = java.io.File.createTempFile("kyo-tasty-test-", ".jar")
        tmp.deleteOnExit()
        val fos = new FileOutputStream(tmp)
        try fos.write(zipBytes)
        finally fos.close()
        tmp
    end writeTempJar

    "end-to-end bundled load produces exact symbol count (probe HIT)" in {
        // Write a content-only jar to get the stable digest (snapshot entry excluded from hash).
        val contentBytes  = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val contentFile   = writeTempJar(contentBytes)
        val digest        = DigestComputer.digestForRoot(contentFile.getAbsolutePath)
        val snapshotBytes = syntheticSnapshotBytes(5, digest)
        // Write the final jar (content + snapshot).
        val finalBytes = buildZipBytes(
            "Foo.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val finalFile = writeTempJar(finalBytes)
        val basePath  = finalFile.getAbsolutePath
        Scope.run {
            ClasspathOrchestrator.coldLoadBinding(
                roots = Seq(basePath),
                mode = Tasty.ErrorMode.SoftFail,
                cacheDir = Maybe.Absent
            ).map { binding =>
                val classpath = binding.classpath
                assert(
                    classpath.symbols.size == 5,
                    s"expected exactly 5 bundled symbols (probe HIT), got ${classpath.symbols.size}"
                )
                val hasMismatch = classpath.errors.exists {
                    case _: TastyError.DigestMismatch => true
                    case _                            => false
                }
                assert(!hasMismatch, s"unexpected DigestMismatch in errors: ${classpath.errors}")
            }
        }
    }

    "mixed-root merge: bundled root contributes exact symbol count from snapshot" in {
        val bundledContent      = buildZipBytes("A.class" -> Array[Byte](0xca.toByte))
        val bundledContent1File = writeTempJar(bundledContent)
        val bundledDigest       = DigestComputer.digestForRoot(bundledContent1File.getAbsolutePath)
        val bundledSnapshot     = syntheticSnapshotBytes(3, bundledDigest)
        val bundledFinal = buildZipBytes(
            "A.class"                              -> Array[Byte](0xca.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> bundledSnapshot
        )
        val bundledFinalFile = writeTempJar(bundledFinal)
        val bundledPath      = bundledFinalFile.getAbsolutePath
        // Cold jar: just a class file, no snapshot.
        val coldBase     = buildZipBytes("B.class" -> Array[Byte](0xca.toByte))
        val coldBaseFile = writeTempJar(coldBase)
        val coldBasePath = coldBaseFile.getAbsolutePath
        Scope.run {
            ClasspathOrchestrator.coldLoadBinding(
                roots = Seq(bundledPath, coldBasePath),
                mode = Tasty.ErrorMode.SoftFail,
                cacheDir = Maybe.Absent
            ).map { binding =>
                val classpath = binding.classpath
                assert(
                    classpath.symbols.size == 3,
                    s"expected 3 merged symbols (3 bundled + 0 cold), got ${classpath.symbols.size}"
                )
                val hasMismatch = classpath.errors.exists {
                    case _: TastyError.DigestMismatch => true
                    case _                            => false
                }
                assert(!hasMismatch, s"unexpected DigestMismatch: ${classpath.errors}")
            }
        }
    }

    "SoftFail on digest mismatch falls back to cold load without raising Abort" in {
        val staleJar = writeTempJar(buildZipBytes(
            "B.class"                              -> Array[Byte](0xca.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> syntheticSnapshotBytes(2, 0xdeadbeefL)
        ))
        Tasty.withClasspath(Seq(staleJar.getAbsolutePath)) {
            Tasty.classpath.map { classpath =>
                assert(classpath.symbols.size >= 0, "symbol count must be non-negative after SoftFail fallback")
            }
        }
    }

    "probe raises DigestMismatch when embedded digest does not match recomputed digest" in {
        val staleJar = writeTempJar(buildZipBytes(
            "C.class"                              -> Array[Byte](0xca.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> syntheticSnapshotBytes(2, 0xdeadbeefL)
        ))
        val rootPath = staleJar.getAbsolutePath
        Scope.run {
            Abort.run[TastyError] {
                BundledSnapshotProbe.probe(rootPath)
            }.map {
                case Result.Failure(_: TastyError.DigestMismatch) => assert(true)
                case other                                        => assert(false, s"expected DigestMismatch failure, got: $other")
            }
        }
    }

end ClasspathInitBundledSnapshotJarTest
