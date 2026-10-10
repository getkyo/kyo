package kyo.natives.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the cross-compilation guard.
  *
  * A build states the target its binary is for in two places, `nativeConfig.targetTriple` and `kyoNativesTargets`, and
  * the delivery reads only the second. These pin every way the two can disagree, because each of them ends with
  * another pole's libraries linked into the binary: a file-format error from the linker when the architectures differ,
  * and a binary that loads and crashes when they do not, as glibc and musl do.
  */
class KyoNativesNativePluginTest extends AnyFunSuite with Matchers {

    private val darwin = Some("darwin-aarch64")

    private def error(
        triple: Option[String],
        compilerTarget: Option[String] = darwin,
        wanted: Option[String] = Some("darwin-aarch64"),
        requested: Boolean = true,
        named: Boolean = true
    ): Option[String] =
        KyoNativesNativePlugin.crossTargetError(triple, compilerTarget, wanted, requested, named)

    test("a host build, which sets no triple and takes the compiler's target, agrees with itself") {
        error(triple = None) shouldBe None
    }

    test("a triple agreeing with the wanted target passes") {
        error(Some("arm64-apple-darwin23.3.0")) shouldBe None
        error(
            Some("x86_64-unknown-linux-musl"),
            compilerTarget = Some("linux-musl-x86_64"),
            wanted = Some("linux-musl-x86_64")
        ) shouldBe None
    }

    test("a triple naming another pole is named, with both values and the fix") {
        val message = error(Some("x86_64-unknown-linux-gnu")).getOrElse(fail("expected an error"))
        message should include("linux-x86_64")
        message should include("darwin-aarch64")
        message should include("kyoNativesTargets")
    }

    test("a triple kyo publishes nothing for fails, since the delivery matched the compiler instead") {
        val message = error(Some("riscv64-unknown-linux-gnu")).getOrElse(fail("expected an error"))
        message should include("riscv64-unknown-linux-gnu")
        message should include("Disabled")
    }

    test("with no triple, a kyoNativesTargets the compiler does not build for is the same mistake") {
        val message = error(triple = None, wanted = Some("linux-x86_64")).getOrElse(fail("expected an error"))
        message should include("linux-x86_64")
        message should include("darwin-aarch64")
        message should include("targetTriple")
    }

    test("a build whose dependencies declare no library can mislink nothing, whatever the targets say") {
        error(Some("riscv64-unknown-linux-gnu"), requested = false) shouldBe None
        error(triple = None, wanted = Some("linux-x86_64"), requested = false) shouldBe None
    }

    test("a target nobody named is not blamed on the setting that did not name it") {
        // A withClang pointing at another toolchain reaches this with the target derived from the clang on the PATH.
        // Telling that build to change kyoNativesTargets sends it looking at a setting it never wrote.
        val message = error(triple = None, wanted = Some("linux-x86_64"), named = false).getOrElse(fail("expected an error"))
        message should not include "kyoNativesTargets names"
        message should include("derived from the clang on the PATH")
        message should include("linux-x86_64")
        message should include("darwin-aarch64")
    }

    test("no resolved target means no claim about what would be linked") {
        error(Some("x86_64-unknown-linux-gnu"), wanted = None) shouldBe None
    }

    test("an unknown compiler target makes no claim, rather than a wrong one") {
        error(triple = None, compilerTarget = None, wanted = Some("linux-x86_64")) shouldBe None
    }

    private def probeError(
        triple: Option[String],
        wanted: Option[String] = Some("darwin-aarch64"),
        compilerTarget: Option[String] = darwin,
        options: Seq[String] = Seq("-I/opt/homebrew/include", "-L/opt/homebrew/lib"),
        resolved: Seq[String] = Seq("kyonet_openssl"),
        ownSysroot: Option[String] = Some("/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk")
    ): Option[String] =
        KyoNativesNativePlugin.systemProbeError(
            resolved,
            triple,
            wanted,
            compilerTarget,
            darwin,
            options,
            path => KyoNativesNativePlugin.sameSysroot(path, ownSysroot)
        )

    test("system libraries probed for the host and linked for the host pass") {
        probeError(triple = None) shouldBe None
        probeError(Some("arm64-apple-darwin23.3.0")) shouldBe None
    }

    test("system libraries probed for the host refuse a link for another target triple") {
        // The probe linked the host's libssl; an aarch64-linux link would carry its define and -lssl into a binary the
        // probe never answered for.
        val message = probeError(Some("aarch64-unknown-linux-gnu"), wanted = Some("linux-aarch64")).getOrElse(fail("expected an error"))
        message should include("kyonet_openssl")
        message should include("aarch64-unknown-linux-gnu")
        message should include("darwin-aarch64")
        message should include("NativesSource.Disabled")
    }

    test("system libraries probed for the host refuse a target triple kyo publishes nothing for") {
        probeError(Some("riscv64-unknown-linux-gnu")) shouldBe defined
    }

    test("system libraries probed for the host refuse a link through a compiler for another target") {
        val message = probeError(triple = None, compilerTarget = Some("linux-aarch64")).getOrElse(fail("expected an error"))
        message should include("a compiler for linux-aarch64")
        message should include("darwin-aarch64")
    }

    test("another path to a compiler for the same target is the same compiler, and passes") {
        // A withClang naming /usr/bin/clang where the PATH clang is /usr/bin/clang-18 builds for the same target.
        probeError(triple = None, compilerTarget = darwin) shouldBe None
    }

    test("system libraries probed for the host refuse libraries wanted for another target") {
        probeError(triple = None, wanted = Some("linux-x86_64")).getOrElse(fail("expected an error")) should include("linux-x86_64")
    }

    test("system libraries probed for the host refuse a link against a sysroot, whose triple is unchanged") {
        // Building for an older distribution of the same architecture: the probe linked the host's libssl, and the
        // sysroot holds another version or none.
        Seq(
            Seq("--sysroot", "/sysroots/x86_64-glibc-2.17"),
            Seq("--sysroot=/sysroots/x86_64-glibc-2.17"),
            Seq("-isysroot", "/sdk"),
            Seq("-target", "x86_64-apple-darwin"),
            Seq("--target=x86_64-apple-darwin"),
            Seq("-Wl,--sysroot=/sysroots/x86_64-glibc-2.17")
        ).foreach { flags =>
            val message = probeError(triple = None, options = Seq("-O2") ++ flags).getOrElse(fail(s"expected an error for $flags"))
            message should include(flags.head.takeWhile(_ != '='))
        }
    }

    test("a sysroot or target naming the probe's own system passes, as a Homebrew mac repairing its SDK headers does") {
        Seq(
            Seq("-isysroot", "/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk"),
            Seq("-isysroot=/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk"),
            Seq("--sysroot=/"),
            Seq("-Wl,--sysroot=/"),
            Seq("-target", "arm64-apple-macos11"),
            Seq("--target=arm64-apple-darwin23.3.0")
        ).foreach { flags =>
            probeError(triple = None, options = Seq("-I/opt/homebrew/opt/openssl@3/include") ++ flags) shouldBe None
        }
    }

    test("a sysroot is the compiler's own by real path, so a symlink to it is the same system") {
        val dir = java.nio.file.Files.createTempDirectory("kyo-natives-sysroot")
        try {
            val versioned = java.nio.file.Files.createDirectory(dir.resolve("MacOSX14.4.sdk"))
            val link      = java.nio.file.Files.createSymbolicLink(dir.resolve("MacOSX.sdk"), versioned)
            probeError(triple = None, options = Seq("-isysroot", link.toString), ownSysroot = Some(versioned.toString)) shouldBe None
            probeError(triple = None, options = Seq("-isysroot", dir.toString), ownSysroot = Some(versioned.toString)) shouldBe defined
        } finally sbt.IO.delete(dir.toFile)
    }

    test("with no sysroot of its own, the compiler's system is the host root and any other sysroot is refused") {
        probeError(triple = None, options = Seq("--sysroot=/"), ownSysroot = None) shouldBe None
        probeError(triple = None, options = Seq("-isysroot", "/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk"), ownSysroot = None)
            .getOrElse(fail("expected an error")) should include("-isysroot /Library/Developer/CommandLineTools/SDKs/MacOSX.sdk")
    }

    test("the compiler's sysroot is the -isysroot its dry run hands -cc1, and none means the host root") {
        val darwinDryRun =
            """ "/usr/bin/clang" "-cc1" "-triple" "arm64-apple-macosx14.0.0" "-isysroot" "/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk" "-x" "c" "-""""
        KyoNativesNativePlugin.sysrootOfDryRun(darwinDryRun) shouldBe Some("/Library/Developer/CommandLineTools/SDKs/MacOSX.sdk")
        KyoNativesNativePlugin.sysrootOfDryRun(""" "/usr/bin/clang-18" "-cc1" "-triple" "x86_64-pc-linux-gnu" "-x" "c" "-"""") shouldBe None
    }

    test("this machine's compiler accepts its own system: the SDK xcrun selects on darwin, the root elsewhere") {
        val clang = scala.scalanative.build.Discover.clang().toString
        val own   = KyoNativesNativePlugin.compilerSysroot(clang)
        val sdk   =
            if (sys.props("os.name").toLowerCase.contains("mac")) scala.sys.process.Process(Seq("xcrun", "--show-sdk-path")).!!.trim
            else "/"
        KyoNativesNativePlugin.sameSysroot(sdk, own) shouldBe true
    }

    test("nothing resolved means nothing to refuse, whatever the link targets") {
        probeError(
            Some("aarch64-unknown-linux-gnu"),
            compilerTarget = Some("linux-aarch64"),
            options = Seq("--sysroot=/x"),
            resolved = Nil
        ) shouldBe None
    }

    test("a link config carrying every folded define passes") {
        KyoNativesNativePlugin.foldError(
            Seq("-O2", "-DKYO_FFI_EXTERNAL_KYONET_BORINGSSL"),
            Seq("KYO_FFI_EXTERNAL_KYONET_BORINGSSL")
        ) shouldBe None
        KyoNativesNativePlugin.foldError(Seq("-O2"), Nil) shouldBe None
    }

    test("a link config that lost a folded define names it and both ways out") {
        val message = KyoNativesNativePlugin
            .foldError(Seq("-O2"), Seq("KYO_FFI_EXTERNAL_KYONET_BORINGSSL", "KYO_FFI_LINKED_KYONET_OPENSSL"))
            .getOrElse(fail("expected an error"))
        message should include("KYO_FFI_EXTERNAL_KYONET_BORINGSSL")
        message should include("KYO_FFI_LINKED_KYONET_OPENSSL")
        message should include("nativeConfig ~=")
        message should include("NativesSource.Disabled")
    }

    test("the probed prefix's headers and libraries precede Scala Native's default search directories") {
        val base = scala.scalanative.build.NativeConfig.empty
            .withCompileOptions(Seq("-I/opt/homebrew/include", "-Qunused-arguments"))
            .withLinkingOptions(Seq("-L/opt/homebrew/lib"))
        val prefix = "/opt/homebrew/opt/openssl@3"
        val folded = KyoNativesNativePlugin.withLibraries(
            base,
            Seq((Seq("-DKYO_FFI_LINKED_KYONET_OPENSSL", s"-I$prefix/include"), Seq(s"-L$prefix/lib", "-lssl", "-lcrypto")))
        )
        folded.compileOptions shouldBe Seq(
            "-DKYO_FFI_LINKED_KYONET_OPENSSL",
            s"-I$prefix/include",
            "-I/opt/homebrew/include",
            "-Qunused-arguments"
        )
        // The -l flags stay after everything the defaults name, so the objects that call them come before them.
        folded.linkingOptions shouldBe Seq(s"-L$prefix/lib", "-L/opt/homebrew/lib", "-lssl", "-lcrypto")
    }

    test("a Windows target delivers nothing, because a DLL with no import library cannot be linked") {
        val why = KyoNativesNativePlugin.undeliverable("windows-x86_64").getOrElse(fail("expected a reason"))
        why should include("windows-x86_64")
        why should include("import library")
        KyoNativesNativePlugin.undeliverable("windows-aarch64") shouldBe defined
    }

    test("every posix target delivers") {
        Seq("darwin-aarch64", "darwin-x86_64", "linux-x86_64", "linux-aarch64", "linux-musl-x86_64", "linux-musl-aarch64")
            .foreach(target => KyoNativesNativePlugin.undeliverable(target) shouldBe None)
    }

    test("a target kyo publishes nothing for is left to the check that names the supported set") {
        KyoNativesNativePlugin.undeliverable("solaris-sparc") shouldBe None
    }
}
