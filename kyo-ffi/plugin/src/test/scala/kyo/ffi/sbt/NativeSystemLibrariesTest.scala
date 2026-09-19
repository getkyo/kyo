package kyo.ffi.sbt

import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The published system-library declaration: what a Native artifact writes, what a consumer reads back from its jar, and which flags a
  * consumer's machine resolves it to. The probe is injected, so resolution is exercised without a C toolchain.
  */
class NativeSystemLibrariesTest extends AnyFunSuite with Matchers {

    private val openssl = FfiLibrary(
        id = "kyonet_openssl",
        cSources = Nil,
        system = Some(
            FfiSystemLibrary(
                headers = Seq("openssl/ssl.h"),
                linkLibs = Seq("ssl", "crypto"),
                prefixesByOs = Map("darwin" -> Seq("/opt/homebrew/opt/openssl@3", "/usr/local/opt/openssl@3"))
            )
        )
    )
    private val uring = FfiLibrary(
        id = "kyonet_posix_uring",
        cSources = Nil,
        system = Some(FfiSystemLibrary(headers = Seq("liburing.h"), linkLibsByOs = Map("linux" -> Seq("uring")), staticLink = true))
    )
    private val vendored = FfiLibrary(id = "kyonet_boringssl", cSources = Nil, linkLibs = Seq("ssl", "crypto"))

    test("render and parse round-trip every field, and skip a library that declares no system library") {
        val text = NativeSystemLibraries.render(Seq(openssl, uring, vendored)).get
        NativeSystemLibraries.parse(text) shouldBe Seq(
            NativeSystemLibraries.Declared("kyonet_openssl", openssl.system.get),
            NativeSystemLibraries.Declared("kyonet_posix_uring", uring.system.get)
        )
    }

    test("render is None when no library declares a system library") {
        NativeSystemLibraries.render(Seq(vendored)) shouldBe None
    }

    test("render is byte-identical for an unchanged declaration") {
        // The declaration is a packaged resource: a varying byte changes the jar and nativeLink's input hash on every build.
        NativeSystemLibraries.render(Seq(openssl, uring)) shouldBe NativeSystemLibraries.render(Seq(openssl, uring))
    }

    test("render refuses a value the comma-separated format would split") {
        val bad = openssl.copy(system = Some(FfiSystemLibrary(headers = Seq("a,b.h"), linkLibs = Seq("x"))))
        an[RuntimeException] should be thrownBy NativeSystemLibraries.render(Seq(bad))
    }

    private def declared(lib: FfiLibrary) = NativeSystemLibraries.Declared(lib.id, lib.system.get)

    test("resolve prefers the compiler's default paths") {
        val resolved = NativeSystemLibraries.resolve(declared(openssl), "darwin", _ => true)
        resolved shouldBe Some(
            NativeSystemLibraries.Resolved("kyonet_openssl", Seq("-DKYO_FFI_LINKED_KYONET_OPENSSL"), Seq("-lssl", "-lcrypto"))
        )
    }

    test("resolve falls back to the first declared prefix that links") {
        // Homebrew's OpenSSL is keg-only: the defaults find nothing, and the second prefix is where it is installed.
        val probe: Seq[String] => Boolean = args => args.contains("-I/usr/local/opt/openssl@3/include")
        NativeSystemLibraries.resolve(declared(openssl), "darwin", probe) shouldBe Some(
            NativeSystemLibraries.Resolved(
                "kyonet_openssl",
                Seq("-DKYO_FFI_LINKED_KYONET_OPENSSL", "-I/usr/local/opt/openssl@3/include"),
                Seq("-L/usr/local/opt/openssl@3/lib", "-lssl", "-lcrypto")
            )
        )
    }

    test("resolve is None when nothing links, so the shim compiles its stubs") {
        NativeSystemLibraries.resolve(declared(openssl), "darwin", _ => false) shouldBe None
    }

    test("resolve probes with exactly the flags it would emit") {
        var seen = Seq.empty[Seq[String]]
        NativeSystemLibraries.resolve(declared(uring), "linux", args => { seen :+= args; true })
        seen shouldBe Seq(Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic"))
    }

    test("resolve links statically on linux and plainly on darwin, which has no -Bstatic") {
        NativeSystemLibraries.linkLibFlags(Seq("uring"), static = true, "linux-musl") shouldBe Seq("-Wl,-Bstatic", "-luring", "-Wl,-Bdynamic")
        NativeSystemLibraries.linkLibFlags(Seq("uring"), static = true, "darwin") shouldBe Seq("-luring")
    }

    test("resolve is None on an OS the library declares nothing to link for, without probing") {
        var probed = false
        NativeSystemLibraries.resolve(declared(uring), "darwin", _ => { probed = true; true }) shouldBe None
        probed shouldBe false
    }

    test("readJars reads the declaration a published jar carries, and ignores classpath directories") {
        val tmp = Files.createTempDirectory("kyo-ffi-decl").toFile
        val jar = new File(tmp, "kyo-net_native0.5_3.jar")
        val out = new ZipOutputStream(new FileOutputStream(jar))
        try {
            out.putNextEntry(new ZipEntry("META-INF/kyo-ffi/native-system-libraries/kyo-net.properties"))
            out.write(NativeSystemLibraries.render(Seq(openssl, uring)).get.getBytes(StandardCharsets.UTF_8))
            out.closeEntry()
            out.putNextEntry(new ZipEntry("scala-native/kyo_uring.c"))
            out.closeEntry()
        } finally out.close()
        // A module built in the same build is a directory and hands over its exact flags instead; a declaration there is not read.
        val dir = new File(tmp, "classes")
        val inDir = new File(dir, "META-INF/kyo-ffi/native-system-libraries")
        inDir.mkdirs()
        Files.write(new File(inDir, "other.properties").toPath, "libraries = other\nother.headers = x.h\nother.libs = x\n".getBytes)
        NativeSystemLibraries.readJars(Seq(jar, dir)).map(_.id) shouldBe Seq("kyonet_openssl", "kyonet_posix_uring")
    }
}
