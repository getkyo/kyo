package kyo.ffi.sbt

import java.io.File
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/** Unit coverage for the topological sort, exercises
  * the `KyoFfiPlugin.topoSortLibraries` helper without spinning up a scripted
  * sbt invocation. Scripted coverage in `sbt-test/kyo-ffi/multi-library-deps`
  * verifies the wiring end-to-end.
  */
class TopoSortTest extends AnyFunSuite with Matchers {

    private def lib(id: String, deps: String*): FfiLibrary =
        FfiLibrary(id, cSources = Nil, dependsOn = deps)

    test("empty input is preserved") {
        KyoFfiPlugin.topoSortLibraries(Nil) shouldBe Nil
    }

    test("single library is preserved") {
        val a = lib("a")
        KyoFfiPlugin.topoSortLibraries(Seq(a)) shouldBe Seq(a)
    }

    test("independent libraries preserve input order") {
        val a = lib("a"); val b = lib("b"); val c = lib("c")
        KyoFfiPlugin.topoSortLibraries(Seq(c, a, b)).map(_.id) shouldBe Seq("c", "a", "b")
    }

    test("simple dependency: b dependsOn a is reordered") {
        val a = lib("a")
        val b = lib("b", "a")
        // Even when declared as Seq(b, a), sort emits a before b.
        KyoFfiPlugin.topoSortLibraries(Seq(b, a)).map(_.id) shouldBe Seq("a", "b")
    }

    test("transitive dependency chain a <- b <- c") {
        val a = lib("a")
        val b = lib("b", "a")
        val c = lib("c", "b")
        KyoFfiPlugin.topoSortLibraries(Seq(c, b, a)).map(_.id) shouldBe Seq("a", "b", "c")
    }

    test("diamond dependency: d <- {b, c} <- a") {
        val a   = lib("a")
        val b   = lib("b", "a")
        val c   = lib("c", "a")
        val d   = lib("d", "b", "c")
        val out = KyoFfiPlugin.topoSortLibraries(Seq(d, c, b, a)).map(_.id)
        // a must come first; d must come last; b and c after a, before d.
        out.head shouldBe "a"
        out.last shouldBe "d"
        out.indexOf("b") should be < out.indexOf("d")
        out.indexOf("c") should be < out.indexOf("d")
    }

    test("missing dependency id fails with clear error naming the library and missing id") {
        val a  = lib("a")
        val b  = lib("b", "missing")
        val ex = intercept[RuntimeException](KyoFfiPlugin.topoSortLibraries(Seq(a, b)))
        ex.getMessage should include("'b'")
        ex.getMessage should include("'missing'")
        ex.getMessage should include("Declared")
        ex.getMessage should include("a")
    }

    test("dependency cycle fails with a clear error naming the cycle path") {
        val a  = lib("a", "b")
        val b  = lib("b", "a")
        val ex = intercept[RuntimeException](KyoFfiPlugin.topoSortLibraries(Seq(a, b)))
        ex.getMessage should include("cycle")
        ex.getMessage should include("a")
        ex.getMessage should include("b")
    }

    test("self-cycle fails with a clear error") {
        val a  = lib("a", "a")
        val ex = intercept[RuntimeException](KyoFfiPlugin.topoSortLibraries(Seq(a)))
        ex.getMessage should include("cycle")
        ex.getMessage should include("a")
    }

    test("duplicate library ids fail") {
        val a1 = lib("a")
        val a2 = lib("a")
        val ex = intercept[RuntimeException](KyoFfiPlugin.topoSortLibraries(Seq(a1, a2)))
        ex.getMessage should include("duplicate")
        ex.getMessage should include("a")
    }
}
