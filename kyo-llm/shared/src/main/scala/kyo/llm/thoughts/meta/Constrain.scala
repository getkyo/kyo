package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
    The Constrain thought is a tool for applying specific constraints to the AI's reasoning process.
    - The `Apply specific constraints to the value generation` field, defined as a string literal type, outlines the nature of the constraints that should govern the AI's thought generation.
    - The `value` is the main focus where the AI applies the defined constraints during its reasoning or output generation.
    - This mechanism is instrumental in guiding the AI to produce results within specific parameters or conditions, enhancing the relevance and precision of the output.
    - Example: Constrain["Must be within ethical guidelines", EthicalReasoning] would direct the AI to ensure that the reasoning adheres to ethical guidelines.
    """
)
case class Constrain[C <: String, T](
    `Apply specific constraints to the value generation`: C,
    value: T
)
