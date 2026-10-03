package kyo.doctest.sbt

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class LineCitationsTest extends AnyFunSuite with Matchers {

    private def texts(markdown: String): Seq[String] = LineCitations.scan(markdown).map(_.text)

    test("file:line citation in an inline code span") {
        texts("The wait is implicit (`Browser.scala:37`).") shouldBe Seq("Browser.scala:37")
    }

    test("file:line-line range") {
        texts("See `internal/CdpClient.scala:249-262` for the relay.") shouldBe Seq("internal/CdpClient.scala:249-262")
    }

    test("file:line,line list") {
        texts("Both arms (`MachineStatFactory.scala:39,82-94`).") shouldBe Seq("MachineStatFactory.scala:39,82-94")
        texts("Two sites (`Browser.scala:107-110, 149`).") shouldBe Seq("Browser.scala:107-110, 149")
    }

    test("bracketed citation with a repository path") {
        texts("Promotes above 8 [kyo-data/shared/src/main/scala/kyo/Dict.scala:8-12].") shouldBe
            Seq("kyo-data/shared/src/main/scala/kyo/Dict.scala:8-12")
    }

    test("citations of non-Scala files") {
        texts("Set in [build.sbt:125-127].") shouldBe Seq("build.sbt:125-127")
        texts("Documented in `README.md:491`.") shouldBe Seq("README.md:491")
        texts("The step in `.github/workflows/checks.yml:12`.") shouldBe Seq(".github/workflows/checks.yml:12")
        texts("The shim (`kyo_uring.c:40`, `kyo_uring.h:7`).") shouldBe Seq("kyo_uring.c:40", "kyo_uring.h:7")
        texts("The helper in `scripts/build.sh:88`.") shouldBe Seq("scripts/build.sh:88")
    }

    test("elided path segment") {
        texts("Lives in `jvm-native/.../SnapshotStorePlatform.scala:12`.") shouldBe Seq("jvm-native/.../SnapshotStorePlatform.scala:12")
    }

    test("several citations on one line are each reported") {
        texts("(`A.scala:1`, `B.scala:2`)") shouldBe Seq("A.scala:1", "B.scala:2")
    }

    test("a code span holding only a line number continues the previous file") {
        texts("Splits per field (`internal/GenDerive.scala:72-80`, `:118-137`).") shouldBe
            Seq("internal/GenDerive.scala:72-80", ":118-137")
        texts("The test proves it (`:185-204`).") shouldBe Seq(":185-204")
    }

    test("GitHub line anchor") {
        texts("[the root](https://github.com/getkyo/kyo/blob/main/build.sbt#L85)") shouldBe Seq("#L85")
        texts("[range](https://github.com/getkyo/kyo/blob/main/build.sbt#L85-L95)") shouldBe Seq("#L85")
    }

    test("L-prefixed line range") {
        texts("The flag selects the aggregate (see `build.sbt` ~L85-95).") shouldBe Seq("~L85-95")
        texts("The flag selects the aggregate (see `build.sbt` L85-95).") shouldBe Seq("L85-95")
    }

    test("prose line references") {
        texts("Defined at line 42 of the file.") shouldBe Seq("line 42")
        texts("Under \"Type-Level Scaladoc\" (lines 434-455).") shouldBe Seq("lines 434")
        texts("Lines 10 to 20 hold the table.") shouldBe Seq("Lines 10")
        texts("Around line ~85.") shouldBe Seq("line ~85")
    }

    test("a fenced comment that is only a citation") {
        val md =
            """Intro.
              |
              |```scala
              |opaque type Dict[K, V] = Span[K | V] | HashMap[K, V] // Dict.scala:66
              |```
              |""".stripMargin
        LineCitations.scan(md) shouldBe Seq(LineCitations.Citation(4, "Dict.scala:66"))
    }

    test("reports the Markdown line of each citation") {
        val md =
            """# Title
              |
              |First paragraph.
              |
              |See `Foo.scala:10`.
              |And line 7 too.
              |""".stripMargin
        LineCitations.scan(md) shouldBe Seq(LineCitations.Citation(5, "Foo.scala:10"), LineCitations.Citation(6, "line 7"))
    }

    test("look-alikes: ports, hosts and URLs") {
        texts("Connect to localhost:5432 or `http://localhost:4318/v1/traces`.") shouldBe empty
        texts("Bind 127.0.0.1:40123 and multicast 224.1.1.1:40123; the image is postgres:16.") shouldBe empty
        texts("Serve on https://example.com:8443/path.") shouldBe empty
    }

    test("look-alikes: times, ratios and dates") {
        texts("Runs at 12:30, a 3:1 ratio, the 1:1 mapping, at 2024-01-15T10:30.") shouldBe empty
        texts("Range -838:59:59 to 838:59:59.") shouldBe empty
    }

    test("look-alikes: versions and plain file references") {
        texts("Depends on kyo-core 1.0.0 and scala 3.7.4.") shouldBe empty
        texts("""addSbtPlugin("io.getkyo" % "kyo-doctest-plugin" % "1.2.3")""") shouldBe empty
        texts("See `Tasty.scala`, `Tasty.computeFullName`.") shouldBe empty
        texts("See [the guide](CONTRIBUTING.md#line-numbers) and `build.sbt`.") shouldBe empty
        texts("A YAML key `port: 8080` and a symbol `:ok` and a slice `xs(1:3)`.") shouldBe empty
    }

    test("look-alikes: words containing line") {
        texts("The pipeline 3 stages, inline 2 calls, deadline 5 seconds, a line-oriented format.") shouldBe empty
        texts("Fits in the L1 cache, and L2 too.") shouldBe empty
    }

    test("look-alikes: code and output inside fenced blocks") {
        val md =
            """```scala
              |val log = SLF4JLog("app")
              |// The appender sees: INFO app [App.scala:42] starting request
              |Doctest.Failure(Path("README.md"), line = 42, message = "type mismatch")
              |throw new Exception("at Foo.scala:12")
              |```
              |
              |```
              |INFO com.example.app [App.scala:42] starting request
              |```
              |
              |~~~text
              |error: Foo.scala:3:5: type mismatch
              |~~~
              |""".stripMargin
        LineCitations.scan(md) shouldBe empty
    }

    test("prose after a fenced block is scanned again") {
        val md =
            """```text
              |Foo.scala:1
              |```
              |Back to prose: `Bar.scala:2`.
              |""".stripMargin
        LineCitations.scan(md) shouldBe Seq(LineCitations.Citation(4, "Bar.scala:2"))
    }

    test("check names the file, the Markdown line and the offending text") {
        val root = Files.createTempDirectory("line-citations-").toFile
        val docs = new File(root, "docs")
        docs.mkdirs()
        val clean = new File(docs, "CLEAN.md")
        val dirty = new File(docs, "GUIDE.md")
        Files.write(clean.toPath, "Nothing to see in `Foo.scala`.\n".getBytes(StandardCharsets.UTF_8))
        Files.write(dirty.toPath, "Intro.\n\nSee `Foo.scala:12`.\n".getBytes(StandardCharsets.UTF_8))
        LineCitations.check(Seq(clean, dirty), root) shouldBe Seq("docs/GUIDE.md:3: Foo.scala:12")
    }
}
