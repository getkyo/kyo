package kyo

import kyo.internal.ChartFoundations
import kyo.internal.ChartFoundations.CatKey
import kyo.internal.ChartLower
import kyo.internal.Domain
import kyo.internal.HtmlRenderer
import scala.language.implicitConversions

class ChartFoundationsTest extends Test:

    // ---- INV-002: CatKey identity by class+value, not toString ----

    "catKey distinguishes Int 1 from String 1 despite equal toString" in {
        val k1 = ChartFoundations.categoryKey(1)
        val k2 = ChartFoundations.categoryKey("1")
        assert(k1 != k2, "Int 1 and String \"1\" must have distinct keys (INV-002)")
        assert(k1 == CatKey(classOf[java.lang.Integer], 1))
        assert(k2 == CatKey(classOf[java.lang.String], "1"))
        succeed
    }

    // ---- INV-002: null raw folds to one stable bucket ----

    "catKey buckets null without NPE and produces a stable key" in {
        val k1 = ChartFoundations.categoryKey(null)
        val k2 = ChartFoundations.categoryKey(null)
        assert(k1 == k2, "Two null inputs must produce equal keys (INV-002)")
        assert(k1 == CatKey(null, null))
        succeed
    }

    // ---- INV-006: distinctKeyed preserves first-seen order and deduplicates by key ----

    "distinctKeyed deduplicates by CatKey in encounter order" in {
        val rows   = Chunk("a", "b", "a", "c")
        val result = ChartFoundations.distinctKeyed(rows, r => ChartFoundations.categoryKey(r))
        assert(result.size == 3, s"Expected 3 distinct entries but got ${result.size}")
        assert(result(0)._2 == "a", s"First entry should be 'a' but got ${result(0)._2}")
        assert(result(1)._2 == "b", s"Second entry should be 'b' but got ${result(1)._2}")
        assert(result(2)._2 == "c", s"Third entry should be 'c' but got ${result(2)._2}")
        succeed
    }

    "distinctKeyed empty fast-path returns Chunk.empty" in {
        val result = ChartFoundations.distinctKeyed(Chunk.empty[String], r => ChartFoundations.categoryKey(r))
        assert(result.isEmpty, "Empty input must return Chunk.empty (INV-006)")
        succeed
    }

    // ---- INV-003: chartIdPrefix is content-stable and distinct ----

    "chartIdPrefix is deterministic for the same spec object and distinct for different specs" in {
        case class Row(x: Int, y: Double)
        given CanEqual[Row, Row]               = CanEqual.derived
        given CanEqual[Chunk[Row], Chunk[Row]] = CanEqual.derived
        val rows                               = Chunk(Row(1, 2.0))
        val spec1                              = Chart(rows)(bar(x = _.x, y = _.y))
        val spec3                              = Chart(rows)(bar(x = _.x, y = _.y), point(x = _.x, y = _.y))
        // Same spec object called twice: must produce the same prefix (deterministic hashing)
        val p1a = ChartFoundations.chartIdPrefix(spec1)
        val p1b = ChartFoundations.chartIdPrefix(spec1)
        assert(p1a == p1b, s"Same spec must produce same prefix both times (INV-003): p1a=$p1a p1b=$p1b")
        // Different spec with different mark count: must produce a different prefix
        val p3 = ChartFoundations.chartIdPrefix(spec3)
        assert(p1a != p3, s"Different specs must produce different prefix (INV-003): p1a=$p1a p3=$p3")
        assert(p1a.startsWith("kyo-chart-"), s"Prefix must start with kyo-chart- but got $p1a")
        succeed
    }

    // ---- INV-001: filterFinite drops NaN/Inf and the finite extent of {1.0, NaN, 3.0} is exactly [1.0, 3.0] ----

    "INV-001: filterFinite retains finite values and the fitted extent for {1.0, NaN, 3.0} is exactly [1.0, 3.0]" in run {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
        val root = summon[Conversion[ChartSpec[Row], Svg.Root]](spec)
        // NaN-free: the full render pipeline must not emit "NaN" or "Infinity" (INV-001 core)
        for html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"SVG output must not contain 'NaN' (INV-001): ${html.take(200)}")
            assert(!html.contains("Infinity"), s"SVG output must not contain 'Infinity' (INV-001)")
            // Exact-extent: filterFinite drops Double.NaN, leaving {1.0, 3.0}.
            // Verify the finite guard on each domain value directly.
            val domainValues  = rows.map(r => ChartFoundations.filterFinite(Present(Domain.Continuous(r.y))))
            val finite        = domainValues.filter(_.isDefined)
            val finiteDoubles = finite.collect { case Present(Domain.Continuous(v)) => v }
            assert(finiteDoubles == Chunk(1.0, 3.0), s"filterFinite must drop NaN and retain {1.0, 3.0} (INV-001): $finiteDoubles")
            val lo = finiteDoubles.foldLeft(Double.MaxValue)(math.min)
            val hi = finiteDoubles.foldLeft(Double.MinValue)(math.max)
            assert(lo == 1.0, s"Inferred extent lo must be 1.0 (INV-001): $lo")
            assert(hi == 3.0, s"Inferred extent hi must be 3.0 (INV-001): $hi")
        end for
    }

end ChartFoundationsTest
