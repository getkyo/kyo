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
) extends Thought.Opening

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
) extends Thought.Opening
