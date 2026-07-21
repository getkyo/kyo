package kyo.mirrorfix

import scala.quoted.FromExpr
import scala.quoted.ToExpr

/** Test fixtures for MirrorMacroTest. Each type lives at PACKAGE LEVEL (not inside an `object`) so that
  * `Symbol.requiredClass` resolves the class without a `$`-suffixed outer-module path. Nested-in-object
  * case classes have an outer-self-type encoding the consumer compiler reports as `Class/T`.
  */

case class Single(x: Int):
    override def toString = s"Single($x)"
inline given FromExpr[Single] = kyo.FromExpr.derived
inline given ToExpr[Single]   = kyo.ToExpr.derived

case class Pair(a: Int, b: String):
    override def toString = s"Pair($a,$b)"
inline given FromExpr[Pair] = kyo.FromExpr.derived
inline given ToExpr[Pair]   = kyo.ToExpr.derived

case class Triple(a: Int, b: String, c: Boolean):
    override def toString = s"Triple($a,$b,$c)"
inline given FromExpr[Triple] = kyo.FromExpr.derived
inline given ToExpr[Triple]   = kyo.ToExpr.derived

case class Outer(inner: Single, label: String):
    override def toString = s"Outer($inner,$label)"
inline given FromExpr[Outer] = kyo.FromExpr.derived
inline given ToExpr[Outer]   = kyo.ToExpr.derived

case object Empty
inline given FromExpr[Empty.type] = kyo.FromExpr.derived
inline given ToExpr[Empty.type]   = kyo.ToExpr.derived

sealed trait Shape:
    override def toString: String = this match
        case Circle(r)  => s"Circle($r)"
        case Rect(w, h) => s"Rect($w,$h)"
end Shape
case class Circle(r: Int)       extends Shape
case class Rect(w: Int, h: Int) extends Shape
inline given FromExpr[Circle] = kyo.FromExpr.derived
inline given ToExpr[Circle]   = kyo.ToExpr.derived
inline given FromExpr[Rect]   = kyo.FromExpr.derived
inline given ToExpr[Rect]     = kyo.ToExpr.derived
inline given FromExpr[Shape]  = kyo.FromExpr.derived
inline given ToExpr[Shape]    = kyo.ToExpr.derived

sealed trait MaybeInt
case object NoneInt extends MaybeInt:
    override def toString = "NoneInt"
case class JustInt(value: Int) extends MaybeInt:
    override def toString = s"JustInt($value)"
case class JustPair(a: Int, b: Int) extends MaybeInt:
    override def toString = s"JustPair($a,$b)"
inline given FromExpr[NoneInt.type] = kyo.FromExpr.derived
inline given ToExpr[NoneInt.type]   = kyo.ToExpr.derived
inline given FromExpr[JustInt]      = kyo.FromExpr.derived
inline given ToExpr[JustInt]        = kyo.ToExpr.derived
inline given FromExpr[JustPair]     = kyo.FromExpr.derived
inline given ToExpr[JustPair]       = kyo.ToExpr.derived
inline given FromExpr[MaybeInt]     = kyo.FromExpr.derived
inline given ToExpr[MaybeInt]       = kyo.ToExpr.derived

type IntOrString = Int | String
inline given FromExpr[IntOrString] = kyo.FromExpr.derived
inline given ToExpr[IntOrString]   = kyo.ToExpr.derived

type ThreeWay = Int | String | Boolean
inline given FromExpr[ThreeWay] = kyo.FromExpr.derived
inline given ToExpr[ThreeWay]   = kyo.ToExpr.derived

object Nested:
    case class Inner(x: Int):
        override def toString = s"Nested.Inner($x)"
    inline given FromExpr[Inner] = kyo.FromExpr.derived
    inline given ToExpr[Inner]   = kyo.ToExpr.derived

    case class Two(a: Int, b: String):
        override def toString = s"Nested.Two($a,$b)"
    inline given FromExpr[Two] = kyo.FromExpr.derived
    inline given ToExpr[Two]   = kyo.ToExpr.derived
end Nested
