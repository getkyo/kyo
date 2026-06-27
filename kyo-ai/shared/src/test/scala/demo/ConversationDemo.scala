package demo

import kyo.*

/** Conversation memory: a named instance remembers earlier turns, so each answer builds on the last.
  *
  * A travel chat where the second question ("there", "that month") only makes sense given the first
  * answer. `AI.init` mints a persistent instance whose conversation survives across `ai.gen` calls within
  * the run, so the model carries the destination it picked into the follow-up.
  *
  * Demonstrates: AI.initWith (named instance), ai.gen across turns (memory), LLM.run (auto-config)
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.ConversationDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.ConversationDemo"
  */
object ConversationDemo extends KyoApp:

    run {
        LLM.run {
            AI.initWith { chat =>
                for
                    first  <- chat.gen[String]("I want a weekend trip with great hiking. Name one destination and why, in one sentence.")
                    second <- chat.gen[String]("What is the single best month to visit there for hiking? One sentence, name the place.")
                    _      <- Console.printLine(s"turn 1: $first")
                    _      <- Console.printLine(s"turn 2: $second")
                yield ()
            }
        }
    }
end ConversationDemo
