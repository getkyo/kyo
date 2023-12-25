package kyo.llm.thoughts

import kyo.llm.ais._

object Select {

  val SelectDesc =
    p"""
      The Select thought allows you to focus on specific reasoning processes.
      - Analyze the json schema of each thought
      - Generate all thoughts that might be beneficial to improve your reasoning.
    """
}

import Select._

@desc(SelectDesc)
case class Select[A, B](
    `Analyze thought json schemas`: String,
    `Generate all useful thoughts`: Boolean,
    `First thought`: Option[A],
    `Second thought`: Option[B]
)

@desc(SelectDesc)
case class Select3[A, B, C](
    `Analyze thought json schemas`: String,
    `Generate all useful thoughts`: Boolean,
    `First thought`: Option[A],
    `Second thought`: Option[B],
    `Third thought`: Option[C]
)

@desc(SelectDesc)
case class Select4[A, B, C, D](
    `Analyze thought json schemas`: String,
    `Generate all useful thoughts`: Boolean,
    `First thought`: Option[A],
    `Second thought`: Option[B],
    `Third thought`: Option[C],
    `Fourth thought`: Option[D]
)

@desc(SelectDesc)
case class Select5[A, B, C, D, E](
    `Analyze thought json schemas`: String,
    `Generate all useful thoughts`: Boolean,
    `First thought`: Option[A],
    `Second thought`: Option[B],
    `Third thought`: Option[C],
    `Fourth thought`: Option[D],
    `Fifth thought`: Option[E]
)
