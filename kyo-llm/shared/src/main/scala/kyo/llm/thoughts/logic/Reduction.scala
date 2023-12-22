package kyo.llm.thoughts.logic

import kyo._
import kyo.ios._
import kyo.llm.ais._
import kyo.llm.KyoLLMApp
import kyo.llm.configs.Config
import scala.util.Random
import kyo.llm.thoughts.meta.Collect
import kyo.consoles.Consoles

@desc(
    p"""
      The Reduction thought allows you to apply reduction steps to simplify an expression.
      - Outlines each step of the reduction process with rule descriptions and expression transformations.
      - Iteratively simplifies the expression using rules.
      - Normalize the input expression using prefix operators. Example: (&& false false)
      - Apply reduction steps to produce the final outcome.
      - **Do not produce fields with empty values**
    """
)
case class Reduction(
    initialExpression: String,
    `Normalize the expression`: Boolean,
    normalizedExpression: String,
    reductionSteps: List[ReductionStep],
    finalOutcome: String
)

@desc(
    p"""
      Make sure to use small steps to reason through
      the reduction process. One for each sub-expression.
    """
)
case class ReductionStep(
    ruleDescription: String,
    inputExpression: String,
    outputExpression: String
)

object tt extends KyoLLMApp {

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

  def gen(size: Int): BooleanExpr =
    if (size <= 1) {
      Random.nextBoolean() match {
        case true  => True
        case false => False
      }
    } else {
      Random.nextInt(3) match {
        case 0 => And(gen(size / 2), gen(size / 2))
        case 1 => Or(gen(size / 2), gen(size / 2))
        case 2 => Not(gen(size - 1))
      }
    }

  override def config: Config =
    super.config.apiKey("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")

  run {
    def loop(): Unit < AIs =
      IOs {
        val e = gen(5)
        println(e.show)
        AIs.gen[Reduction](e.show).map { r =>
          Consoles.println(r).andThen {
            if (r.finalOutcome.toBoolean != e.eval) {
              println("FAIL: " + e.show)
            }
            loop()
          }
        }
      }
    AIs.parallel(List.fill(5)(loop()))
  }
}
