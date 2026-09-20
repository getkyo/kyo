package kyo.ffi.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the declaration a consumer's build reads to find the artifact carrying a shared library.
  *
  * The round trip matters more than either half: the writer runs in kyo's build and the reader in someone else's, so a
  * disagreement between them surfaces as an application that resolves nothing, with no error naming the cause.
  */
class NativeDeliveryTest extends AnyFunSuite with Matchers {

    test("render and parse round-trip a sliced module") {
        val delivery = Map(
            "kyonet_boringssl"   -> NativeDelivery.Entry("<os-arch>-boringssl"),
            "kyonet_posix_uring" -> NativeDelivery.Entry("<os-arch>", Set("jvm", "js"))
        )
        val parsed = NativeDelivery.parse(NativeDelivery.render(delivery).mkString("\n"))
        parsed.map(d => d.id -> NativeDelivery.Entry(d.classifierPattern, d.platforms)).toMap shouldBe delivery
    }

    test("render and parse round-trip a module keeping its natives in the main artifact") {
        val parsed = NativeDelivery.parse(NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.Entry(""))).mkString("\n"))
        parsed shouldBe Seq(NativeDelivery.Declared("kyo_aeron", "", NativeDelivery.allPlatforms))
    }

    test("render emits nothing for a module that delivers no library") {
        NativeDelivery.render(Map.empty) shouldBe Nil
    }

    test("render orders ids so an unchanged declaration is byte-identical") {
        val one = NativeDelivery.render(Map("b" -> NativeDelivery.Entry(""), "a" -> NativeDelivery.Entry("")))
        val two = NativeDelivery.render(Map("a" -> NativeDelivery.Entry(""), "b" -> NativeDelivery.Entry("")))
        one shouldBe two
        one.head shouldBe "libraries = a, b"
    }

    test("render omits the platform line for a library every platform takes") {
        NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.Entry(""))).exists(_.contains("platforms")) shouldBe false
        NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.Entry("", Set("jvm")))) should contain("kyo_aeron.platforms = jvm")
    }

    test("render rejects a platform that is not one kyo publishes for") {
        val thrown = intercept[RuntimeException] {
            NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.Entry("", Set("wasm"))))
        }
        thrown.getMessage should include("wasm")
    }

    test("an empty classifier names the main artifact and a pattern is substituted") {
        NativeDelivery.Declared("kyo_aeron", "").classifier("darwin-aarch64") shouldBe None
        NativeDelivery.Declared("kyonet_boringssl", "<os-arch>-boringssl").classifier("linux-x86_64") shouldBe
            Some("linux-x86_64-boringssl")
        NativeDelivery.Declared("kyonet_posix_uring", "<os-arch>").classifier("linux-musl-aarch64") shouldBe
            Some("linux-musl-aarch64")
    }

    test("a narrowed declaration delivers only to the platforms it names") {
        val uring = NativeDelivery.Declared("kyonet_posix_uring", "<os-arch>", Set("jvm", "js"))
        uring.deliversTo("jvm") shouldBe true
        uring.deliversTo("js") shouldBe true
        uring.deliversTo("native") shouldBe false
    }

    test("parse tolerates a declaration naming a library with no classifier line") {
        NativeDelivery.parse("libraries = kyo_aeron\n") shouldBe Seq(NativeDelivery.Declared("kyo_aeron", ""))
    }

    test("parse of a declaration with no platform line delivers everywhere") {
        NativeDelivery.parse("libraries = kyo_aeron\n").head.platforms shouldBe NativeDelivery.allPlatforms
    }

    test("parse of an unrelated properties file yields nothing") {
        NativeDelivery.parse("something = else\n") shouldBe Nil
    }

    test("jvmArtifactName strips the platform infix and leaves a JVM name alone") {
        NativeDelivery.jvmArtifactName("kyo-net_native0.5_3") shouldBe "kyo-net_3"
        NativeDelivery.jvmArtifactName("kyo-net_sjs1_3") shouldBe "kyo-net_3"
        NativeDelivery.jvmArtifactName("kyo-net_3") shouldBe "kyo-net_3"
        NativeDelivery.jvmArtifactName("kyo-sql-doltlite_native0.5_2.13") shouldBe "kyo-sql-doltlite_2.13"
    }

    test("jvmArtifactName does not mistake a module name containing 'native' for the infix") {
        NativeDelivery.jvmArtifactName("kyo-native-tools_3") shouldBe "kyo-native-tools_3"
    }
}
