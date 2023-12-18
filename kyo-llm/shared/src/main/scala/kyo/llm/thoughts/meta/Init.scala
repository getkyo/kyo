package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
      The Init thought sets foundational guidelines for the LLM's reasoning process, especially in adhering to the JSON schema.
      - **Crucially, the LLM must never generate arbitrary fields and should strictly follow the provided JSON schema.**
      - This thought ensures that 'value' represents specific, predefined thoughts or reasoning steps.
      - Acts as a blueprint for integrating thoughts into a logical and schema-compliant reasoning pathway.
      - Aims for well-structured responses that align with the user's intent and adhere to JSON formatting rules.
      - Fields focus on reflecting upon past errors and strategizing to avoid them, especially in maintaining JSON schema integrity.
      - Example: A 'value' containing Chain[Analysis, Synthesis] should be elaborated in accordance with the schema, without adding extraneous fields.
      - **The LLM is required to ensure compliance with the JSON schema at all times.**
    """
)
case class Init[T](
    `Have you failed at previous attempts to generate this json`: Boolean,
    `Analysis of the previous failures`: String,
    `Strategy to avoid another failure`: String,
    `Elaborate on the strategy to avoid failures`: String,
    `The generated json can't have new line characters`: Boolean,
    `I understand I can't use new lines`: Boolean,
    `Strictly adhere to the provided JSON schema without adding arbitrary fields`: Boolean,
    value: T
)
