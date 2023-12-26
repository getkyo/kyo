package kyo.llm.thoughts

import kyo.llm.ais._

@desc(
    "All text and any other field from this point on must be complete and with **as much detail as possible**. Perfer long texts."
)
case class Elaborate(
    @desc("Consider all thoughts so far and the user request")
    `Strategy to elaborate`: String,
    `Generate a json that is complete and as detailed as possible`: Boolean,
    `Strategy to generate long texts`: String,
    @desc("Generate bulleted lists with detailed items if appropiate.")
    `I'll elaborate as much as possible and use bulleted lists if appropiate`: Boolean
) extends Thought.Opening
