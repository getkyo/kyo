package kyo.llm.thoughts

import kyo.llm.ais._

@desc(
    p"""
      The Contextualize thought involves analyzing the previous interactions to
      inform your plans on how to handle the user's request.
      - Assesses the relationship of input to previous discussions or knowledge.
      - Identifies broader context relevant to the input.
      - Relevant techniques: Context Analysis, Pattern Recognition.
    """
)
case class Contextualize(
    `Determine the core message or question in the input`: String,
    `Assess how the current input relates to previous discussions or knowledge`: String,
    `Identify the broader context or background relevant to the input`: String,
    `Identify any ambiguous or unclear aspects that need further clarification`: String
) extends Thought.Opening
