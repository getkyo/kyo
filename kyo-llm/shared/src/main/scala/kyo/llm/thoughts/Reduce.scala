package kyo.llm.thoughts

import kyo._

import kyo.llm._

case class Reduce[Expr, Result](
    initialExpression: Expr,
    `Reduce steps can not be empty`: Boolean,
    completeStepByStepReduce: List[ReduceStep[Expr]],
    fullyReducedResult: Result,
    `Reduce steps and result are not empty`: Boolean
) extends Thought

case class ReduceStep[Expr](
    ruleDescription: String,
    inputExpression: Expr,
    `Description of inputExpression`: String,
    `Method to apply rule`: String,
    outputExpression: Expr
)
