package kyo.llm.thoughts

import kyo.llm.ais._

case class Elaborate(
    `Generate a json that is complete and as detailed as possible`: true,
    `Strategy to elaborate outputs and generate long texts`: String,
    `I'll elaborate the outputs and use bulleted lists if appropiate`: true
) extends Thought
