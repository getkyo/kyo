package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
    The Constrain thought applies specific constraints to the AI's reasoning.
    - Outlines constraints in the 'Apply specific constraints to the value generation' field.
    - 'value' is where the AI applies these constraints in its process.
    - Aims to enhance output precision and relevance.
    - Example: Constrain["Adhere to ethical guidelines", EthicalReasoning] guides the AI to follow ethical standards.
    """
)
case class Constrain[C <: String, T](
    `Apply specific constraints`: C,
    value: T
)
