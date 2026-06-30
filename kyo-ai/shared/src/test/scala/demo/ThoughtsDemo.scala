package demo

import kyo.*
import kyo.schema.doc

/** Structured reasoning: the model must produce a typed reasoning field before its answer.
  *
  * A classic trap question (the bat-and-ball problem, whose fast answer 10 is wrong; the answer is 5). An
  * opening `Thought` forces the model to fill a `Reasoning.steps` field first, which conditions the answer
  * it then commits to. The `process` hook prints that reasoning, so you see the chain of thought.
  *
  * Demonstrates: Thought.opening with a process hook, AI.enable, AI.gen[T]
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.ThoughtsDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.ThoughtsDemo"
  */
object ThoughtsDemo extends KyoApp:

    case class Reasoning(@doc("work through the problem step by step before answering") steps: String) derives Schema
    case class Answer(@doc("the final answer, in cents") cents: Int) derives Schema

    // The process hook fires on the decoded reasoning; here it just prints it.
    val reasonFirst = Thought.opening[Reasoning](r => Console.printLine(s"reasoning: ${r.steps}"))

    run {
        LLM.run {
            AI.enable(reasonFirst) {
                AI.gen[Answer](
                    "A bat and a ball cost $1.10 in total. The bat costs $1.00 more than the ball. How much does the ball cost, in cents?"
                )
            }
        }.map(answer => Console.printLine(s"answer: ${answer.cents} cents"))
    }
end ThoughtsDemo
