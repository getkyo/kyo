package kyo.internal

import kyo.Chunk

/** Test fixture for FromExprDerivedTest. Compiled into main so the macro's reflection-based instantiation can resolve these classes at
  * test-compile time. NOT for production use.
  */
private[kyo] object FromExprTestFixtures:

    case class Wrap(x: Int)
    case class Pair(a: Int, b: String):
        override def toString = s"Pair($a,$b)"
    case class Outer(inner: Pair, label: String):
        override def toString = s"Outer($inner,$label)"

    sealed trait Shape
    case class Circle(radius: Int)       extends Shape
    case class Rectangle(w: Int, h: Int) extends Shape

    enum Color:
        case Red, Green, Blue

    // Mutually-recursive ADT, exercises the FromExpr recursion guard independent of kyo-sql.
    // `Tree` ↔ `Forest` form a 2-type SCC: `Branch.children: Forest`, `Forest.trees: Chunk[Tree]`.
    sealed trait Tree
    case class Leaf(value: Int)         extends Tree
    case class Branch(children: Forest) extends Tree
    case class Forest(trees: Chunk[Tree])

    // Field-carrying enum cases: a singleton (`Leaf`) alongside cases carrying fields, `Maybe`, and
    // nesting. `Arr(Opt(Maybe(10)))` exercises nested field-carrying and `Maybe`-carrying enum-case
    // lifting with no kyo-sql dependency. Regression guard for `FromExpr.derived` over such an enum.
    enum Probe derives CanEqual:
        case Leaf
        case Num(p: Int, s: Int)
        case Arr(e: Probe)
        case Ext(name: String)
        case Opt(v: kyo.Maybe[Int])
    end Probe

end FromExprTestFixtures
