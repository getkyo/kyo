package kyo.ffi.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CodegenBridgeTest extends AnyFunSuite with Matchers {

    test("javaRuntimeShortfall: a JVM at or above the codegen's release has none") {
        CodegenBridge.javaRuntimeShortfall(17, "17") shouldBe None
        CodegenBridge.javaRuntimeShortfall(17, "21") shouldBe None
        CodegenBridge.javaRuntimeShortfall(25, "25") shouldBe None
    }

    test("javaRuntimeShortfall: a JVM below it is told the release it needs and the one it runs") {
        CodegenBridge.javaRuntimeShortfall(25, "21") shouldBe
            Some("kyo-ffi-plugin needs sbt to run on JDK 25 or newer; this sbt runs on JDK 21")
        CodegenBridge.javaRuntimeShortfall(17, "1.8") shouldBe
            Some("kyo-ffi-plugin needs sbt to run on JDK 17 or newer; this sbt runs on JDK 1.8")
    }

    test("javaRuntimeShortfall: an unreadable version is a shortfall rather than a pass") {
        CodegenBridge.javaRuntimeShortfall(17, "unknown") shouldBe
            Some("kyo-ffi-plugin needs sbt to run on JDK 17 or newer; this sbt runs on JDK unknown")
    }
}
