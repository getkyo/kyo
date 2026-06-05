package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.snapshot.DigestComputer

/** Cross-platform `DigestComputer.compute` tests covering jar-root and directory-root branches.
  *
  * Phase 12 post-audit carry: T-J1, T-J3, T-J4, T-J5 migrated to `runJVM` with real temp jars after BLOCKER-1 fix. JVM jar roots now use
  * CEN-walk (RandomAccessFile) which requires real files on disk; MemoryFileSource jar paths are not accessible via RandomAccessFile and
  * would produce TastyError.MalformedSection (missing-jar is loud, not silent 0L). See decisions.md entry 10 for rationale.
  *
  * Scaladoc: 8-35 lines.
  */
class SnapshotDigestTest extends Test:

    import AllowUnsafe.embrace.danger

    // T-J1: jar-root digest is deterministic across two calls on the same jar (JVM: real jar; BLOCKER-1 post-fix).
    "DigestComputer.compute on jar root returns same digest for two successive calls" in runJVM {
        import java.nio.file.Files
        import kyo.internal.tasty.query.PlatformFileSource
        val dir     = Files.createTempDirectory("kyo-sndt-tj1").toAbsolutePath.toString
        val jarPath = s"$dir/tj1.jar"
        writeJar(jarPath, Seq("Test.class" -> Array[Byte](1, 2, 3, 4, 5, 6, 7, 8, 9)))
        val src = PlatformFileSource.get
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

    // T-J3: INV-003 -- bumping jar mtime must NOT change the digest (content-addressed; JVM CEN-walk).
    // After Phase 12 the jar digest is based on CEN CRC32 entries, not mtime. A mtime-only
    // change must produce the same digest (JVM: CEN walk; path-hash fallback is also mtime-invariant).
    "DigestComputer.compute jar mtime change does NOT change digest (INV-003 content-addressed)" in runJVM {
        import java.nio.file.Files
        import kyo.internal.tasty.query.PlatformFileSource
        val dir     = Files.createTempDirectory("kyo-sndt-tj3").toAbsolutePath.toString
        val jarPath = s"$dir/tj3.jar"
        writeJar(jarPath, Seq("Test.class" -> Array[Byte](1, 2, 3)))
        val src = PlatformFileSource.get
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath), src).flatMap: d1 =>
                Sync.defer:
                    Files.setLastModifiedTime(
                        java.nio.file.Paths.get(jarPath),
                        java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L + 3_600_000L)
                    )
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

    // T-J4: different jar paths with identical CEN content produce different compute digests.
    // The outer compute loop mixes the jar path string; two different paths must produce different results.
    "DigestComputer.compute for two different jar paths produces different digests" in runJVM {
        import java.nio.file.Files
        import kyo.internal.tasty.query.PlatformFileSource
        val dir      = Files.createTempDirectory("kyo-sndt-tj4").toAbsolutePath.toString
        val jarPath1 = s"$dir/tj4a.jar"
        val jarPath2 = s"$dir/tj4b.jar"
        writeJar(jarPath1, Seq("Test.class" -> Array[Byte](1, 2, 3)))
        writeJar(jarPath2, Seq("Test.class" -> Array[Byte](1, 2, 3)))
        val src = PlatformFileSource.get
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath1), src).flatMap: d1 =>
                DigestComputer.compute(Seq(jarPath2), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(!d1.sameElements(d2), "different jar paths must produce different compute digests")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    // T-J5: mixed jar+directory roots produce the same digest regardless of root order.
    "DigestComputer.compute on mixed jar+directory roots is root-order independent" in runJVM {
        import java.nio.file.Files
        import kyo.internal.tasty.query.PlatformFileSource
        val dir     = Files.createTempDirectory("kyo-sndt-tj5").toAbsolutePath.toString
        val jarPath = s"$dir/tj5.jar"
        writeJar(jarPath, Seq("Test.class" -> Array[Byte](10, 20, 30)))
        // Write a real .tasty file so the directory root is non-empty.
        val tastyDir  = s"$dir/root"
        val tastyFile = s"$tastyDir/PlainClass.tasty"
        Files.createDirectories(java.nio.file.Paths.get(tastyDir))
        Files.write(java.nio.file.Paths.get(tastyFile), kyo.fixtures.Embedded.plainClassTasty)
        val src = PlatformFileSource.get
        Abort.run[TastyError]:
            DigestComputer.compute(Seq(jarPath, tastyDir), src).flatMap: d1 =>
                DigestComputer.compute(Seq(tastyDir, jarPath), src).map: d2 =>
                    (d1, d2)
        .map:
            case Result.Success((d1, d2)) =>
                assert(d1.sameElements(d2), s"root order must not affect digest: ${d1.toSeq} vs ${d2.toSeq}")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    private def writeJar(path: String, entries: Seq[(String, Array[Byte])]): Unit =
        import java.io.FileOutputStream
        import java.util.zip.ZipEntry
        import java.util.zip.ZipOutputStream
        val fos = new FileOutputStream(path)
        val zos = new ZipOutputStream(fos)
        try
            for (name, bytes) <- entries do
                val entry = new ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(bytes)
                zos.closeEntry()
        finally
            zos.close()
            fos.close()
        end try
    end writeJar

end SnapshotDigestTest
