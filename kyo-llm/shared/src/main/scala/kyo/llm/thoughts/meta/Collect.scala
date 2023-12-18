package kyo.llm.thoughts.meta

import kyo.llm.ais._

@desc(
    p"""
    The Collect thought focuses on assembling a comprehensive array of elements.
    - Generates a complete array to fully satisfy the user's request.
    - Determines the number of elements necessary for completeness.
    - Each element is supported by a brief factual description.
    """
)
case class Collect[T](
    `Generate a complete array to satisfy the user's request`: Boolean,
    `Determine the number of elements to generate`: Int,
    elements: List[Collect.Element[T]]
) {
  def list: List[T] = elements.map(_.elementValue)
}

object Collect {
  @desc(
      p"""
      Element within Collect represents an individual item in the collection.
      - Each item is accompanied by a brief factual description.
      - Decision to continue generating more elements is considered.
      """
  )
  case class Element[T](
      `Provide factual description for the element`: List[String],
      elementValue: T,
      `Decide if more elements should be generated`: Boolean
  )
}
