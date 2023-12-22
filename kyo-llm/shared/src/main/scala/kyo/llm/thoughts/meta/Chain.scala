package kyo.llm.thoughts.meta

import kyo.llm.ais._

object Chain {

  val chainDesc =
    p"""
      The Chain thought enable sequential linking of multiple reasoning processes.
      - Facilitates the flow of reasoning from one thought to another.
      - Ensures coherence and logical progression in the reasoning chain.
    """
}
import Chain._

@desc(chainDesc)
case class Chain[A, B](
    `First thought`: A,
    `Second thought`: B
)

@desc(chainDesc)
case class Chain3[A, B, C](
    `First thought`: A,
    `Second thought`: B,
    `Third thought`: C
)

@desc(chainDesc)
case class Chain4[A, B, C, D](
    `First thought`: A,
    `Second thought`: B,
    `Third thought`: C,
    `Fourth thought`: D
)

@desc(chainDesc)
case class Chain5[A, B, C, D, E](
    `First thought`: A,
    `Second thought`: B,
    `Third thought`: C,
    `Fourth thought`: D,
    `Fifth thought`: E
)
