package kyo

import kyo.mirrorfix.*

class MirrorMacroTest extends Test:

    given CanEqual[Any, Any] = CanEqual.derived

    // --- FromExpr unlift ---

    "FromExpr, single-field case class" in {
        assert(MirrorMacroHarness.unliftSingle(Single(42)) == "Single(42)")
    }

    "FromExpr, two-field case class" in {
        assert(MirrorMacroHarness.unliftPair(Pair(7, "seven")) == "Pair(7,seven)")
    }

    "FromExpr, three-field case class with mixed primitives" in {
        assert(MirrorMacroHarness.unliftTriple(Triple(1, "two", true)) == "Triple(1,two,true)")
    }

    "FromExpr, nested case classes" in {
        assert(MirrorMacroHarness.unliftOuter(Outer(Single(99), "label")) == "Outer(Single(99),label)")
    }

    "FromExpr, singleton case object" in {
        assert(MirrorMacroHarness.unliftEmpty(Empty) == "Empty")
    }

    "FromExpr, sealed trait, Circle variant" in {
        assert(MirrorMacroHarness.unliftShape(Circle(5)) == "Circle(5)")
    }

    "FromExpr, sealed trait, Rect variant" in {
        assert(MirrorMacroHarness.unliftShape(Rect(3, 4)) == "Rect(3,4)")
    }

    // --- ToExpr+FromExpr roundtrips ---

    "roundtrip, single-field case class" in {
        assert(MirrorMacroHarness.roundtripSingle(Single(42)) == "Single(42)")
    }

    "roundtrip, two-field case class" in {
        assert(MirrorMacroHarness.roundtripPair(Pair(7, "seven")) == "Pair(7,seven)")
    }

    "roundtrip, three-field case class" in {
        assert(MirrorMacroHarness.roundtripTriple(Triple(1, "two", true)) == "Triple(1,two,true)")
    }

    "roundtrip, nested case classes" in {
        assert(MirrorMacroHarness.roundtripOuter(Outer(Single(99), "label")) == "Outer(Single(99),label)")
    }

    "roundtrip, singleton case object" in {
        assert(MirrorMacroHarness.roundtripEmpty(Empty) == "Empty")
    }

    "roundtrip, sealed trait, Circle variant" in {
        assert(MirrorMacroHarness.roundtripShape(Circle(5)) == "Circle(5)")
    }

    "roundtrip, sealed trait, Rect variant" in {
        assert(MirrorMacroHarness.roundtripShape(Rect(3, 4)) == "Rect(3,4)")
    }

    // --- Sum type with mixed singleton + product variants ---

    "FromExpr, mixed-variant sum, NoneInt" in {
        assert(MirrorMacroHarness.unliftMaybeInt(NoneInt) == "NoneInt")
    }

    "FromExpr, mixed-variant sum, JustInt" in {
        assert(MirrorMacroHarness.unliftMaybeInt(JustInt(7)) == "JustInt(7)")
    }

    "FromExpr, mixed-variant sum, JustPair" in {
        assert(MirrorMacroHarness.unliftMaybeInt(JustPair(1, 2)) == "JustPair(1,2)")
    }

    "roundtrip, mixed-variant sum, NoneInt" in {
        assert(MirrorMacroHarness.roundtripMaybeInt(NoneInt) == "NoneInt")
    }

    "roundtrip, mixed-variant sum, JustInt" in {
        assert(MirrorMacroHarness.roundtripMaybeInt(JustInt(7)) == "JustInt(7)")
    }

    "roundtrip, mixed-variant sum, JustPair" in {
        assert(MirrorMacroHarness.roundtripMaybeInt(JustPair(1, 2)) == "JustPair(1,2)")
    }

    // --- Unions ---

    "FromExpr, Int | String, Int variant" in {
        assert(MirrorMacroHarness.unliftIntOrString(42) == "42")
    }
    "FromExpr, Int | String, String variant" in {
        assert(MirrorMacroHarness.unliftIntOrString("hi") == "hi")
    }
    "roundtrip, Int | String, Int variant" in {
        assert(MirrorMacroHarness.roundtripIntOrString(42) == "42")
    }
    "roundtrip, Int | String, String variant" in {
        assert(MirrorMacroHarness.roundtripIntOrString("hi") == "hi")
    }

    "FromExpr, Int | String | Boolean, Boolean variant" in {
        assert(MirrorMacroHarness.unliftThreeWay(true) == "true")
    }
    "roundtrip, Int | String | Boolean, Int variant" in {
        assert(MirrorMacroHarness.roundtripThreeWay(7) == "7")
    }
    "roundtrip, Int | String | Boolean, String variant" in {
        assert(MirrorMacroHarness.roundtripThreeWay("x") == "x")
    }
    "roundtrip, Int | String | Boolean, Boolean variant" in {
        assert(MirrorMacroHarness.roundtripThreeWay(false) == "false")
    }

    // --- Nested-in-object case classes ---

    "FromExpr, nested-in-object single-field" in {
        assert(MirrorMacroHarness.unliftNestedInner(Nested.Inner(7)) == "Nested.Inner(7)")
    }

    "FromExpr, nested-in-object two-field" in {
        assert(MirrorMacroHarness.unliftNestedTwo(Nested.Two(1, "x")) == "Nested.Two(1,x)")
    }

    "roundtrip, nested-in-object single-field" in {
        assert(MirrorMacroHarness.roundtripNestedInner(Nested.Inner(7)) == "Nested.Inner(7)")
    }

    "roundtrip, nested-in-object two-field" in {
        assert(MirrorMacroHarness.roundtripNestedTwo(Nested.Two(1, "x")) == "Nested.Two(1,x)")
    }

end MirrorMacroTest
