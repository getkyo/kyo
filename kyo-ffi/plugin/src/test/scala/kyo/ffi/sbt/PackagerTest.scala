package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** R11 / F7, Packager routing and failure-path tests.
  *
  * The Packager dispatches across three platform arms:
  *   - "Native" → no-op (returns Nil)
  *   - "JS"     → copies into kyo-ffi/native layout
  *   - anything else (JVM) → copies into META-INF/native layout
  *
  * The destination `os`-`arch` is a parameter (the artifact's own platform, which is the build
  * target rather than the build host), so these assert both the host-default path and a target
  * that differs from the host.
  *
  * Failure paths: `copyForJvm` calls `Files.copy`, which throws
  * `java.nio.file.NoSuchFileException` when the source artifact is absent.
  * `copyForPlatformMulti` is a thin flatMap wrapper; its routing
  * is verified via the "Native" arm.
  */
class PackagerTest extends AnyFunSuite with Matchers {

    private def tempDir(): File = {
        val p = Files.createTempDirectory("kyo-ffi-packager-spec-")
        p.toFile
    }

    /** An `<os>-<arch>` that is NOT this host's, so a test can tell "packaged for the target" from
      * "packaged for whatever machine ran the test".
      */
    private def foreignOsArch: (String, String) = {
        val hostOs   = CCompiler.detectOs()
        val hostArch = CCompiler.detectArch()
        if (hostOs == "darwin" && hostArch == "aarch64") ("darwin", "x86_64")
        else if (hostOs == "darwin") ("darwin", "aarch64")
        else ("darwin", "aarch64")
    }

    /** Write `content` to a file named `name` in a fresh temp directory. */
    private def artifactNamed(name: String, content: String): File = {
        val dir  = Files.createTempDirectory("kyo-ffi-packager-src-").toFile
        val file = new File(dir, name)
        Files.write(file.toPath, content.getBytes)
        file
    }

    // -------------------------------------------------------------------------
    // copyForPlatform: Native arm is a no-op
    // -------------------------------------------------------------------------

    test("copyForPlatform: 'Native' returns Nil regardless of artifacts") {
        val result = Packager.copyForPlatform(
            platform = "Native",
            artifacts = Seq(new File("/nonexistent/libkyo.so")),
            resDir = tempDir(),
            libraryId = "kyo_test",
            os = "linux",
            arch = "x86_64"
        )
        result shouldBe Nil
    }

    test("copyForPlatform: 'Native' with empty artifact list also returns Nil") {
        val result = Packager.copyForPlatform(
            platform = "Native",
            artifacts = Nil,
            resDir = tempDir(),
            libraryId = "kyo_test",
            os = "linux",
            arch = "x86_64"
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
            resDir = tempDir(),
            os = "linux",
            arch = "x86_64"
        )
        result shouldBe Nil
    }

    // -------------------------------------------------------------------------
    // copyForJvm: non-existent artifact throws
    // -------------------------------------------------------------------------

    test("copyForJvm: throws NoSuchFileException for missing artifact") {
        val resDir  = tempDir()
        val missing = new File("/nonexistent-path/libkyo_tcp-linux-x86_64.so")
        intercept[java.nio.file.NoSuchFileException] {
            Packager.copyForJvm(
                artifacts = Seq(missing),
                resDir = resDir,
                libraryId = "kyo_tcp",
                os = "linux",
                arch = "x86_64"
            )
        }
    }

    // -------------------------------------------------------------------------
    // copyForJvm: host default (the os-arch an unset ffiTargetOsArch resolves to)
    // -------------------------------------------------------------------------

    test("copyForJvm: copies artifact under os-arch subdir stripping platform suffix") {
        val resDir = tempDir()
        val os     = CCompiler.detectOs()
        val arch   = CCompiler.detectArch()
        // Artifact name must embed the os-arch suffix that Packager strips.
        val srcNamed = artifactNamed(s"libkyo_test-$os-$arch.so", "fake-native-lib")

        val dests = Packager.copyForJvm(
            artifacts = Seq(srcNamed),
            resDir = resDir,
            libraryId = "kyo_test",
            os = os,
            arch = arch
        )

        dests should have length 1
        val dest = dests.head
        // Destination lives under <resDir>/<os>-<arch>/
        dest.getParentFile.getName shouldBe s"$os-$arch"
        // Platform suffix is stripped: libkyo_test-linux-x86_64.so → libkyo_test.so
        dest.getName shouldBe "libkyo_test.so"
        dest.exists() shouldBe true
        Files.readAllBytes(dest.toPath).toSeq shouldBe "fake-native-lib".getBytes.toSeq
    }

    // -------------------------------------------------------------------------
    // copyForJvm: a TARGET os-arch that is not the host's
    // -------------------------------------------------------------------------

    test("copyForJvm: a target os-arch different from the host lands under the TARGET directory") {
        // The load-bearing case for cross-builds and staged prebuilts: before the os/arch became a
        // parameter, a darwin-x86_64 artifact built on an arm64 Mac landed in
        // META-INF/native/darwin-aarch64/ keeping its full -darwin-x86_64 suffix, which no runtime
        // lookup resolves, with no error.
        val resDir             = tempDir()
        val (tgtOs, tgtArch)   = ("darwin", "x86_64")
        val (hostOs, hostArch) = (CCompiler.detectOs(), CCompiler.detectArch())
        val src                = artifactNamed(s"libkyo_test-$tgtOs-$tgtArch.dylib", "cross-built")

        val dests = Packager.copyForJvm(
            artifacts = Seq(src),
            resDir = resDir,
            libraryId = "kyo_test",
            os = tgtOs,
            arch = tgtArch
        )

        dests should have length 1
        val dest = dests.head
        dest.getParentFile.getName shouldBe "darwin-x86_64"
        dest.getName shouldBe "libkyo_test.dylib"
        Files.readAllBytes(dest.toPath).toSeq shouldBe "cross-built".getBytes.toSeq
        // Nothing was written under the host's directory (unless the host IS the target).
        if (s"$hostOs-$hostArch" != s"$tgtOs-$tgtArch")
            new File(resDir, s"$hostOs-$hostArch").exists() shouldBe false
    }

    test("copyForJvm: linux-musl target keeps the two-segment os in both the directory and the strip") {
        // `linux-musl-x86_64` is the one tag whose os itself carries a hyphen; the suffix strip has
        // to consume all of it or the packaged name keeps a `-musl-x86_64` tail.
        val resDir = tempDir()
        val src    = artifactNamed("libkyo_test-linux-musl-x86_64.so", "musl")

        val dests = Packager.copyForJvm(
            artifacts = Seq(src),
            resDir = resDir,
            libraryId = "kyo_test",
            os = "linux-musl",
            arch = "x86_64"
        )

        dests should have length 1
        dests.head.getParentFile.getName shouldBe "linux-musl-x86_64"
        dests.head.getName shouldBe "libkyo_test.so"
    }

    test("copyForPlatformMulti: JVM arm packages every library under the same target directory") {
        val resDir = tempDir()
        val alpha  = artifactNamed("libalpha-linux-aarch64.so", "a")
        val beta   = artifactNamed("libbeta-linux-aarch64.so", "b")

        val dests = Packager.copyForPlatformMulti(
            platform = "JVM",
            libs = Seq("alpha" -> Seq(alpha), "beta" -> Seq(beta)),
            resDir = resDir,
            os = "linux",
            arch = "aarch64"
        )

        dests.map(_.getName) shouldBe Seq("libalpha.so", "libbeta.so")
        dests.map(_.getParentFile.getName).distinct shouldBe Seq("linux-aarch64")
    }

    // -------------------------------------------------------------------------
    // copyForJs: same target contract, different subtree
    // -------------------------------------------------------------------------

    test("copyForJs: bundles under kyo-ffi/native/<target os>-<target arch>") {
        // resDir is `<resourceManaged>/META-INF/native` in the plugin; copyForJs swaps the top two
        // segments, so build the same shape here.
        val resManaged       = tempDir()
        val resDir           = new File(resManaged, "META-INF/native")
        val (tgtOs, tgtArch) = foreignOsArch
        val ext              = CCompiler.libExtension(tgtOs)
        val src              = artifactNamed(s"libkyo_test-$tgtOs-$tgtArch.$ext", "js-cross")

        val dests = Packager.copyForJs(
            artifacts = Seq(src),
            resDir = resDir,
            libraryId = "kyo_test",
            os = tgtOs,
            arch = tgtArch
        )

        dests should have length 1
        dests.head.getName shouldBe s"libkyo_test.$ext"
        dests.head.getParentFile.getName shouldBe s"$tgtOs-$tgtArch"
        dests.head.getParentFile.getParentFile.getName shouldBe "native"
        dests.head.getParentFile.getParentFile.getParentFile.getName shouldBe "kyo-ffi"
    }

    // -------------------------------------------------------------------------
    // copyForJvm: artifact without platform suffix is copied unchanged
    // -------------------------------------------------------------------------

    test("copyForJvm: artifact without os-arch suffix is copied with original name") {
        val resDir = tempDir()
        val src    = Files.createTempFile("libkyo_plain-", ".so").toFile
        Files.write(src.toPath, "plain".getBytes)
        try {
            // No os-arch suffix in the name, canonicalName should leave it unchanged.
            val dests = Packager.copyForJvm(
                artifacts = Seq(src),
                resDir = resDir,
                libraryId = "kyo_plain",
                os = CCompiler.detectOs(),
                arch = CCompiler.detectArch()
            )

            dests should have length 1
            dests.head.getName shouldBe src.getName
        } finally {
            src.delete()
        }
    }
}
