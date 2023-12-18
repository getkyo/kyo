package kyo.llm.thoughts.meta

import kyo.llm.ais._

object Chain {

  val chainDesc =
    p"""
    The Chain thoughts enable sequential linking of multiple reasoning processes.
    - Facilitates the flow of reasoning from one thought to another.
    - Ensures coherence and logical progression in the reasoning chain.
    - Allows complex reasoning to be broken down into manageable segments.
    """
}
import Chain._

@desc(chainDesc)
case class Chain[A, B](
    `First thought or process`: A,
    `Second thought or process`: B
)

@desc(chainDesc)
case class Chain3[A, B, C](
    `First thought or process`: A,
    `Second thought or process`: B,
    `Third thought or process`: C
)

@desc(chainDesc)
case class Chain4[A, B, C, D](
    `First thought or process`: A,
    `Second thought or process`: B,
    `Third thought or process`: C,
    `Fourth thought or process`: D
)

@desc(chainDesc)
case class Chain5[A, B, C, D, E](
    `First thought or process`: A,
    `Second thought or process`: B,
    `Third thought or process`: C,
    `Fourth thought or process`: D,
    `Fifth thought or process`: E
)
