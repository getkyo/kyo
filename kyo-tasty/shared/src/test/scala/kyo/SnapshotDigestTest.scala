package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.snapshot.DigestComputer

/** Cross-platform `DigestComputer.compute` tests covering jar-root and directory-root branches.
  *
  * Phase 2 post-audit migration: previously lived in `kyo-tasty/jvm/src/test/scala/kyo/SnapshotRoundTripJvmTest.scala` as JVM-only
  * leaves T-J1, T-J3, T-J4, T-J5 (built real on-disk JARs via `ZipOutputStream` + `FileOutputStream`). All four leaves are reframed
  * against `MemoryFileSource`: the in-memory source treats any path with a `.jar` suffix as a jar root (DigestComputer dispatches via
  * `path.toLowerCase.endsWith(".jar")`), and exposes `setMtime` so the mtime-change leaf can simulate `+1 hour` without touching the
  * filesystem. The remaining mmap-specific leaf (G16a) stays in the JVM-only file because it exercises `FileChannel.map`.
  *
  * Scaladoc: 8-35 lines.
  */
class SnapshotDigestTest extends Test:

    import AllowUnsafe.embrace.danger

    // T-J1: jar-root digest is deterministic across two calls on the same jar
    "DigestComputer.compute on jar root returns same digest for two successive calls" in run {
        val src     = MemoryFileSource()
        val jarPath = "mem/tj1.jar"
        src.add(jarPath, Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9))
        src.setMtime(jarPath, 1_700_000_000_000L)
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath), src).flatMap: d1 =>
                DigestComputer.compute(Seq(jarPath), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"jar-root digest must be deterministic: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T-J3: INV-003 -- bumping jar mtime must NOT change the digest (content-addressed)
    // After Phase 12 the jar digest is based on CEN CRC32 entries, not mtime. A mtime-only
    // change must produce the same digest on JVM (CEN walk) and all platforms (path-hash fallback
    // is also mtime-invariant since it only reads the path string).
    "DigestComputer.compute jar mtime change does NOT change digest (INV-003 content-addressed)" in run {
        val src     = MemoryFileSource()
        val jarPath = "mem/tj3.jar"
        src.add(jarPath, Array[Byte]())
        src.setMtime(jarPath, 1_700_000_000_000L)
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath), src).flatMap: d1 =>
                Sync.defer:
                    src.setMtime(jarPath, 1_700_000_000_000L + 3_600_000L)
                .flatMap: _ =>
                    DigestComputer.compute(Seq(jarPath), src).map: d2 =>
                        (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), "mtime-only change must NOT change the CEN-CRC digest (INV-003)")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T-J4: INV-003 -- jar path change produces a different digest (cross-platform determinism)
    // After Phase 12, the in-memory jar digest is based on the jar path (platform fallback) or
    // CEN CRC32 (JVM with real JAR). Two different paths must produce different digests.
    // Real CEN content-change detection is covered by DigestComputerTest leaf 2 (JVM-only, real JAR).
    "DigestComputer.compute for two different jar paths produces different digests" in run {
        val src      = MemoryFileSource()
        val jarPath1 = "mem/tj4a.jar"
        val jarPath2 = "mem/tj4b.jar"
        src.add(jarPath1, Array[Byte](1, 2, 3))
        src.add(jarPath2, Array[Byte](1, 2, 3))
        src.setMtime(jarPath1, 1_700_000_000_000L)
        src.setMtime(jarPath2, 1_700_000_000_000L)
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath1), src).flatMap: d1 =>
                DigestComputer.compute(Seq(jarPath2), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(!d1.sameElements(d2), "different jar paths must produce different digests")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T-J5: mixed jar+directory roots produce the same digest regardless of root order
    "DigestComputer.compute on mixed jar+directory roots is root-order independent" in run {
        val src     = MemoryFileSource()
        val jarPath = "mem/tj5.jar"
        src.add(jarPath, Array[Byte](10, 20, 30))
        src.setMtime(jarPath, 1_700_000_000_000L)
        // Directory root with one embedded fixture .tasty entry.
        src.add("root/PlainClass.tasty", kyo.fixtures.Embedded.plainClassTasty)
        src.setMtime("root/PlainClass.tasty", 1_700_000_000_000L)
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath, "root"), src).flatMap: d1 =>
                DigestComputer.compute(Seq("root", jarPath), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"root order must not affect digest: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

end SnapshotDigestTest
