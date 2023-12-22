package kyo.llm.thoughts.logic

sealed trait Expr

sealed trait BooleanExpr extends Expr
object BooleanExpr {
  case object True                                      extends BooleanExpr
  case object False                                     extends BooleanExpr
  case class Not(BooleanExpr: BooleanExpr)              extends BooleanExpr
  case class And(left: BooleanExpr, right: BooleanExpr) extends BooleanExpr
  case class Or(left: BooleanExpr, right: BooleanExpr)  extends BooleanExpr
  case class Var(name: String)                          extends BooleanExpr
}

sealed trait MathExpr extends Expr

object MathExpr {
  case class Constant(value: Double)                          extends MathExpr
  case class Variable(name: String)                           extends MathExpr
  case class Add(left: MathExpr, right: MathExpr)             extends MathExpr
  case class Subtract(left: MathExpr, right: MathExpr)        extends MathExpr
  case class Multiply(left: MathExpr, right: MathExpr)        extends MathExpr
  case class Divide(left: MathExpr, right: MathExpr)          extends MathExpr
  case class Power(base: MathExpr, exponent: MathExpr)        extends MathExpr
  case class Root(MathExpression: MathExpr, degree: MathExpr) extends MathExpr
}
