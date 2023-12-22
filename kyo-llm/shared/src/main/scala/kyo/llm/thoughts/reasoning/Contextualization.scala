package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
        The Contextualization thought involves placing the input within a broader framework.
        - Assesses the relationship of input to previous discussions or knowledge.
        - Identifies broader context relevant to the input.
        - Considers implications of external factors.
        - Relevant techniques: Context Analysis, Pattern Recognition.
    """
)
case class Contextualization(
    `Determine the core message or question in the input`: String,
    `Assess how the current input relates to previous discussions or knowledge`: String,
    `Identify the broader context or background relevant to the input`: String,
    `Consider the implications of external factors or related concepts`: String,
    `Identify any ambiguous or unclear aspects that need further clarification`: String
)
