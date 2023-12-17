package kyo.llm

import kyo.llm.ais._

object thoughts {

  import Reasoning._

  case class Reasoning(
      startReasoning: StartReasoning,
      contextualize: Contextualize,
      reflect: Reflect,
      plan: Plan,
      endReasoning: EndReasoning
  )

  object Reasoning {
    case class Contextualize(
        `When I disconsider the fields under 'reasoning' and analyze the other fields in the json schema, I briefly conclude the purpose of the other fields is`: String,
        `My goal generating this data is`: String,
        `Important restrictions I should take in consideration when generating the data`: String
    )
    case class Reflect(
        `I noticed that I previously failed to produce valid data`: Boolean,
        `The reasons I failed previously are`: List[String]
    )
    case class Plan(
        `Considering all the information provided so far, I'll produce a plan to generate high-quality data that satisfies the user's needs`: Boolean,
        `Known formal and informal techniques that can help in the generation of the data`: String,
        `Steps to generate the data`: List[String],
        `Most likely reasons I might fail to generate high-quality data`: List[String]
    )
    case class StartReasoning(
        `I understand that the fields under 'reasoning' is meant as a way for me to reason about the json I need to generate`: Boolean,
        `When generating the values for the 'reasoning' fields, I won't mention themselves in the reasoning process`: Boolean
    )
    case class EndReasoning(
        `I understand the reasoning process has ended and will now focus on generating actual data requested by the user based on the reasoning`: Boolean
    )
  }

  import Collect._
  case class Collect[T](
      `I'll now generate a complete array with as elements as necessary to fully satisfy the user's request`: Boolean,
      `Number of elements I can generate`: Int,
      elements: List[Element[T]]
  ) {
    def list: List[T] = elements.map(_.elementValue)
  }

  object Collect {
    case class Element[T](
        `Brief description of the facts that support this information`: List[String],
        elementValue: T,
        `Should I continue generating more elements`: Boolean
    )
  }

  case class NonEmptyString(
      `I understand I can not generate an empty string in the next field`: Boolean,
      nonEmptyString: String
  ) {
    def string: String = nonEmptyString
  }

  case class Constrain[T, C <: String](
      @desc("Constraints to consider when generating the value")
      constraints: C,
      value: T
  )
}
