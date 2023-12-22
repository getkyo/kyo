package kyo.llm.thoughts.logic

import kyo.llm.ais._

@desc(
    p"""
      The ExpressionOptimization thought process aims to simplify a boolean expression.
      - Involves applying logical simplification steps to the expression.
      - Each step aims to reduce the complexity and size of the expression.
      - Results in an optimized expression that is logically equivalent to the original.
    """
)
case class ExpressionOptimization(
    originalExpression: Expr,
    optimizationSteps: List[OptimizationStep],
    optimizedExpression: Expr
)

case class OptimizationStep(
    stepDescription: String,
    inputExpression: Expr,
    outputExpression: Expr
)
