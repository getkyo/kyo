package kyo.llm.thoughts

import kyo.llm.*

case class Think(
    `Consider all the information so far`: Boolean,
    `Reflect on the user's intent`: Boolean,
    `Let us think step-by-step`: List[String]
) extends Thought
