package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Systems thought is designed to apply systems theory in AI reasoning.
      - Utilizes principles from systems theory to analyze component interactions.
      - Employs methods from complexity science for systemic dynamics evaluation.
      - Integrates techniques like causal loop diagramming for predicting outcomes.
      - Relevant techniques: Feedback Systems, Network Analysis.
    """
)
case class SystemsThinking(
    identifyComponents: IdentifyComponents,
    analyzeInteractions: AnalyzeInteractions,
    predictOutcomes: PredictOutcomes
)

case class IdentifyComponents(
    `List major elements of the system and their roles`: String,
    `Recognize relationships among system components`: String
)

case class AnalyzeInteractions(
    `Examine causal links within the system`: String,
    `Evaluate feedback loops and systemic dynamics`: String
)

case class PredictOutcomes(
    `Anticipate possible changes within the system`: String,
    `Propose potential interventions in the system`: String
)
