package kyo.llm.thoughts

import kyo._
import kyo.ios._
import kyo.llm.ais._
import kyo.llm.KyoLLMApp
import kyo.llm.configs.Config
import scala.util.Random
import kyo.consoles.Consoles
import kyo.llm.agents.Agent

@desc(
    p"""
      The Reduce thought allows for detailed tracking and explanation of each step in the expression reduction process.
      - Includes detailed rule descriptions and expression transformations.
      - Tracks intermediate expressions and sub-expressions.
      - Normalizes input expression and iteratively simplifies it.
      - **None of the fields can be empty**
      - **No empty arrays and no empty strings**
    """
)
case class Reduce[Expr, Result](
    initialExpression: Expr,
    `Reduce steps can not be empty`: Boolean,
    completeStepByStepReduce: List[ReduceStep[Expr]],
    fullyReducedResult: Result,
    `Reduce steps and result are not empty`: Boolean
)

@desc(
    p"""
      The Reduce thought allows for detailed tracking and explanation of each step in the expression reduction process.
      - Includes detailed rule descriptions and expression transformations.
      - Tracks intermediate expressions and sub-expressions.
      - Normalizes input expression and iteratively simplifies it.
      - **None of the fields can be empty**
      - **No empty arrays and no empty strings**
    """
)
case class ReduceStep[Expr](
    ruleDescription: String,
    inputExpression: Expr,
    `Description of inputExpression`: String,
    `Method to apply rule`: String,
    outputExpression: Expr
)

object tt extends KyoLLMApp {

  override def config: Config =
    super.config.apiKey("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").seed(
        Some(8931)
    ).temperature(0)

  run {
    def loop(): Unit < AIs =
      IOs {
        val e = booleanExpr(10)
        println(e.show)
        AIs.gen[Refine[Reduce[String, Boolean]]](e.show).map { r =>
          Consoles.println(r).andThen {
            if (r.finalCorrectSolution.fullyReducedResult != e.eval) {
              println("FAIL!!! " + e.show)
            }
            loop()
          }
        }
      }
    AIs.parallel(List.fill(10)(loop()))
  }

  sealed trait BooleanExpr {
    def eval: Boolean
    def show: String
  }

  case object True extends BooleanExpr {
    def eval = true
    def show = "true"
  }
  case object False extends BooleanExpr {
    def eval = false
    def show = "false"
  }
  case class Not(expr: BooleanExpr) extends BooleanExpr {
    def eval = !expr.eval
    def show = s"!(${expr.show})"
  }
  case class And(left: BooleanExpr, right: BooleanExpr) extends BooleanExpr {
    def eval = left.eval && right.eval
    def show = s"(${left.show} && ${right.show})"
  }
  case class Or(left: BooleanExpr, right: BooleanExpr) extends BooleanExpr {
    def eval = left.eval || right.eval
    def show = s"(${left.show} || ${right.show})"
  }

  def booleanExpr(size: Int): BooleanExpr =
    if (size <= 1) {
      Random.nextBoolean() match {
        case true  => True
        case false => False
      }
    } else {
      Random.nextInt(3) match {
        case 0 => And(booleanExpr(size / 2), booleanExpr(size / 2))
        case 1 => Or(booleanExpr(size / 2), booleanExpr(size / 2))
        case 2 => Not(booleanExpr(size - 1))
      }
    }

  import scala.util.Random

  sealed trait MathExpr {
    def eval: Int
    def show: String
  }

  case class Const(value: Int) extends MathExpr {
    def eval = value
    def show = value.toString
  }

  case class Add(left: MathExpr, right: MathExpr) extends MathExpr {
    def eval = left.eval + right.eval
    def show = s"(${left.show} + ${right.show})"
  }

  case class Subtract(left: MathExpr, right: MathExpr) extends MathExpr {
    def eval = left.eval - right.eval
    def show = s"(${left.show} - ${right.show})"
  }

  case class Multiply(left: MathExpr, right: MathExpr) extends MathExpr {
    def eval = left.eval * right.eval
    def show = s"(${left.show} * ${right.show})"
  }

  def mathExpr(size: Int): MathExpr = {
    if (size <= 1) {
      Const(Random.nextInt(10))
    } else {
      Random.nextInt(3) match {
        case 0 => Add(mathExpr(size / 2), mathExpr(size / 2))
        case 1 => Subtract(mathExpr(size / 2), mathExpr(size / 2))
        case 2 => Multiply(mathExpr(size / 2), mathExpr(size / 2))
      }
    }
  }

}
