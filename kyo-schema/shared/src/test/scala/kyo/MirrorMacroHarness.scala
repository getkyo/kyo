package kyo

import kyo.mirrorfix.*
import kyo.mirrorfix.given
import scala.quoted.*

/** Per-type harness for FromExpr / ToExpr Mirror macro tests.
  *
  * Each `unlift*` exercises the FromExpr derivation via `case Expr(v)`. Each `roundtrip*` exercises BOTH: lifts the value via `Expr(v)`
  * (ToExpr) into a quoted expression, then unlifts the quoted expression back via `case Expr(v)` (FromExpr).
  */
object MirrorMacroHarness:

    // === Single ===
    inline def unliftSingle(inline v: Single): String    = ${ unliftSingleImpl('v) }
    inline def roundtripSingle(inline v: Single): String = ${ roundtripSingleImpl('v) }
    private def unliftSingleImpl(v: Expr[Single])(using Quotes): Expr[String] = v match
        case Expr(s) => Expr(s.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripSingleImpl(v: Expr[Single])(using Quotes): Expr[String] = v match
        case Expr(s) =>
            val relifted = Expr(s)
            relifted match
                case Expr(s2) => Expr(s2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === Pair ===
    inline def unliftPair(inline v: Pair): String    = ${ unliftPairImpl('v) }
    inline def roundtripPair(inline v: Pair): String = ${ roundtripPairImpl('v) }
    private def unliftPairImpl(v: Expr[Pair])(using Quotes): Expr[String] = v match
        case Expr(p) => Expr(p.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripPairImpl(v: Expr[Pair])(using Quotes): Expr[String] = v match
        case Expr(p) =>
            val relifted = Expr(p)
            relifted match
                case Expr(p2) => Expr(p2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === Triple ===
    inline def unliftTriple(inline v: Triple): String    = ${ unliftTripleImpl('v) }
    inline def roundtripTriple(inline v: Triple): String = ${ roundtripTripleImpl('v) }
    private def unliftTripleImpl(v: Expr[Triple])(using Quotes): Expr[String] = v match
        case Expr(t) => Expr(t.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripTripleImpl(v: Expr[Triple])(using Quotes): Expr[String] = v match
        case Expr(t) =>
            val relifted = Expr(t)
            relifted match
                case Expr(t2) => Expr(t2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === Outer (nested) ===
    inline def unliftOuter(inline v: Outer): String    = ${ unliftOuterImpl('v) }
    inline def roundtripOuter(inline v: Outer): String = ${ roundtripOuterImpl('v) }
    private def unliftOuterImpl(v: Expr[Outer])(using Quotes): Expr[String] = v match
        case Expr(o) => Expr(o.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripOuterImpl(v: Expr[Outer])(using Quotes): Expr[String] = v match
        case Expr(o) =>
            val relifted = Expr(o)
            relifted match
                case Expr(o2) => Expr(o2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === Empty (case object) ===
    inline def unliftEmpty(inline v: Empty.type): String    = ${ unliftEmptyImpl('v) }
    inline def roundtripEmpty(inline v: Empty.type): String = ${ roundtripEmptyImpl('v) }
    private def unliftEmptyImpl(v: Expr[Empty.type])(using Quotes): Expr[String] = v match
        case Expr(e) => Expr(e.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripEmptyImpl(v: Expr[Empty.type])(using Quotes): Expr[String] = v match
        case Expr(e) =>
            val relifted = Expr(e)
            relifted match
                case Expr(e2) => Expr(e2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === Shape (sealed trait) ===
    inline def unliftShape(inline v: Shape): String    = ${ unliftShapeImpl('v) }
    inline def roundtripShape(inline v: Shape): String = ${ roundtripShapeImpl('v) }
    private def unliftShapeImpl(v: Expr[Shape])(using Quotes): Expr[String] = v match
        case Expr(s) => Expr(s.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripShapeImpl(v: Expr[Shape])(using Quotes): Expr[String] = v match
        case Expr(s) =>
            val relifted = Expr(s)
            relifted match
                case Expr(s2) => Expr(s2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === MaybeInt (mixed-variant sum: case object + case classes) ===
    inline def unliftMaybeInt(inline v: MaybeInt): String    = ${ unliftMaybeIntImpl('v) }
    inline def roundtripMaybeInt(inline v: MaybeInt): String = ${ roundtripMaybeIntImpl('v) }
    private def unliftMaybeIntImpl(v: Expr[MaybeInt])(using Quotes): Expr[String] = v match
        case Expr(m) => Expr(m.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripMaybeIntImpl(v: Expr[MaybeInt])(using Quotes): Expr[String] = v match
        case Expr(m) =>
            val relifted = Expr(m)
            relifted match
                case Expr(m2) => Expr(m2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === IntOrString (union) ===
    inline def unliftIntOrString(inline v: IntOrString): String    = ${ unliftIntOrStringImpl('v) }
    inline def roundtripIntOrString(inline v: IntOrString): String = ${ roundtripIntOrStringImpl('v) }
    private def unliftIntOrStringImpl(v: Expr[IntOrString])(using Quotes): Expr[String] = v match
        case Expr(u) => Expr(u.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripIntOrStringImpl(v: Expr[IntOrString])(using Quotes): Expr[String] = v match
        case Expr(u) =>
            val relifted = Expr(u)
            relifted match
                case Expr(u2) => Expr(u2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === ThreeWay (Int | String | Boolean) ===
    inline def unliftThreeWay(inline v: ThreeWay): String    = ${ unliftThreeWayImpl('v) }
    inline def roundtripThreeWay(inline v: ThreeWay): String = ${ roundtripThreeWayImpl('v) }
    private def unliftThreeWayImpl(v: Expr[ThreeWay])(using Quotes): Expr[String] = v match
        case Expr(u) => Expr(u.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripThreeWayImpl(v: Expr[ThreeWay])(using Quotes): Expr[String] = v match
        case Expr(u) =>
            val relifted = Expr(u)
            relifted match
                case Expr(u2) => Expr(u2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    // === Nested.Inner / Nested.Two (case classes inside an object) ===
    inline def unliftNestedInner(inline v: Nested.Inner): String    = ${ unliftNestedInnerImpl('v) }
    inline def roundtripNestedInner(inline v: Nested.Inner): String = ${ roundtripNestedInnerImpl('v) }
    private def unliftNestedInnerImpl(v: Expr[Nested.Inner])(using Quotes): Expr[String] = v match
        case Expr(n) => Expr(n.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripNestedInnerImpl(v: Expr[Nested.Inner])(using Quotes): Expr[String] = v match
        case Expr(n) =>
            val relifted = Expr(n)
            relifted match
                case Expr(n2) => Expr(n2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

    inline def unliftNestedTwo(inline v: Nested.Two): String    = ${ unliftNestedTwoImpl('v) }
    inline def roundtripNestedTwo(inline v: Nested.Two): String = ${ roundtripNestedTwoImpl('v) }
    private def unliftNestedTwoImpl(v: Expr[Nested.Two])(using Quotes): Expr[String] = v match
        case Expr(n) => Expr(n.toString)
        case _       => Expr("<unlift-failed>")
    private def roundtripNestedTwoImpl(v: Expr[Nested.Two])(using Quotes): Expr[String] = v match
        case Expr(n) =>
            val relifted = Expr(n)
            relifted match
                case Expr(n2) => Expr(n2.toString)
                case _        => Expr("<roundtrip-from-failed>")
        case _ => Expr("<unlift-failed>")

end MirrorMacroHarness
