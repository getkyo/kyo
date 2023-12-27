package kyo.llm.thoughts

import kyo._
import kyo.ios._
import kyo.llm.ais._
import kyo.llm.KyoLLMApp
import kyo.llm.configs.Config
import scala.util.Random
import kyo.consoles.Consoles
import kyo.llm.agents.Agent

case class Reduce[Expr, Result](
    initialExpression: Expr,
    `Reduce steps can not be empty`: Boolean,
    completeStepByStepReduce: List[ReduceStep[Expr]],
    fullyReducedResult: Result,
    `Reduce steps and result are not empty`: Boolean
) extends Thought.Opening

case class ReduceStep[Expr](
    ruleDescription: String,
    inputExpression: Expr,
    `Description of inputExpression`: String,
    `Method to apply rule`: String,
    outputExpression: Expr
) extends Thought.Opening
