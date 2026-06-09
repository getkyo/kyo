package kyo

import kyo.Chart.*
import kyo.UI.*
import kyo.UI.Ast.*
import kyo.internal.ChartFoundations
import kyo.internal.ChartFoundations.CatKey
import kyo.internal.Domain
import kyo.internal.HtmlRenderer

class ChartFoundationsTest extends kyo.test.Test[Any]:

    // Two enum cases that override toString to the same label: they collide under toString-keyed
    // dedup but stay distinct under CatKey (keyed by tag + value, the case being its own value).
    enum Col derives CanEqual:
        case Red
        case Blue
        override def toString: String = "color"
    end Col

    // ---- CatKey identity by stable type tag + value, not toString, cross-platform ----

    "catKey distinguishes Int 1 from String 1 despite equal toString" in {
        val k1 = ChartFoundations.categoryKey(1)
        val k2 = ChartFoundations.categoryKey("1")
        assert(k1 != k2, "Int 1 and String \"1\" must have distinct keys despite equal toString")
    }

    "catKey is stable: the same value keys to an equal CatKey" in {
        assert(ChartFoundations.categoryKey(1) == ChartFoundations.categoryKey(1), "Int 1 must key stably")
        assert(ChartFoundations.categoryKey("a") == ChartFoundations.categoryKey("a"), "String a must key stably")
    }

    "catKey distinguishes distinct values of the same type" in {
        assert(ChartFoundations.categoryKey(1) != ChartFoundations.categoryKey(2), "Int 1 and 2 must differ")
    }

    // ---- enum cases with colliding toString stay distinct (keyed by tag + value) ----

    "catKey distinguishes enum cases that share a toString" in {
        // Col.Red and Col.Blue both override toString to "color"; CatKey keys by tag + value
        // (the enum case is its own value), so they stay distinct despite the toString collision.
        val red  = ChartFoundations.categoryKey(Col.Red)
        val blue = ChartFoundations.categoryKey(Col.Blue)
        assert(red != blue, "Col.Red and Col.Blue must have distinct keys despite equal toString")
        assert(red == ChartFoundations.categoryKey(Col.Red), "Col.Red must key stably")
    }

    // ---- a typed null VALUE flowing through the explicit-tag form is null-safe ----

    "catKey keys a typed null value via the explicit-tag form without NPE" in {
        // A typed encoding accessor (e.g. Encoding[A, String]) can yield a null reference. That flows
        // through categoryKey(tag, value) where value: Any may be null. The key must be stable and
        // distinct from a non-null value under the same tag, and must never throw.
        val t = summon[ConcreteTag[String]]
        assert(
            ChartFoundations.categoryKey(t, null) == ChartFoundations.categoryKey(t, null),
            "Two null values under the same tag must key equal"
        )
        assert(
            ChartFoundations.categoryKey(t, "x") != ChartFoundations.categoryKey(t, null),
            "A null value must key distinctly from a non-null value under the same tag"
        )
    }

    // ---- distinctKeyed preserves first-seen order and deduplicates by key ----

    "distinctKeyed deduplicates by CatKey in encounter order" in {
        val rows   = Chunk("a", "b", "a", "c")
        val result = ChartFoundations.distinctKeyed(rows, r => ChartFoundations.categoryKey(r))
        assert(result.size == 3, s"Expected 3 distinct entries but got ${result.size}")
        assert(result(0)._2 == "a", s"First entry should be 'a' but got ${result(0)._2}")
        assert(result(1)._2 == "b", s"Second entry should be 'b' but got ${result(1)._2}")
        assert(result(2)._2 == "c", s"Third entry should be 'c' but got ${result(2)._2}")
    }

    "distinctKeyed empty fast-path returns Chunk.empty" in {
        val result = ChartFoundations.distinctKeyed(Chunk.empty[String], r => ChartFoundations.categoryKey(r))
        assert(result.isEmpty, "Empty input must return Chunk.empty")
    }

    // ---- chartIdPrefix is content-stable and distinct ----

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
        assert(p1a == p1b, s"Same spec must produce same prefix both times: p1a=$p1a p1b=$p1b")
        // Different spec with different mark count: must produce a different prefix
        val p3 = ChartFoundations.chartIdPrefix(spec3)
        assert(p1a != p3, s"Different specs must produce different prefix: p1a=$p1a p3=$p3")
        assert(p1a.startsWith("kyo-chart-"), s"Prefix must start with kyo-chart- but got $p1a")
    }

    // ---- filterFinite drops NaN/Inf and the finite extent of {1.0, NaN, 3.0} is exactly [1.0, 3.0] ----

    "filterFinite retains finite values and the fitted extent for {1.0, NaN, 3.0} is exactly [1.0, 3.0]" in {
        case class Row(x: Int, y: Double)
        val rows = Chunk(Row(0, 1.0), Row(1, Double.NaN), Row(2, 3.0))
        val spec = Chart(rows)(bar(x = _.x, y = _.y))
        // NaN-free: the full render pipeline must not emit "NaN" or "Infinity"
        for
            root <- (spec).lower
            html <- HtmlRenderer.render(root, Seq.empty)
        yield
            assert(!html.contains("NaN"), s"SVG output must not contain 'NaN': ${html.take(200)}")
            assert(!html.contains("Infinity"), s"SVG output must not contain 'Infinity'")
            // Exact-extent: filterFinite drops Double.NaN, leaving {1.0, 3.0}.
            // Verify the finite guard on each domain value directly.
            val domainValues  = rows.map(r => ChartFoundations.filterFinite(Present(Domain.Continuous(r.y))))
            val finite        = domainValues.filter(_.isDefined)
            val finiteDoubles = finite.collect { case Present(Domain.Continuous(v)) => v }
            assert(finiteDoubles == Chunk(1.0, 3.0), s"filterFinite must drop NaN and retain {1.0, 3.0}: $finiteDoubles")
            val lo = finiteDoubles.foldLeft(Double.MaxValue)(math.min)
            val hi = finiteDoubles.foldLeft(Double.MinValue)(math.max)
            assert(lo == 1.0, s"Inferred extent lo must be 1.0: $lo")
            assert(hi == 3.0, s"Inferred extent hi must be 3.0: $hi")
        end for
    }

end ChartFoundationsTest
