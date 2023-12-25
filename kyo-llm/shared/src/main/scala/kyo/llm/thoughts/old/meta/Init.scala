package kyo.llm.thoughts.old.meta

import kyo.llm.ais._

@desc(
    p"""
      The Init thought guides the LLM in structuring reasoning to align with JSON schema.
      - Emphasizes schema compliance and logical reasoning.
      - Aids in reflecting on and improving from past outputs.
      - Ensures strict adherence to formatting rules.
    """
)
case class Init[T](
    `Reflect on past JSON errors`: Boolean,
    `Strategy for JSON schema compliance`: String,
    `Adhere strictly to JSON schema and formatting`: Boolean,
    value: T
)
