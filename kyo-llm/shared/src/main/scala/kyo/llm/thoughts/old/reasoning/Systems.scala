package kyo.llm.thoughts.old.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Systems thought applies systems theory principles in AI reasoning.
      - Analyzes interactions within systems.
      - Evaluates systemic dynamics and feedback loops.
      - Predicts outcomes using network analysis and causal diagrams.
    """
)
case class Systems(
    `List system elements and their roles`: String,
    `Examine causal links and feedback dynamics`: String,
    `Anticipate system changes and propose interventions`: String
)
