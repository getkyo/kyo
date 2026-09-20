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
