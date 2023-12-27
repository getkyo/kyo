package kyo.llm.thoughts

final case class Remember[T <: String](
  `Remeber`: T
) extends Thought.Opening
