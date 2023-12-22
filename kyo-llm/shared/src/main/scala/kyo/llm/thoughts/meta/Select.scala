package kyo.llm.thoughts.meta

import kyo.llm.ais._

object Select {

  val SelectDesc =
    p"""
      The Select thought allows the LLM to focus on specific reasoning processes.
      - Facilitates choice and prioritization of thoughts for relevance.
      - Enables a flexible and targeted reasoning approach.
      - Uses optional fields for dynamic thought selection based on context.
    """
}

import Select._

@desc(SelectDesc)
case class Select[A, B](
    `First thought`: Option[A],
    `Second thought`: Option[B]
)

@desc(SelectDesc)
case class Select3[A, B, C](
    `First thought`: Option[A],
    `Second thought`: Option[B],
    `Third thought`: Option[C]
)

@desc(SelectDesc)
case class Select4[A, B, C, D](
    `First thought`: Option[A],
    `Second thought`: Option[B],
    `Third thought`: Option[C],
    `Fourth thought`: Option[D]
)

@desc(SelectDesc)
case class Select5[A, B, C, D, E](
    `First thought`: Option[A],
    `Second thought`: Option[B],
    `Third thought`: Option[C],
    `Fourth thought`: Option[D],
    `Fifth thought`: Option[E]
)
