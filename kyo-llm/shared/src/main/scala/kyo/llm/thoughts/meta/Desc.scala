package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
      The Desc thought pairs a user-defined description with a thought.
      - 'desc': A label or brief explanation as a string literal.
      - 'value': The actual thought or process to generate.
      - Example: Desc["brainstorm", Chain[Creative, Critical]] guides the LLM for a brainstorming thought process.
    """
)
case class Desc[D <: String, T](desc: D, value: T)
