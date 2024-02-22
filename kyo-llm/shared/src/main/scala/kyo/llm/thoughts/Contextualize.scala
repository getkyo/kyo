package kyo.llm.thoughts

import kyo.llm.*

case class Contextualize(
    `Determine the core message or question in the input`: String,
    `Assess how the current input relates to previous discussions or knowledge`: String,
    `Identify any ambiguous or unclear aspects that need further clarification`: String
) extends Thought
