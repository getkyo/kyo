package kyo.ffi.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the NPM bundle `package.json` template emitter.
  *
  * Asserts the pinned koffi range appears verbatim in the generated JSON, that
  * the emitter is idempotent (no overwrite unless asked), and that the range
  * constant stays in lockstep with the runtime contract documented on
  * `kyo.ffi.internal.FfiErrors.KoffiSupportedRange`.
  */
class NpmBundleTemplateTest extends AnyFunSuite with Matchers {

    private def tmp(prefix: String): File = {
        val d = Files.createTempDirectory(s"kyo-ffi-npm-$prefix").toFile
        d.deleteOnExit()
        d
    }

    private def readUtf8(f: File): String =
        new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8)

    test("KoffiSupportedRange is ^2.7 (locked contract)") {
        NpmBundleTemplate.KoffiSupportedRange shouldBe "^2.7"
    }

    test("packageJson embeds the library name, sets private, pins koffi to ^2.7") {
        val json = NpmBundleTemplate.packageJson("my_bundle")
        json should include(""""name": "my_bundle"""")
        json should include(""""private": true""")
        json should include(""""koffi": "^2.7"""")
    }

    test("packageJson output is valid JSON-shaped text (basic structural check)") {
        val json = NpmBundleTemplate.packageJson("x")
        json.trim.startsWith("{") shouldBe true
        json.trim.endsWith("}") shouldBe true
        json.count(_ == '{') shouldBe json.count(_ == '}')
    }

    test("writeTemplate creates the file with the pinned range when absent") {
        val dir = tmp("write")
        val pj  = new File(dir, "package.json")
        NpmBundleTemplate.writeTemplate(pj, "lib_a")
        pj.exists() shouldBe true
        val content = readUtf8(pj)
        content should include(""""koffi": "^2.7"""")
        content should include(""""name": "lib_a"""")
    }

    test("writeTemplate does not overwrite an existing file by default (user customization wins)") {
        val dir    = tmp("no-overwrite")
        val pj     = new File(dir, "package.json")
        val custom = """{"name":"user-wrote-this","private":true,"dependencies":{"koffi":"2.7.9","other":"1.0.0"}}"""
        Files.write(pj.toPath, custom.getBytes(StandardCharsets.UTF_8))
        NpmBundleTemplate.writeTemplate(pj, "lib_b")
        readUtf8(pj) shouldBe custom
    }

    test("writeTemplate overwrites when overwrite=true") {
        val dir = tmp("overwrite")
        val pj  = new File(dir, "package.json")
        Files.write(pj.toPath, """{"koffi":"1.0.0"}""".getBytes(StandardCharsets.UTF_8))
        NpmBundleTemplate.writeTemplate(pj, "lib_c", overwrite = true)
        val content = readUtf8(pj)
        content should include(""""koffi": "^2.7"""")
        content should include(""""name": "lib_c"""")
        content should not include "1.0.0"
    }

    test("writeTemplate creates parent directory if missing") {
        val dir  = tmp("parent")
        val deep = new File(new File(dir, "a"), "b")
        val pj   = new File(deep, "package.json")
        NpmBundleTemplate.writeTemplate(pj, "lib_d")
        pj.exists() shouldBe true
    }
}
