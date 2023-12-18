package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
    The Constrain thought involves applying constraints to the reasoning process.
    - Focuses on considering specific constraints for value generation.
    - Ensures that the generated values adhere to defined constraints.
    """
)
case class Constrain[T, C <: String](
    `Apply specific constraints to the value generation`: C,
    value: T
)
