package demo

import kyo.*

/** Self-consistency, built as a custom `Mode`.
  *
  * A `Mode` wraps a generation. This one runs N independent takes in parallel (each context-isolated via
  * `AI.forget`, each with a different seed), then injects them and asks the model to synthesize one answer,
  * keeping facts that recur across takes and dropping one-off ones (likely hallucinations). It varies the
  * seed rather than the temperature, so it works on every model.
  *
  * Demonstrates: a custom `Mode` (new Mode[Any]), AI.forget, Async parallel generations, AI.withConfig
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.SamplingDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.SamplingDemo"
  */
object SamplingDemo extends KyoApp:

    def selfConsistency(n: Int): Mode[Any] =
        new Mode[Any]:
            def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
                Frame
            ): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
                AI.forget {
                    Async.foreach(0 until n) { i =>
                        AI.withConfig(_.seed(i))(Abort.run[AIGenException](gen).map(_.getOrThrow))
                    }
                }.map { takes =>
                    ai.systemMessage(
                        "These answers came from independent takes. Synthesize ONE answer, keeping only the " +
                            "facts that recur across takes and dropping any that appear in just one (likely " +
                            "hallucinations):\n\n" + takes.flatten.map(a => Json.encode(a)).mkString("\n\n")
                    ).andThen(gen)
                }

    run {
        LLM.run {
            AI.enable(selfConsistency(3)) {
                AI.gen[String]("State three well-established facts about the planet Mars, one short sentence each.")
            }
        }.map(answer => Console.printLine(s"synthesized from 3 takes:\n$answer"))
    }
end SamplingDemo
