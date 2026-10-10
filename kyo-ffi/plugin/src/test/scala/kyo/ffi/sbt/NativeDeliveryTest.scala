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
            "kyonet_boringssl"   -> NativeDelivery.underClassifier("<os-arch>-boringssl", NativeDelivery.allPlatforms),
            "kyonet_posix_uring" -> NativeDelivery.underClassifier("<os-arch>")
        )
        NativeDelivery.parse(NativeDelivery.render(delivery).mkString("\n")) shouldBe delivery
    }

    test("render and parse round-trip a module keeping its natives in the main artifact") {
        val delivery = Map("kyo_aeron" -> NativeDelivery.mainArtifact())
        NativeDelivery.parse(NativeDelivery.render(delivery).mkString("\n")) shouldBe delivery
    }

    test("Native is not a default: a library gets there only where the module opts in") {
        NativeDelivery.defaultPlatforms should not contain DeliveryPlatform.Native
        NativeDelivery.mainArtifact().deliversTo(DeliveryPlatform.Native) shouldBe false
        NativeDelivery.mainArtifact(NativeDelivery.allPlatforms).deliversTo(DeliveryPlatform.Native) shouldBe true
    }

    test("render emits nothing for a module that delivers no library") {
        NativeDelivery.render(Map.empty) shouldBe Nil
    }

    test("render orders ids so an unchanged declaration is byte-identical") {
        val one = NativeDelivery.render(Map("b" -> NativeDelivery.mainArtifact(), "a" -> NativeDelivery.mainArtifact()))
        val two = NativeDelivery.render(Map("a" -> NativeDelivery.mainArtifact(), "b" -> NativeDelivery.mainArtifact()))
        one shouldBe two
        one.head shouldBe "libraries = a, b"
    }

    test("render omits the platform line where it matches the default") {
        NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.mainArtifact())).exists(_.contains("platforms")) shouldBe false
        NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.mainArtifact(Set(DeliveryPlatform.Jvm)))) should
            contain("kyo_aeron.platforms = jvm")
        NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.mainArtifact(NativeDelivery.allPlatforms))) should
            contain("kyo_aeron.platforms = js, jvm, native")
    }

    test("render rejects an entry scoped to no platform, which would read back as the default") {
        val thrown = intercept[RuntimeException] {
            NativeDelivery.render(Map("kyo_aeron" -> NativeDelivery.mainArtifact(Set.empty)))
        }
        thrown.getMessage should include("kyo_aeron")
    }

    test("render rejects a classifier pattern that would not survive the round trip") {
        def reject(pattern: String): String =
            intercept[RuntimeException] {
                NativeDelivery.render(Map("kyonet_boringssl" -> NativeDelivery.underClassifier(pattern)))
            }.getMessage
        reject("<os-arch>\n-boringssl") should include("line break")
        reject(" <os-arch>-boringssl") should include("whitespace")
        reject("<os-arch>-boringssl ") should include("whitespace")
        reject("""<os-arch>\boringssl""") should include("backslash")
    }

    test("render keeps a comma, which the reader takes as part of the value rather than a separator") {
        val delivery = Map("kyonet_boringssl" -> NativeDelivery.underClassifier("<os-arch>,boringssl"))
        NativeDelivery.parse(NativeDelivery.render(delivery).mkString("\n")) shouldBe delivery
    }

    test("the main artifact has no classifier and a pattern is substituted") {
        NativeDelivery.mainArtifact().classifier("darwin-aarch64") shouldBe None
        NativeDelivery.underClassifier("<os-arch>-boringssl").classifier("linux-x86_64") shouldBe
            Some("linux-x86_64-boringssl")
        NativeDelivery.underClassifier("<os-arch>").classifier("linux-musl-aarch64") shouldBe
            Some("linux-musl-aarch64")
    }

    test("a narrowed declaration delivers only to the platforms it names") {
        val uring = NativeDelivery.underClassifier("<os-arch>", Set(DeliveryPlatform.Jvm, DeliveryPlatform.Js))
        uring.deliversTo(DeliveryPlatform.Jvm) shouldBe true
        uring.deliversTo(DeliveryPlatform.Js) shouldBe true
        uring.deliversTo(DeliveryPlatform.Native) shouldBe false
    }

    test("parse takes a library with no classifier line as the main artifact, on the default platforms") {
        NativeDelivery.parse("libraries = kyo_aeron\n") shouldBe Map("kyo_aeron" -> NativeDelivery.mainArtifact())
    }

    test("parse drops a platform it does not know, so a newer declaration still delivers what this one understands") {
        NativeDelivery.parse("libraries = kyo_aeron\nkyo_aeron.platforms = jvm, wasm\n") shouldBe
            Map("kyo_aeron" -> NativeDelivery.mainArtifact(Set(DeliveryPlatform.Jvm)))
    }

    test("parse drops a library left with no platform at all, which is the same as no entry") {
        NativeDelivery.parse("libraries = kyo_aeron\nkyo_aeron.platforms = wasm\n") shouldBe Map.empty
    }

    test("parse of an unrelated properties file yields nothing") {
        NativeDelivery.parse("something = else\n") shouldBe Map.empty
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
