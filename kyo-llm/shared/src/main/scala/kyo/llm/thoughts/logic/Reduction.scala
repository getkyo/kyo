package kyo.llm.thoughts.logic

import kyo.llm.ais._

@desc(
    p"""
      The Boolean thought applies algebraic principles to simplify a boolean expression.
      - Outlines each step of the reduction process with rule descriptions and expression transformations.
      - Iteratively simplifies the expression using boolean algebra rules.
      - Aimed at reducing the expression to True or False.
    """
)
case class Reduction(
    initialExpression: Expr,
    reductionSteps: List[ReductionStep],
    finalOutcome: Expr
)

case class ReductionStep(
    ruleDescription: String,
    inputExpression: Expr,
    outputExpression: Expr
)
