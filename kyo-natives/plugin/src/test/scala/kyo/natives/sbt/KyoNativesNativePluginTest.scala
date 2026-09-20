package kyo.natives.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the cross-compilation guard.
  *
  * The libraries a Native build links are chosen from the compiler's own target rather than from `nativeConfig`, which
  * this plugin contributes to and so cannot read. A build that cross-compiles has to say its target twice, and these
  * pin what happens when the two disagree: linking one pole's libraries into another pole's binary is the failure the
  * guard exists to make loud.
  */
class KyoNativesNativePluginTest extends AnyFunSuite with Matchers {

    private def error(triple: Option[String], targets: Seq[String], delivering: Boolean = true): Option[String] =
        KyoNativesNativePlugin.crossTargetError(triple, targets, delivering)

    test("a host build, which sets no triple, has nothing to disagree about") {
        error(None, Seq("darwin-aarch64")) shouldBe None
    }

    test("a triple agreeing with the delivered target passes") {
        error(Some("arm64-apple-darwin23.3.0"), Seq("darwin-aarch64")) shouldBe None
        error(Some("x86_64-unknown-linux-musl"), Seq("linux-musl-x86_64")) shouldBe None
    }

    test("a triple naming another pole is named, with both values and the fix") {
        val message = error(Some("x86_64-unknown-linux-gnu"), Seq("darwin-aarch64")).getOrElse(fail("expected an error"))
        message should include("linux-x86_64")
        message should include("darwin-aarch64")
        message should include("kyoNativesTargets")
    }

    test("a triple kyo publishes nothing for fails while libraries are being delivered") {
        val message = error(Some("riscv64-unknown-linux-gnu"), Seq("darwin-aarch64")).getOrElse(fail("expected an error"))
        message should include("riscv64-unknown-linux-gnu")
        message should include("Disabled")
    }

    test("that same triple is fine when nothing is being delivered, since nothing can be mislinked") {
        error(Some("riscv64-unknown-linux-gnu"), Seq("darwin-aarch64"), delivering = false) shouldBe None
    }

    test("no resolved target means no claim about what would be linked") {
        error(Some("x86_64-unknown-linux-gnu"), Nil) shouldBe None
    }
}
