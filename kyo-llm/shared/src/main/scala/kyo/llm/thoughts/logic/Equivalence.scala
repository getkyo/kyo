package kyo.llm.thoughts.logic

import kyo.llm.ais._

@desc(
    p"""
      The EquivalenceChecking thought guides the LLM in determining if two boolean expressions are equivalent.
      - Involves step-by-step transformations and comparisons of the expressions.
      - Each step includes descriptions and results of transformations.
      - Continuously assesses the equivalence of the transformed expressions.
      - Concludes with a final determination of equivalence.
    """
)
case class EquivalenceChecking(
    initialExpr1: Expr,
    initialExpr2: Expr,
    equivalenceSteps: List[EquivalenceStep],
    finalEquivalence: Boolean
)

case class EquivalenceStep(
    stepDescription: String,
    expr1Transformation: Expr,
    expr2Transformation: Expr,
    areEquivalent: Boolean
)
