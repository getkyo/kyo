package kyo.llm.thoughts

import kyo.llm._

case class Expert(
    `I am an expert in the task requested by the user`: Boolean,
    `My skills from literature and otherwise to perform the task`: String
) extends Thought
