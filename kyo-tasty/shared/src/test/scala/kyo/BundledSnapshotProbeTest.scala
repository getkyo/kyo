package kyo

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.MemoryFileSource
import kyo.internal.tasty.query.BundledSnapshotProbe
import kyo.internal.tasty.query.FileSource
import kyo.internal.tasty.query.ZipHandle
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotWriter
import scala.collection.mutable

/** Phase 13 plan leaves for BundledSnapshotProbe unit tests.
  *
  * Leaf 1: jar with no snapshot entry returns Maybe.Absent.
  * Leaf 2: jar with valid snapshot and matching digest returns Maybe.Present.
  * Leaf 3: jar with stale digest raises TastyError.DigestMismatch.
  * Leaf 8: remap-at-merge preserves identity (symbol id + offset == merged id).
  * Leaf 9: cross-classpath isolation -- two partials with overlapping ids.
  * Leaf 11: probe is idempotent (same bytes returned on two calls).
  *
  * All leaves that require real zip construction are gated with runJVM. The probe and merge
  * logic tested here is in shared/ and compiles on all platforms; only the ZipOutputStream
  * construction is JVM-only.
  *
  * Pins: INV-004, INV-005, INV-007.
  */
class BundledSnapshotProbeTest extends Test:

    // ── in-memory zip FileSource (JVM test helper) ─────────────────────────────

    /** A FileSource that serves zip entries from an in-memory map of root -> zip bytes.
      *
      * Does NOT extend MemoryFileSource (which is final). All FileSource methods except openZip
      * return sensible stubs sufficient for probe-only tests.
      */
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

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Build a valid KRFL snapshot bytes for a synthetic classpath with `n` Package symbols. */
    private def syntheticSnapshotBytes(n: Int, digest: Long): Array[Byte] =
        val syms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until n).map: i =>
                Tasty.Symbol.Package(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"pkg$i"),
                    flags = Tasty.Flags.empty,
                    ownerId = Tasty.SymbolId(0),
                    memberIds = Chunk.empty
                )
        )
        val cp = Tasty.Classpath(
            symbols = syms,
            indices = Tasty.Classpath.Indices(
                byFqn = syms.toSeq.zipWithIndex.map((s, i) => s"pkg$i" -> Tasty.SymbolId(i)).toMap,
                bySimpleName = Map.empty,
                packageIndex = syms.toSeq.zipWithIndex.map((s, i) => s"pkg$i" -> Tasty.SymbolId(i)).toMap,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                modulesIndex = Map.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.from(syms.map(_.id)),
                unresolvedFqnByNegId = Map.empty,
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

    /** Write zip bytes to a temp jar and return the path. Temp file is deleted on JVM exit. */
    private def writeTempJarBytes(zipBytes: Array[Byte]): String =
        val tmp = java.io.File.createTempFile("kyo-probe-", ".jar")
        tmp.deleteOnExit()
        val fos = new java.io.FileOutputStream(tmp)
        try fos.write(zipBytes)
        finally fos.close()
        tmp.getAbsolutePath
    end writeTempJarBytes

    // ── Leaf 1: jar with no snapshot returns Absent ─────────────────────────
    // Given: fixture jar containing only .class entries (no snapshot entry)
    // When: BundledSnapshotProbe.probe(jarPath)
    // Then: returns Maybe.Absent
    // Pins: INV-004 probe-absent
    "Leaf 1: jar with no snapshot entry returns Maybe.Absent" in runJVM {
        // Use an in-memory ZipMemoryFileSource: no snapshot entry in the zip, so probe returns Absent.
        // No need to hit disk; the probe returns Absent before ever computing a digest.
        val noSnapBytes = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val root        = "no-snap.jar"
        val source      = new ZipMemoryFileSource(Map(root -> noSnapBytes))
        Scope.run:
            BundledSnapshotProbe.probe(root, source).map: result =>
                assert(result == Maybe.Absent, s"expected Absent, got $result")
    }

    // ── Leaf 2: jar with valid snapshot returns Present ───────────────────────
    // Given: real jar on disk with KRFL snapshot; embedded digest matches JVM CEN walk digest.
    //        Two-pass approach: pass 1 writes a base jar (no snapshot) to get the CEN digest;
    //        pass 2 writes the same jar PLUS the snapshot entry with the correct digest embedded.
    // When: BundledSnapshotProbe.probe(jarPath) via JvmFileSource
    // Then: returns Maybe.Present(bytes) where bytes.length > 0
    // Pins: INV-004 probe-present
    "Leaf 2: jar with valid snapshot and matching digest returns Maybe.Present" in runJVM {
        // Pass 1: write base jar without snapshot, compute its CEN digest.
        val baseBytes = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val basePath  = writeTempJarBytes(baseBytes)
        val digest    = DigestComputer.digestForRoot(basePath)
        // Pass 2: write jar with snapshot entry embedding the base-jar digest.
        val snapshotBytes = syntheticSnapshotBytes(3, digest)
        // We use the base jar path directly but serve the zip contents in-memory via ZipMemoryFileSource.
        // The probe calls DigestComputer.digestForRoot(root) with `root = basePath` and computes the
        // CEN digest from the REAL file at basePath (which has no snapshot entry -- correct).
        // The snapshotBytes embed that same digest, so they will match.
        val jarWithSnapBytes = buildZipBytes(
            "Foo.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        // Use a ZipMemoryFileSource keyed by basePath so openZip finds the entries.
        val source = new ZipMemoryFileSource(Map(basePath -> jarWithSnapBytes))
        Scope.run:
            BundledSnapshotProbe.probe(basePath, source).map: result =>
                assert(result.isDefined, "expected Maybe.Present for valid snapshot")
                result match
                    case Maybe.Present(bytes) => assert(bytes.length > 0, "snapshot bytes must be non-empty")
                    case Maybe.Absent         => assert(false, "unreachable")
    }

    // ── Leaf 3: digest mismatch raises DigestMismatch ─────────────────────────
    // Given: real jar on disk; snapshot embeds a wrong digest (0xdeadbeef).
    // When: BundledSnapshotProbe.probe(jarPath) via Abort.run
    // Then: Result.Failure(TastyError.DigestMismatch(_, _))
    // Pins: INV-007 verify-then-fallback
    "Leaf 3: digest mismatch raises TastyError.DigestMismatch" in runJVM {
        val baseBytes     = buildZipBytes("Foo.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val basePath      = writeTempJarBytes(baseBytes)
        val staleDigest   = 0xdeadbeefL // intentionally wrong
        val snapshotBytes = syntheticSnapshotBytes(2, staleDigest)
        val jarWithStale = buildZipBytes(
            "Foo.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val source = new ZipMemoryFileSource(Map(basePath -> jarWithStale))
        Scope.run:
            Abort.run[TastyError]:
                BundledSnapshotProbe.probe(basePath, source)
            .map:
                case Result.Failure(e: TastyError.DigestMismatch) =>
                    assert(e.expected.nonEmpty, "expected hex must be non-empty")
                    assert(e.actual.nonEmpty, "actual hex must be non-empty")
                    assert(e.expected != e.actual, "expected and actual must differ for stale digest")
                case other =>
                    assert(false, s"expected DigestMismatch failure, got: $other")
    }

    // ── Leaf 8: remap-at-merge preserves identity ─────────────────────────────
    // Given: bundled partial with SymbolId space {0, 1, 2}; merged with offset 100.
    // When: mergePartialInto(existing100, partial3) and lookup by name.
    // Then: symbol with local id=1 in partial has id=101 in merged.
    // Pins: INV-005 remap-at-merge
    "Leaf 8: remap-at-merge shifts partial ids by existing classpath size" in run {
        val existingSyms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until 100).map: i =>
                Tasty.Symbol.Unresolved(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"ex$i"),
                    ownerId = Tasty.SymbolId(0),
                    flags = Tasty.Flags.empty
                )
        )
        val existing = Tasty.Classpath(
            symbols = existingSyms,
            indices = Tasty.Classpath.Indices(
                byFqn = existingSyms.toSeq.zipWithIndex.map((s, i) => s"ex$i" -> Tasty.SymbolId(i)).toMap,
                bySimpleName = Map.empty,
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                modulesIndex = Map.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                unresolvedFqnByNegId = Map.empty,
                diagnostics = Chunk.empty
            ),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val partialSyms: Chunk[Tasty.Symbol] = Chunk.from(
            (0 until 3).map: i =>
                Tasty.Symbol.Unresolved(
                    id = Tasty.SymbolId(i),
                    name = Tasty.Name(s"partial$i"),
                    ownerId = Tasty.SymbolId(0),
                    flags = Tasty.Flags.empty
                )
        )
        val partial = Tasty.Classpath(
            symbols = partialSyms,
            indices = Tasty.Classpath.Indices(
                byFqn = partialSyms.toSeq.zipWithIndex.map((s, i) => s"partial$i" -> Tasty.SymbolId(i)).toMap,
                bySimpleName = Map.empty,
                packageIndex = Map.empty,
                subclassIndex = Map.empty,
                companionIndex = Map.empty,
                modulesIndex = Map.empty,
                topLevelClassIds = Chunk.empty,
                packageIds = Chunk.empty,
                unresolvedFqnByNegId = Map.empty,
                diagnostics = Chunk.empty
            ),
            errors = Chunk.empty,
            modules = Chunk.empty,
            rootSymbolId = Tasty.SymbolId(0)
        )
        val merged = BundledSnapshotProbe.mergePartialInto(existing, partial)
        val sym101 = merged.symbols.find(_.id == Tasty.SymbolId(101))
        assert(sym101.isDefined, "symbol with id=101 must exist after merge at offset 100")
        assert(
            sym101.get.name.asString == "partial1",
            s"symbol at id=101 must have name 'partial1', got '${sym101.get.name.asString}'"
        )
        assert(
            merged.indices.byFqn.get("partial1").contains(Tasty.SymbolId(101)),
            "byFqn entry for 'partial1' must point to id=101"
        )
    }

    // ── Leaf 9: cross-classpath isolation ─────────────────────────────────────
    // Given: two bundled partials with overlapping ids {0, 1, 2}.
    // When: merged sequentially.
    // Then: post-merge ids for A and B symbols are disjoint.
    // Pins: INV-005 isolation
    "Leaf 9: two partials with overlapping ids produce disjoint ids after merge" in run {
        def makePartial(prefix: String, n: Int): Tasty.Classpath =
            val syms: Chunk[Tasty.Symbol] = Chunk.from(
                (0 until n).map: i =>
                    Tasty.Symbol.Unresolved(
                        id = Tasty.SymbolId(i),
                        name = Tasty.Name(s"$prefix$i"),
                        ownerId = Tasty.SymbolId(0),
                        flags = Tasty.Flags.empty
                    )
            )
            Tasty.Classpath(
                symbols = syms,
                indices = Tasty.Classpath.Indices(
                    byFqn = syms.toSeq.zipWithIndex.map((s, i) => s"$prefix$i" -> Tasty.SymbolId(i)).toMap,
                    bySimpleName = Map.empty,
                    packageIndex = Map.empty,
                    subclassIndex = Map.empty,
                    companionIndex = Map.empty,
                    modulesIndex = Map.empty,
                    topLevelClassIds = Chunk.empty,
                    packageIds = Chunk.empty,
                    unresolvedFqnByNegId = Map.empty,
                    diagnostics = Chunk.empty
                ),
                errors = Chunk.empty,
                modules = Chunk.empty,
                rootSymbolId = Tasty.SymbolId(0)
            )
        end makePartial
        val partialA = makePartial("a", 3)
        val partialB = makePartial("b", 3)
        val afterA   = BundledSnapshotProbe.mergePartialInto(Tasty.Classpath.empty, partialA)
        val afterAB  = BundledSnapshotProbe.mergePartialInto(afterA, partialB)
        assert(afterAB.symbols.size == 6, s"expected 6 symbols, got ${afterAB.symbols.size}")
        val ids = afterAB.symbols.map(_.id.value).toSet
        assert(ids == Set(0, 1, 2, 3, 4, 5), s"expected ids 0..5, got $ids")
        val symA0 = afterAB.symbols.find(_.id.value == 0)
        val symB0 = afterAB.symbols.find(_.id.value == 3)
        assert(symA0.exists(_.name.asString == "a0"), s"expected a0 at id=0, got ${symA0.map(_.name.asString)}")
        assert(symB0.exists(_.name.asString == "b0"), s"expected b0 at id=3, got ${symB0.map(_.name.asString)}")
    }

    // ── Leaf 11: probe is idempotent ────────────────────────────────────────
    // Given: same jar probed twice.
    // When: probe(jar); probe(jar)
    // Then: both return Maybe.Present with byte-identical snapshots.
    // Pins: INV-004 idempotence
    "Leaf 11: probe is idempotent; same bytes returned on two calls" in runJVM {
        // Two-pass: compute digest from base jar, then serve jar+snapshot in-memory.
        val baseBytes      = buildZipBytes("Bar.class" -> Array[Byte](0xca.toByte, 0xfe.toByte))
        val basePath       = writeTempJarBytes(baseBytes)
        val expectedDigest = DigestComputer.digestForRoot(basePath)
        val snapshotBytes  = syntheticSnapshotBytes(2, expectedDigest)
        val jarBytes = buildZipBytes(
            "Bar.class"                            -> Array[Byte](0xca.toByte, 0xfe.toByte),
            BundledSnapshotProbe.snapshotEntryPath -> snapshotBytes
        )
        val root   = basePath
        val source = new ZipMemoryFileSource(Map(root -> jarBytes))
        Scope.run:
            BundledSnapshotProbe.probe(root, source).flatMap: r1 =>
                BundledSnapshotProbe.probe(root, source).map: r2 =>
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

end BundledSnapshotProbeTest
