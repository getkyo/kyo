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
    case class StartReasoning(
        `I understand that the fields under 'reasoning' are meant as a way for me to reason about the json I need to generate`: Boolean,
        `When generating the values for the 'reasoning' fields, I won't mention themselves in the reasoning process`: Boolean,
        `Some fields in the json schema have a 'const' definition. I understand the provided const value is the only possible value for the field`: Boolean
    )
    case class Contextualize(
        `When I disconsider the fields under 'reasoning' and analyze the other fields in the json schema, I briefly conclude the purpose of the other fields is`: String,
        `My goal generating this data is`: String,
        `Important restrictions I should take in consideration when generating the data`: String
    )
    case class Reflect(
        `I noticed that I previously failed to produce valid data`: Boolean,
        `The reasons I failed previously are`: List[String]
    )
    case class Step(
        name: String,
        description: String,
        `potential mistakes`: String
    )
    case class Plan(
        `Considering all the information provided so far, I'll produce a plan to generate high-quality data that satisfies the user's needs`: Boolean,
        `Formal techniques from literature I can be used to aid the generation`: String,
        `Additional informal techniques`: String,
        `Let's think step by step`: List[Step],
        `Now that I have the plan steps, I'll generate 'endReasoning'`: Boolean
    )
    case class EndReasoning(
        `This reasoning process was helpful to generate the json because`: String,
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

  case class Constrain[T, C <: String](
      `constraints to consider to proceed with the 'value' json generation`: C,
      value: T
  )

  type NonEmpty = "Non-empty"
}

object tt extends KyoLLMApp {

  import thoughts._
  import kyo.llm.ais._

  case class CQA(
      @desc("Excerpt from the input text")
      excerpt: Constrain[String, NonEmpty],
      @desc("An elaborate question regarding the excerpt")
      question: Constrain[String, NonEmpty],
      @desc("A comprehensive answer")
      answer: Constrain[String, NonEmpty]
  )
  case class Req(
      reasoning: Reasoning,
      @desc("Comprehensive set of questions covering all information in the input text")
      questions: Collect[Constrain[String, NonEmpty]],
      @desc("Process each question")
      processedQuestions: Constrain[Collect[CQA], NonEmpty]
  )

  run {
    AIs.gen[Req](text)
  }

  def text =
    p"""
    General relativity is a theory of gravitation developed by Einstein in the years 1907–1915. The development of general relativity began with the equivalence principle, under which the states of accelerated motion and being at rest in a gravitational field (for example, when standing on the surface of the Earth) are physically identical. The upshot of this is that free fall is inertial motion: an object in free fall is falling because that is how objects move when there is no force being exerted on them, instead of this being due to the force of gravity as is the case in classical mechanics. This is incompatible with classical mechanics and special relativity because in those theories inertially moving objects cannot accelerate with respect to each other, but objects in free fall do so. To resolve this difficulty Einstein first proposed that spacetime is curved. Einstein discussed his idea with mathematician Marcel Grossmann and they concluded that general relativity could be formulated in the context of Riemannian geometry which had been developed in the 1800s.[10] In 1915, he devised the Einstein field equations which relate the curvature of spacetime with the mass, energy, and any momentum within it.
    """
}
