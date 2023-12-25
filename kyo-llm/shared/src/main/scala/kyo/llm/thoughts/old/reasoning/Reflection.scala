package kyo.llm.thoughts.old.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Reflection thought involves reviewing the reasoning process.
      - Identifies errors or biases in reasoning.
      - Considers ways to improve future responses.
      - Relevant techniques: Reflective Practice, Bias Identification.
    """
)
case class Reflection(
    `Review reasoning process for errors or biases`: String,
    `Consider improvements for future reasoning`: String,
    `Reflect on the effectiveness of the reasoning approach`: String
)
