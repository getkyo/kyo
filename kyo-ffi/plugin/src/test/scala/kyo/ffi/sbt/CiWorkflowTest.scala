package kyo.ffi.sbt

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class CiWorkflowTest extends AnyFunSuite with Matchers {
    test("Darwin targets use a supported ARM64 runner and retain macOS 14 compatibility") {
        val out = Files.createTempFile("kyo-ffi-workflow-", ".yml")
        try {
            CiWorkflow.writeTemplate(out.toFile, "test_library")
            val yaml       = new String(Files.readAllBytes(out), StandardCharsets.UTF_8)
            val darwinRows = yaml.split("\n").filter(_.contains("- { os: macos-")).map(_.trim).toSeq
            darwinRows shouldBe Seq(
                "- { os: macos-15,         target: darwin-aarch64, cc: clang, cflags: 'Nil' }",
                "- { os: macos-15,         target: darwin-x86_64,  cc: clang, cflags: 'Seq(\"-arch\", \"x86_64\")' }"
            )
            yaml should include("MACOSX_DEPLOYMENT_TARGET: '14.0'")
        } finally Files.deleteIfExists(out)
    }
}
