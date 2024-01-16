package kyo.llm.thoughts

import kyo.llm._

case class Refine[T](
    initialSolution: T,
    `Strategy produce a different solution`: String,
    alternativeSolution: T,
    `Elaborate analysis of initialSolution`: String,
    `Elaborate analysis of alternativeSolution`: String,
    `Differences between the solutions`: String,
    `Aspects to double check for mistakes`: List[String],
    `Elaborate on how to fix mistakes`: String,
    finalCorrectSolution: T
) extends Thought
