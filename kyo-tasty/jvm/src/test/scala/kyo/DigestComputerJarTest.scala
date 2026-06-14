package kyo

import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kyo.internal.tasty.snapshot.DigestComputer
import kyo.internal.tasty.snapshot.DigestComputer.JarDigestEntry

/** DigestComputer behavior on real jars and JPMS modules:
  *   - digestForRoot is stable across an mtime-only copy of byte-identical jars
  *   - digestForRoot changes when class bytes change
  *   - compute on the jrt:/ filesystem returns a stable 8-byte result
  */
class DigestComputerJarTest extends kyo.test.Test[Any]:

    "digestForRoot stable across mtime-only copy of real JAR" in {
        val dir     = Files.createTempDirectory("kyo-dct-leaf1").toAbsolutePath.toString
        val jarA    = s"$dir/A.jar"
        val jarB    = s"$dir/B.jar"
        val content = Array[Byte](0xca.toByte, 0xfe.toByte, 0xba.toByte, 0xbe.toByte)
        writeJar(jarA, Seq("foo.class" -> content))
        writeJar(jarB, Seq("foo.class" -> content))
        Files.setLastModifiedTime(
            java.nio.file.Paths.get(jarA),
            java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L)
        )
        Files.setLastModifiedTime(
            java.nio.file.Paths.get(jarB),
            java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L + 3_600_000L)
        )
        val dA = DigestComputer.digestForRoot(jarA)
        val dB = DigestComputer.digestForRoot(jarB)
        assert(dA == dB, s"mtime-only copy must produce same CEN-CRC digest: $dA vs $dB")
    }

    "digestForRoot changes when class bytes change (CEN CRC32 differs)" in {
        val dir    = Files.createTempDirectory("kyo-dct-leaf2").toAbsolutePath.toString
        val jarA   = s"$dir/A.jar"
        val jarMod = s"$dir/A_mod.jar"
        writeJar(jarA, Seq("foo.class" -> Array[Byte](1, 2, 3)))
        writeJar(jarMod, Seq("foo.class" -> Array[Byte](1, 2, 4)))
        val dA = DigestComputer.digestForRoot(jarA)
        val dM = DigestComputer.digestForRoot(jarMod)
        assert(dA != dM, s"byte-content change must produce different digestForRoot: $dA vs $dM")
    }

    "jrt:/ compute returns a stable 8-byte result" in {
        Abort.run[TastyError] {
            DigestComputer.compute(Seq("jrt:/")).map { d1 =>
                DigestComputer.compute(Seq("jrt:/")).map { d2 =>
                    assert(d1.length == 8, s"digest must be 8 bytes, got ${d1.length}")
                    assert(d1.sameElements(d2), "jrt:/ digest must be stable")
                }
            }
        }.map {
            case Result.Success(_) => succeed
            case Result.Failure(e) => fail(s"Unexpected failure: $e")
            case Result.Panic(t)   => throw t
        }
    }

    private def writeJar(path: String, entries: Seq[(String, Array[Byte])]): Unit =
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

end DigestComputerJarTest
