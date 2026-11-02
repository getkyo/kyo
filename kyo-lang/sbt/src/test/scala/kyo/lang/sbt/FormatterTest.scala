package kyo.lang.sbt

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** The pinned `.kyo` formatting profile, one golden case per canonical syntax row of kyo-lang/DESIGN.md (S01 to S12), each checked for
  * its exact output and for idempotence. Scripted coverage in `sbt-test/kyo-lang` verifies the wiring into compile and check mode.
  */
class FormatterTest extends AnyFunSuite with Matchers {

    private def formats(input: String, expected: String): Unit = {
        val once = Formatter.format(input).fold(error => fail(s"does not parse: $error"), identity)
        once shouldBe expected
        Formatter.format(once).fold(error => fail(s"formatted output does not parse: $error"), identity) shouldBe expected
    }

    test("S01 braces become significant indentation with four spaces") {
        formats(
            """object Braced {
              |  def twice(n: Int): Int = {
              |    val doubled = n * 2
              |    doubled
              |  }
              |}
              |""".stripMargin,
            """object Braced:
              |    def twice(n: Int): Int =
              |        val doubled = n * 2
              |        doubled
              |end Braced
              |""".stripMargin
        )
    }

    test("S02 control flow uses the new syntax") {
        formats(
            """object Control:
              |    def pick(flag: Boolean, n: Int) = if (flag) n else -n
              |    def count(n: Int) = while (n > 0) ()
              |    def all(xs: List[Int]) = for (x <- xs) yield x
              |""".stripMargin,
            """object Control:
              |    def pick(flag: Boolean, n: Int) = if flag then n else -n
              |    def count(n: Int)               = while n > 0 do ()
              |    def all(xs: List[Int])          = for x <- xs yield x
              |end Control
              |""".stripMargin
        )
    }

    test("S03 one statement per line: a semicolon between statements is reported, not reshaped") {
        val result = Formatter.format(
            """object Statements:
              |    def sum =
              |        val a = 1; val b = 2
              |        a + b
              |""".stripMargin
        )
        result shouldBe Left("one statement per line: a semicolon separates statements at line 3")
    }

    test("S05 a multi-line final block argument uses the colon syntax") {
        formats(
            """object Blocks:
              |    def total(xs: List[Int]) = xs.map { x =>
              |        val doubled = x * 2
              |        doubled + 1
              |    }
              |""".stripMargin,
            """object Blocks:
              |    def total(xs: List[Int]) = xs.map: x =>
              |        val doubled = x * 2
              |        doubled + 1
              |end Blocks
              |""".stripMargin
        )
    }

    test("S07 imports use wildcards, renames, and one layout") {
        formats(
            """import scala.collection.immutable.{Map => IMap, _}
              |import scala.util.{Try, Success}
              |object Imports:
              |    def m: IMap[Int, Int] = IMap.empty
              |""".stripMargin,
            """import scala.collection.immutable.{Map as IMap, *}
              |import scala.util.Success
              |import scala.util.Try
              |object Imports:
              |    def m: IMap[Int, Int] = IMap.empty
              |""".stripMargin
        )
    }

    test("S08 wildcard types use the question mark") {
        formats(
            """object Types:
              |    def size(xs: List[_]): Int = xs.size
              |""".stripMargin,
            """object Types:
              |    def size(xs: List[?]): Int = xs.size
              |""".stripMargin
        )
    }

    test("S09 repeated arguments use the star") {
        formats(
            """object Repeated:
              |    def all(xs: Int*): Int = xs.sum
              |    def pass(xs: List[Int]) = all(xs: _*)
              |""".stripMargin,
            """object Repeated:
              |    def all(xs: Int*): Int  = xs.sum
              |    def pass(xs: List[Int]) = all(xs*)
              |""".stripMargin
        )
    }

    test("S11 ASCII arrows and a stable modifier order") {
        formats(
            """object Tokens:
              |    override final def f(xs: List[Int]) = xs.map(x ⇒ x → x)
              |""".stripMargin,
            """object Tokens:
              |    final override def f(xs: List[Int]) = xs.map(x => x -> x)
              |""".stripMargin
        )
    }

    test("S12 end markers after four lines, never for shorter definitions") {
        formats(
            """object Markers:
              |    def long(n: Int) =
              |        val a = n + 1
              |        val b = a + 1
              |        val c = b + 1
              |        a + b + c
              |    def short(n: Int) =
              |        n + 1
              |""".stripMargin,
            """object Markers:
              |    def long(n: Int) =
              |        val a = n + 1
              |        val b = a + 1
              |        val c = b + 1
              |        a + b + c
              |    end long
              |    def short(n: Int) =
              |        n + 1
              |end Markers
              |""".stripMargin
        )
    }

    test("comments and string contents are preserved") {
        val triple = "\"\"\""
        val source =
            s"""object Text:
               |    // a comment with   spaces
               |    def text = ${triple}keep   these
               |  spaces$triple
               |""".stripMargin
        formats(source, source)
    }

    test("a source that does not parse is reported") {
        Formatter.format("object Broken:\n    def =\n").isLeft shouldBe true
    }
}
