package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
      The Gen thought links a specific reasoning approach with a desired outcome.
      - 'thought': The reasoning method to be applied.
      - 'value': The outcome to be generated.
      - Guides the AI in applying the chosen reasoning to produce the specified result.
      - Example: Gen[Analysis, "Solutions"] uses analysis to generate solutions.
    """
)
case class Gen[T, U](
    thought: T,
    value: U
)
