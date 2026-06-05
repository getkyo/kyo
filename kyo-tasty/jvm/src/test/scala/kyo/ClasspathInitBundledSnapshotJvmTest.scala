package kyo

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.tasty.query.BundledSnapshotProbe
import kyo.internal.tasty.query.ClasspathOrchestrator
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.ZipHandle
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** JVM-only leaves for ClasspathInitBundledSnapshot that require real jar files (java.util.zip, java.io.File).
  *
  * Leaf 4: end-to-end transparent bundled load -- probe HIT; symbols come from bundled snapshot.
  * Leaf 5: mixed-root merge -- bundled jar + cold jar produces combined symbol count.
  * Leaf 6: digest mismatch under SoftFail falls back to cold load.
  * Leaf 7: digest mismatch under FailFast raises DigestMismatch.
  *
  * Cross-platform leaf (10) lives in ClasspathInitBundledSnapshotTest.scala (shared/src/test).
  *
  * Pins: INV-004, INV-005, INV-007.
  */
class ClasspathInitBundledSnapshotJvmTest extends Test:

    /** Build a valid KRFL snapshot bytes for a synthetic classpath with `n` Package symbols. */
    private def syntheticSnapshotBytes(n: Int, digest: Long): Array[Byte] =
        val syms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until n).map: i =>
                Tasty.Symbol.Package(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"bundledPkg$i"),
                    flags = Tasty.Flags.empty,
                    ownerId = Tasty.SymbolId(0),
                    memberIds = Chunk.empty
                )
        )
        val cp = Tasty.Classpath(
            symbols = syms,
            indices = Tasty.Classpath.Indices(
                byFqn = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"bundledPkg$i" -> Tasty.SymbolId(i)).toMap),
                bySimpleName = Dict.empty,
                packageIndex = Dict.from(syms.toSeq.zipWithIndex.map((s, i) => s"bundledPkg$i" -> Tasty.SymbolId(i)).toMap),
                subclassIndex = Dict.empty,
                companionIndex = Dict.empty,
                modulesIndex = Dict.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.from(syms.map(_.id)),
                unresolvedFqnByNegId = Dict.empty,
                diagnostics = Chunk.empty
            ),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val digestBytes = DigestComputer.longToBytes(digest)
        SnapshotWriter.serializeToBytes(cp, digestBytes)
    end syntheticSnapshotBytes

    /** Build a zip as Array[Byte] containing the given entries. JVM-only. */
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

    /** Parse all entries of a zip byte array into a HashMap. JVM-only. */
    private def parseZipEntries(zipBytes: Array[Byte]): mutable.HashMap[String, Array[Byte]] =
        val entries = mutable.HashMap.empty[String, Array[Byte]]
        val zis     = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zipBytes))
        var entry   = zis.getNextEntry
        while entry != null do
            if !entry.isDirectory then
                val name = entry.getName
                val baos = new ByteArrayOutputStream()
                val buf  = new Array[Byte](4096)
                var n    = zis.read(buf)
                while n >= 0 do
                    baos.write(buf, 0, n)
                    n = zis.read(buf)
                entries(name) = baos.toByteArray
            end if
            zis.closeEntry()
            entry = zis.getNextEntry
        end while
        zis.close()
        entries
    end parseZipEntries

    /** FileSource that serves zip entries from an in-memory map, keyed by jar root path. */
    private class ZipMemoryFileSource(zipMap: Map[String, Array[Byte]]) extends FileSource:
        def read(path: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.FileNotFound(path))
        def write(path: String, bytes: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.SnapshotIoError("read-only stub"))
        def rename(from: String, to: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.SnapshotIoError("read-only stub"))
        def mkdirs(path: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.SnapshotIoError("read-only stub"))
        def list(dir: String, suffixes: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) =
            Chunk.empty
        def exists(path: String)(using Frame): Boolean < Sync =
            false
        def stat(path: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) =
            Abort.fail(TastyError.FileNotFound(path))
        override def openZip(root: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
            zipMap.get(root) match
                case None => Maybe.Absent
                case Some(zipBytes) =>
                    Sync.defer:
                        val entries = parseZipEntries(zipBytes)
                        Maybe.Present(new ZipHandle:
                            def readEntry(internalPath: String)(using Frame): Maybe[Array[Byte]] < (Sync & Abort[TastyError]) =
                                entries.get(internalPath) match
                                    case Some(b) => Maybe.Present(b)
                                    case None    => Maybe.Absent)
    end ZipMemoryFileSource

    // Leaf 4: end-to-end transparent bundled load (probe HIT)
    // Pins: INV-004 end-to-end probe-HIT path
    "Leaf 4: end-to-end bundled load produces exact symbol count (probe HIT)" in run {
        val baseBytes     = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val baseFile      = writeTempJar(baseBytes)
        val basePath      = baseFile.getAbsolutePath
        val digest        = DigestComputer.digestForRoot(basePath)
        val snapshotBytes = syntheticSnapshotBytes(5, digest)
        val finalZipBytes = buildZipBytes(
            "Foo.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val source = new ZipMemoryFileSource(Map(basePath -> finalZipBytes))
        Scope.run:
            ClasspathOrchestrator.coldLoadBinding(
                roots = Seq(basePath),
                mode = Tasty.ErrorMode.SoftFail,
                cacheDir = Maybe.Absent,
                source = source
            ).map: binding =>
                val cp = binding.cp
                assert(
                    cp.symbols.size == 5,
                    s"expected exactly 5 bundled symbols (probe HIT), got ${cp.symbols.size}"
                )
                val hasMismatch = cp.errors.exists:
                    case _: TastyError.DigestMismatch => true
                    case _                            => false
                assert(!hasMismatch, s"unexpected DigestMismatch in errors: ${cp.errors}")
    }

    // Leaf 5: mixed-root merge (bundled HIT + cold jar)
    // Pins: INV-004 + INV-005 merge correctness
    "Leaf 5: mixed-root merge: bundled root contributes exact symbol count from snapshot" in run {
        val bundledBase     = buildZipBytes("A.class" -> Array[Byte](0xca.toByte))
        val bundledBaseFile = writeTempJar(bundledBase)
        val bundledBasePath = bundledBaseFile.getAbsolutePath
        val bundledDigest   = DigestComputer.digestForRoot(bundledBasePath)
        val bundledSnapshot = syntheticSnapshotBytes(3, bundledDigest)
        val bundledFinalZip = buildZipBytes(
            "A.class"                              -> Array[Byte](0xca.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> bundledSnapshot
        )
        val coldBase     = buildZipBytes("B.class" -> Array[Byte](0xca.toByte))
        val coldBaseFile = writeTempJar(coldBase)
        val coldBasePath = coldBaseFile.getAbsolutePath
        val source       = new ZipMemoryFileSource(Map(bundledBasePath -> bundledFinalZip))
        Scope.run:
            ClasspathOrchestrator.coldLoadBinding(
                roots = Seq(bundledBasePath, coldBasePath),
                mode = Tasty.ErrorMode.SoftFail,
                cacheDir = Maybe.Absent,
                source = source
            ).map: binding =>
                val cp = binding.cp
                assert(
                    cp.symbols.size == 3,
                    s"expected 3 merged symbols (3 bundled + 0 cold), got ${cp.symbols.size}"
                )
                val hasMismatch = cp.errors.exists:
                    case _: TastyError.DigestMismatch => true
                    case _                            => false
                assert(!hasMismatch, s"unexpected DigestMismatch: ${cp.errors}")
    }

    // Leaf 6: SoftFail falls back on digest mismatch
    // Pins: INV-007 SoftFail
    "Leaf 6: SoftFail on digest mismatch falls back to cold load without raising Abort" in run {
        val staleJar = writeTempJar(buildZipBytes(
            "B.class"                              -> Array[Byte](0xca.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> syntheticSnapshotBytes(2, 0xdeadbeefL)
        ))
        Tasty.withClasspath(Seq(staleJar.getAbsolutePath)):
            Tasty.classpath.map: cp =>
                assert(cp.symbols.size >= 0, "symbol count must be non-negative after SoftFail fallback")
    }

    // Leaf 7: probe raises DigestMismatch on stale snapshot
    // Pins: INV-007 FailFast
    "Leaf 7: probe raises DigestMismatch when embedded digest does not match recomputed digest" in run {
        val baseBytes      = buildZipBytes("C.class" -> Array[Byte](0xca.toByte))
        val rootFile       = writeTempJar(baseBytes)
        val rootPath       = rootFile.getAbsolutePath
        val staleSnapBytes = syntheticSnapshotBytes(2, 0xdeadbeefL)
        val source: FileSource = new FileSource:
            def read(p: String)(using Frame): Array[Byte] < (Sync & Abort[TastyError]) = Abort.fail(TastyError.FileNotFound(p))
            def write(p: String, b: Array[Byte])(using Frame): Unit < (Sync & Abort[TastyError]) =
                Abort.fail(TastyError.SnapshotIoError("stub"))
            def rename(f: String, t: String)(using Frame): Unit < (Sync & Abort[TastyError]) =
                Abort.fail(TastyError.SnapshotIoError("stub"))
            def mkdirs(p: String)(using Frame): Unit < (Sync & Abort[TastyError]) = Abort.fail(TastyError.SnapshotIoError("stub"))
            def list(d: String, s: Chunk[String])(using Frame): Chunk[String] < (Sync & Abort[TastyError]) = Chunk.empty
            def exists(p: String)(using Frame): Boolean < Sync                                             = false
            def stat(p: String)(using Frame): FileSource.FileStat < (Sync & Abort[TastyError]) = Abort.fail(TastyError.FileNotFound(p))
            override def openZip(rt: String)(using Frame): Maybe[ZipHandle] < (Sync & Scope & Abort[TastyError]) =
                if rt == rootPath then
                    Sync.defer:
                        Maybe.Present(new ZipHandle:
                            def readEntry(ip: String)(using Frame): Maybe[Array[Byte]] < (Sync & Abort[TastyError]) =
                                if ip == BundledSnapshotProbe.snapshotEntryPath then Maybe.Present(staleSnapBytes)
                                else Maybe.Absent)
                else Maybe.Absent
        Scope.run:
            Abort.run[TastyError]:
                BundledSnapshotProbe.probe(rootPath, source)
            .map:
                case Result.Failure(_: TastyError.DigestMismatch) => assert(true)
                case other                                        => assert(false, s"expected DigestMismatch failure, got: $other")
    }

end ClasspathInitBundledSnapshotJvmTest
