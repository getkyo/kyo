package kyo.llm.thoughts.reasoning

import kyo.llm.ais._

@desc(
    p"""
      The Ecological thought considers environmental and ecological factors.
      - Focuses on understanding ecological interrelationships.
      - Relevant techniques: Ecosystem Analysis, Sustainability Assessment.
    """
)
case class Ecological(
    `Analyze ecological interrelationships and dependencies`: String,
    `Assess environmental impacts and sustainability factors`: String,
    `Explore ecological solutions and sustainable practices`: String
)
