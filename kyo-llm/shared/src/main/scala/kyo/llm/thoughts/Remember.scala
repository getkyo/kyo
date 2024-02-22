package kyo.llm.thoughts

import kyo.llm.*

final case class Remember[T <: String](
    `Remeber`: T
) extends Thought
