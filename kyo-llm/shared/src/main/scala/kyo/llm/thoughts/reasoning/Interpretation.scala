package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Interpretation thought focuses on the initial understanding of the input.
      - Determines the core message or question in the input.
      - Identifies ambiguous aspects needing clarification.
      - Extracts key terms for deeper analysis.
      - Relevant techniques: Textual Analysis, Conceptual Clarification.
    """
)
case class Interpretation(
    `Determine the core message or question in the input`: String,
    `Identify any ambiguous or unclear aspects that need further clarification`: String,
    `Extract key terms and concepts for deeper analysis`: String
)
