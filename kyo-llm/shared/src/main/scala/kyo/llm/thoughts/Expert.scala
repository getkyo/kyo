package kyo.llm.thoughts

case class Expert(
    `I am an expert in the task requested by the user`: true,
    `My skills that can be useful to perform the task`: String
) extends Thought.Opening
