package kyo.llm.thoughts

import kyo.llm.ais._

@desc(
    p"""
      The Desc is a meta-thought that pairs a user-defined description with a thought.
      - 'desc': The user's description to consider when generating the thought.
      - 'thought': The actual thought or process to generate.
    """
)
case class Desc[D <: String, T](desc: D, thought: T)
