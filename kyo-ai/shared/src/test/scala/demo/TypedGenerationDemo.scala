package demo

import kyo.*

/** Typed generation: ask once, get a decoded value back, never a string to parse.
  *
  * A trip planner asks the model for a structured city guide and uses the fields directly. `AI.gen[CityGuide]`
  * derives the result schema from the case class, calls the model, and decodes the reply into a typed
  * `CityGuide`; the model is required to fill every field. The no-argument `LLM.run` resolves the provider,
  * model, and key from the environment (the present API key selects the provider).
  *
  * Demonstrates: LLM.run (auto-config from the environment), AI.gen[T]
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.TypedGenerationDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.TypedGenerationDemo"
  */
object TypedGenerationDemo extends KyoApp:

    case class CityGuide(city: String, country: String, knownFor: List[String], bestMonths: List[String]) derives Schema

    run {
        for
            guide <- LLM.run(AI.gen[CityGuide]("Write a short travel guide for Kyoto, Japan."))
            _     <- Console.printLine(s"city:        ${guide.city}")
            _     <- Console.printLine(s"country:     ${guide.country}")
            _     <- Console.printLine(s"known for:   ${guide.knownFor.mkString(", ")}")
            _     <- Console.printLine(s"best months: ${guide.bestMonths.mkString(", ")}")
        yield ()
    }
end TypedGenerationDemo
