package kyo.ffi.sbt

import java.io.File
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the packaging check's format reader.
  *
  * The failure it exists for shipped once: a macOS binding was published as `libmachine_macos.so` under
  * `META-INF/native/linux-x86_64/`, and no darwin artifact was published at all. The directory, the file name
  * and the extension were all self-consistent there, so only the bytes inside distinguish a correctly filed
  * artifact from a mis-filed one.
  */
class NativeArtifactFormatTest extends AnyFunSuite with Matchers {

    private def fileWith(name: String, magic: Array[Int]): File = {
        val dir = Files.createTempDirectory("kyo-ffi-format").toFile
        dir.deleteOnExit()
        val f = new File(dir, name)
        Files.write(f.toPath, (magic.map(_.toByte) ++ Array.fill[Byte](64)(0)))
        f.deleteOnExit()
        f
    }

    private val elfMagic   = Array(0x7f, 'E'.toInt, 'L'.toInt, 'F'.toInt)
    private val machoMagic = Array(0xcf, 0xfa, 0xed, 0xfe)
    private val fatMagic   = Array(0xca, 0xfe, 0xba, 0xbe)
    private val peMagic    = Array('M'.toInt, 'Z'.toInt, 0x90, 0x00)

    test("reads the object format from the leading bytes") {
        NativeArtifactFormat.of(fileWith("libx.so", elfMagic)) shouldBe NativeArtifactFormat.Elf
        NativeArtifactFormat.of(fileWith("libx.dylib", machoMagic)) shouldBe NativeArtifactFormat.MachO
        NativeArtifactFormat.of(fileWith("libx.dylib", fatMagic)) shouldBe NativeArtifactFormat.MachO
        NativeArtifactFormat.of(fileWith("x.dll", peMagic)) shouldBe NativeArtifactFormat.Pe
        NativeArtifactFormat.of(fileWith("libx.so", Array(0, 0, 0, 0))) shouldBe NativeArtifactFormat.Unknown
    }

    test("a correctly filed artifact reports no mismatch") {
        NativeArtifactFormat.mismatch("linux-x86_64", fileWith("libx.so", elfMagic)) shouldBe None
        NativeArtifactFormat.mismatch("linux-musl-aarch64", fileWith("libx.so", elfMagic)) shouldBe None
        NativeArtifactFormat.mismatch("darwin-aarch64", fileWith("libx.dylib", machoMagic)) shouldBe None
        NativeArtifactFormat.mismatch("windows-x86_64", fileWith("x.dll", peMagic)) shouldBe None
    }

    test("a Mach-O object under a linux key is a mismatch") {
        val m = NativeArtifactFormat.mismatch("linux-x86_64", fileWith("libmachine_macos.so", machoMagic))
        m.isDefined shouldBe true
        m.get should include("Mach-O")
        m.get should include("linux-x86_64")
    }

    test("an ELF object under a darwin key is a mismatch even when the extension is right") {
        val m = NativeArtifactFormat.mismatch("darwin-aarch64", fileWith("libmachine_macos.dylib", elfMagic))
        m.isDefined shouldBe true
        m.get should include("ELF")
    }

    test("a wrong extension is a mismatch before the bytes are even read") {
        val m = NativeArtifactFormat.mismatch("darwin-aarch64", fileWith("libmachine_macos.so", machoMagic))
        m.isDefined shouldBe true
        m.get should include("'so'")
        m.get should include("'dylib'")
    }

    test("an unrecognized platform key asserts nothing") {
        NativeArtifactFormat.expectedFormat("solaris-sparc") shouldBe None
        NativeArtifactFormat.expectedExtension("solaris-sparc") shouldBe None
        NativeArtifactFormat.mismatch("solaris-sparc", fileWith("libx.so", elfMagic)) shouldBe None
    }

    test("library id is read back off the canonical artifact name") {
        NativeArtifactFormat.libraryIdOf("libmachine_macos.dylib") shouldBe Some("machine_macos")
        NativeArtifactFormat.libraryIdOf("libkyonet_boringssl.so") shouldBe Some("kyonet_boringssl")
        NativeArtifactFormat.libraryIdOf("kyo_tcp.dll") shouldBe Some("kyo_tcp")
        NativeArtifactFormat.libraryIdOf("noextension") shouldBe None
    }
}

/** The shared-pool staging rule.
  *
  * `ffiPrebuiltDir` is strict on purpose: a native for an id the project does not declare, in a directory
  * pointed at that project deliberately, is a mistake worth failing on. `ffiPrebuiltPool` is the opposite
  * situation, a directory several FFI modules share, where another module's native is the normal case. The
  * two rules have to differ, and this pins that they do.
  */
class FfiPrebuiltPoolTest extends AnyFunSuite with Matchers {

    private def idOf(name: String): Option[String] = CCompiler.parseArtifactName(name).map(_._1)

    /** What the pool staging does: keep the files whose declared id this project owns. */
    private def taken(pool: Seq[String], declared: Set[String]): Seq[String] =
        pool.filter(n => idOf(n).exists(declared.contains))

    private val pool = Seq(
        "libmachine_macos-darwin-aarch64.dylib",
        "libmachine_macos-darwin-x86_64.dylib",
        "libkyo_net-darwin-aarch64.dylib",
        "libkyo_boringssl-darwin-aarch64.dylib"
    )

    test("a pooled native is attributed to the library id its filename declares") {
        idOf("libmachine_macos-darwin-aarch64.dylib") shouldBe Some("machine_macos")
        idOf("libkyo_net-darwin-aarch64.dylib") shouldBe Some("kyo_net")
    }

    test("a project takes its own natives out of a shared pool and leaves the others") {
        taken(pool, Set("machine_macos")) shouldBe Seq(
            "libmachine_macos-darwin-aarch64.dylib",
            "libmachine_macos-darwin-x86_64.dylib"
        )
        taken(pool, Set("kyo_net", "kyo_boringssl")) should have size 2
    }

    test("a project whose natives no producer built takes nothing, rather than failing on another module's") {
        // This is the whole reason the pool cannot reuse ffiPrebuiltDir's rule: on a Linux producer the
        // darwin-only shim is absent and every file in the pool belongs to someone else.
        taken(pool, Set("not_built_here")) shouldBe empty
    }

    test("a name that does not parse is still not attributed to anyone, so a typo cannot hide in the pool") {
        idOf("machine_macos.dylib") shouldBe None
        idOf("libmachine_macos-darwin.dylib") shouldBe None
        taken(Seq("machine_macos.dylib"), Set("machine_macos")) shouldBe empty
    }
}
