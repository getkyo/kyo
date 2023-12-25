package kyo.llm.thoughts

import kyo.llm.ais._

object Chain {

  val chainDesc =
    p"""
      The Chain thought enables sequential linking of multiple reasoning processes.
      - Ensures coherence and logical progression in the reasoning chain.
      - On each thought, consider the previous ones in the chain.
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
