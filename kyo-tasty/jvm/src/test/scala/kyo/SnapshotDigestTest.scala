package kyo

import kyo.internal.MemoryFileSource
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.SnapshotWriter

/** DigestComputer.compute on jar-root and directory-root branches using real temp jars. Jar roots use CEN-walk (RandomAccessFile), which
  * requires real files on disk.
  */
class SnapshotDigestTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "DigestComputer.compute on jar root returns same digest for two successive calls".onlyJvm in {
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

    "DigestComputer.compute jar mtime change does NOT change digest (content-addressed)".onlyJvm in {
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
                assert(d1.sameElements(d2), "mtime-only change must NOT change the CEN-CRC digest")
            case Result.Failure(e) =>
                fail(s"Unexpected failure: $e")
            case Result.Panic(t) =>
                throw t
    }

    "DigestComputer.compute for two different jar paths produces different digests".onlyJvm in {
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

    "DigestComputer.compute on mixed jar+directory roots is root-order independent".onlyJvm in {
        import java.nio.file.Files
        import kyo.internal.tasty.query.PlatformFileSource
        val dir     = Files.createTempDirectory("kyo-sndt-tj5").toAbsolutePath.toString
        val jarPath = s"$dir/tj5.jar"
        writeJar(jarPath, Seq("Test.class" -> Array[Byte](10, 20, 30)))
        // Write a real.tasty file so the directory root is non-empty.
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

    "snapshot serialization is digest-stable across two calls" in {
        import kyo.Tasty.SymbolId
        val rootSym = Tasty.Symbol.Package(SymbolId(0), Tasty.Name(""), Tasty.Flags.empty, SymbolId(0), Chunk.empty)
        val errors = Chunk[TastyError](
            TastyError.UnhandledSubtypingCase("Applied-TermRef-_", Tasty.Type.Any, Tasty.Type.Nothing, "X.tasty"),
            TastyError.UnresolvedReference("x.Y", 7)
        )
        val cp = Tasty.Classpath.make(
            symbols = Chunk(rootSym),
            rootSymbolId = SymbolId(0),
            topLevelClassIds = Chunk.empty,
            packageIds = Chunk(rootSym.id),
            fqnIndex = Dict.empty,
            packageIndex = Dict.empty,
            subclassIndex = Dict.empty,
            companionIndex = Dict.empty,
            moduleIndex = Dict.empty,
            errors = errors
        )
        val digest = Array[Byte](0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17)

        val bytes1 = SnapshotWriter.serializeToBytes(cp, digest)
        val bytes2 = SnapshotWriter.serializeToBytes(cp, digest)

        assert(
            java.util.Arrays.equals(bytes1, bytes2),
            "Two serializations of the same classpath must produce identical bytes"
        )

        // The 8-byte input digest is embedded at bytes [16.23] (magic 4 + version 4 + flags 8 = 16).
        val digestSlice1 = java.util.Arrays.copyOfRange(bytes1, 16, 24)
        val digestSlice2 = java.util.Arrays.copyOfRange(bytes2, 16, 24)
        val hex1         = DigestComputer.toHexString(digestSlice1)
        val hex2         = DigestComputer.toHexString(digestSlice2)
        assert(hex1 == hex2, s"Digest hex strings must be equal: $hex1 vs $hex2")

        // Tamper: flip byte 16. The digest field starts here; the tampered snapshot carries a different digest.
        val tamperedBytes = bytes1.clone()
        tamperedBytes(16) = (tamperedBytes(16) ^ 0xff.toByte).toByte
        val tamperedDigestSlice = java.util.Arrays.copyOfRange(tamperedBytes, 16, 24)
        val hexTampered         = DigestComputer.toHexString(tamperedDigestSlice)
        assert(hex1 != hexTampered, s"Tampered digest must differ from original: both are $hex1")
        succeed
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
