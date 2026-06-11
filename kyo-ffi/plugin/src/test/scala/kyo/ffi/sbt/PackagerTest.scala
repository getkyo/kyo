package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** R11 / F7, Packager routing and failure-path tests.
  *
  * The Packager dispatches across three platform arms:
  *   - "Native" → no-op (returns Nil)
  *   - "JS"     → copies into kyo-ffi/native layout
  *   - anything else (JVM) → copies into META-INF/native layout
  *
  * Failure paths: `copyForCurrentPlatform` calls `Files.copy`, which throws
  * `java.nio.file.NoSuchFileException` when the source artifact is absent.
  * `copyForPlatformMulti` is a thin flatMap wrapper; its routing
  * is verified via the "Native" arm.
  */
class PackagerTest extends AnyFunSuite with Matchers {

    private def tempDir(): File = {
        val p = Files.createTempDirectory("kyo-ffi-packager-spec-")
        p.toFile
    }

    // -------------------------------------------------------------------------
    // copyForPlatform: Native arm is a no-op
    // -------------------------------------------------------------------------

    test("copyForPlatform: 'Native' returns Nil regardless of artifacts") {
        val result = Packager.copyForPlatform(
            platform = "Native",
            artifacts = Seq(new File("/nonexistent/libkyo.so")),
            resDir = tempDir(),
            libraryId = "kyo_test"
        )
        result shouldBe Nil
    }

    test("copyForPlatform: 'Native' with empty artifact list also returns Nil") {
        val result = Packager.copyForPlatform(
            platform = "Native",
            artifacts = Nil,
            resDir = tempDir(),
            libraryId = "kyo_test"
        )
        result shouldBe Nil
    }

    // -------------------------------------------------------------------------
    // copyForPlatformMulti: Native arm across multiple libraries
    // -------------------------------------------------------------------------

    test("copyForPlatformMulti: 'Native' returns Nil for all libs") {
        val libs = Seq(
            "kyo_a" -> Seq(new File("/nonexistent/libkyo_a.so")),
            "kyo_b" -> Seq(new File("/nonexistent/libkyo_b.so"))
        )
        val result = Packager.copyForPlatformMulti(
            platform = "Native",
            libs = libs,
            resDir = tempDir()
        )
        result shouldBe Nil
    }

    // -------------------------------------------------------------------------
    // copyForCurrentPlatform: non-existent artifact throws
    // -------------------------------------------------------------------------

    test("copyForCurrentPlatform: throws NoSuchFileException for missing artifact") {
        val resDir  = tempDir()
        val missing = new File("/nonexistent-path/libkyo_tcp-linux-x86_64.so")
        intercept[java.nio.file.NoSuchFileException] {
            Packager.copyForCurrentPlatform(
                artifacts = Seq(missing),
                resDir = resDir,
                libraryId = "kyo_tcp"
            )
        }
    }

    // -------------------------------------------------------------------------
    // copyForCurrentPlatform: single real file copies into os-arch sub-dir
    // -------------------------------------------------------------------------

    test("copyForCurrentPlatform: copies artifact under os-arch subdir stripping platform suffix") {
        val resDir = tempDir()
        val tmpSrc = Files.createTempFile("libkyo_test-tmp-", ".so").toFile
        // Write content so we can verify the copy.
        Files.write(tmpSrc.toPath, "fake-native-lib".getBytes)
        try {
            val os   = CCompiler.detectOs()
            val arch = CCompiler.detectArch()
            // Artifact name must embed the os-arch suffix that Packager strips.
            val srcNamed = new File(tmpSrc.getParentFile, s"libkyo_test-$os-$arch.so")
            tmpSrc.renameTo(srcNamed)

            val dests = Packager.copyForCurrentPlatform(
                artifacts = Seq(srcNamed),
                resDir = resDir,
                libraryId = "kyo_test"
            )

            dests should have length 1
            val dest = dests.head
            // Destination lives under <resDir>/<os>-<arch>/
            dest.getParentFile.getName shouldBe s"$os-$arch"
            // Platform suffix is stripped: libkyo_test-linux-x86_64.so → libkyo_test.so
            dest.getName shouldBe "libkyo_test.so"
            dest.exists() shouldBe true
            Files.readAllBytes(dest.toPath).toSeq shouldBe "fake-native-lib".getBytes.toSeq
        } finally {
            tmpSrc.delete()
        }
    }

    // -------------------------------------------------------------------------
    // copyForCurrentPlatform: artifact without platform suffix is copied unchanged
    // -------------------------------------------------------------------------

    test("copyForCurrentPlatform: artifact without os-arch suffix is copied with original name") {
        val resDir = tempDir()
        val src    = Files.createTempFile("libkyo_plain-", ".so").toFile
        Files.write(src.toPath, "plain".getBytes)
        try {
            // No os-arch suffix in the name, canonicalName should leave it unchanged.
            val dests = Packager.copyForCurrentPlatform(
                artifacts = Seq(src),
                resDir = resDir,
                libraryId = "kyo_plain"
            )

            dests should have length 1
            dests.head.getName shouldBe src.getName
        } finally {
            src.delete()
        }
    }
}
