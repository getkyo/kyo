package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
      The Gen thought is structured to facilitate the generation of a specific outcome based on a given thought process.
      - The 'thought' field represents the thought process or reasoning approach that the AI should use.
      - The 'value' field is the specific outcome or result that should be generated using the specified thought process.
      - This thought encourages the AI to apply a particular reasoning method (as defined in 'thought') to produce a desired output (as specified in 'value').
      - Example: Gen[Analysis, "Potential solutions to a problem"] would direct the AI to use analytical reasoning to generate potential solutions to the given problem.
    """
)
case class Gen[T, U](
    thought: T,
    value: U
)
