package kyo.llm.thoughts

import kyo.llm.ais._

@desc(
    "All text and any other field from this point on must be complete and with **as much detail as possible**. Perfer long texts."
)
case class Elaborate(
    `I'll generate a json that is complete and as detailed as possible`: Boolean,
    `Stragegy to generate a complete and well elaborated output`: String
) extends Thought.Opening
