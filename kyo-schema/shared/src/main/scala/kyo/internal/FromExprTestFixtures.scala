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

    // Mutually-recursive ADT — exercises the FromExpr recursion guard (Phase F.5) independent of kyo-sql.
    // `Tree` ↔ `Forest` form a 2-type SCC: `Branch.children: Forest`, `Forest.trees: Chunk[Tree]`.
    sealed trait Tree
    case class Leaf(value: Int)         extends Tree
    case class Branch(children: Forest) extends Tree
    case class Forest(trees: Chunk[Tree])

end FromExprTestFixtures
