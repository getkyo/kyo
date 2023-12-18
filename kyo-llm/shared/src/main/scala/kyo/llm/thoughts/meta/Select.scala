package kyo.llm.thoughts.meta

import kyo.llm.ais._

object Select {

  val SelectDesc =
    p"""
      The Select thought facilitates the LLM's choice in focusing on particular reasoning processes.
      - Enables selective expansion on specific thoughts based on relevance and significance.
      - Provides flexibility in the reasoning pathway, allowing the LLM to prioritize and delve deeper into chosen thoughts.
      - Encourages a targeted approach to reasoning, ensuring a more efficient and relevant output.
      - The use of 'Option' in each thought or process allows for dynamic selection based on the context of the conversation.
    """
}
import Select._

@desc(SelectDesc)
case class Select[A, B](
    `First thought or process`: Option[A],
    `Second thought or process`: Option[B]
)

@desc(SelectDesc)
case class Select3[A, B, C](
    `First thought or process`: Option[A],
    `Second thought or process`: Option[B],
    `Third thought or process`: Option[C]
)

@desc(SelectDesc)
case class Select4[A, B, C, D](
    `First thought or process`: Option[A],
    `Second thought or process`: Option[B],
    `Third thought or process`: Option[C],
    `Fourth thought or process`: Option[D]
)

@desc(SelectDesc)
case class Select5[A, B, C, D, E](
    `First thought or process`: Option[A],
    `Second thought or process`: Option[B],
    `Third thought or process`: Option[C],
    `Fourth thought or process`: Option[D],
    `Fifth thought or process`: Option[E]
)
