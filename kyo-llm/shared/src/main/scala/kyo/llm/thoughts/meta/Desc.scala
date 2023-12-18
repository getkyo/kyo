package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
    The Desc thought is designed to encapsulate a thought along with a user-defined description.
    - 'desc' is a string literal type that acts as a label or a brief explanation of the thought process.
    - 'value' is the actual thought or process that needs to be generated or executed.
    - This class guides the LLM to focus on generating the 'value' based on the context provided by 'desc'.
    - Example user code that generates this thought Desc["brainstorm something", Chain[Creative, Critical]] indicates 
      that the LLM should engage in a creative and critical thought chain under the theme of 'brainstorm something'.
    """
)
case class Desc[D <: String, T](desc: D, value: T)
