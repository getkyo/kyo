package kyo.llm.thoughts

import kyo.llm._

final case class Remember[T <: String](
    `Remeber`: T
) extends Thought
