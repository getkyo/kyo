package demo

import kyo.*

/** A stateful agent: an actor-backed entity that remembers across `ask`s.
  *
  * Unlike a one-shot `AI.gen`, an `Agent` is a persistent actor whose conversation survives between calls,
  * so the second `ask` answers in light of the first. `Agent.run` mints one stable instance, runs the
  * behavior inside the actor fiber, and the parked continuation keeps the memory alive.
  *
  * Demonstrates: Agent.run (no-config, env-default), agent.ask, memory across asks
  * Run on OpenAI:    OPENAI_API_KEY=...    sbt "kyo-aiJVM/Test/runMain demo.AgentDemo"
  * Run on Anthropic: ANTHROPIC_API_KEY=... sbt "kyo-aiJVM/Test/runMain demo.AgentDemo"
  */
object AgentDemo extends KyoApp:

    case class Question(text: String) derives Schema

    run {
        Agent.run[Question] { (self, q) =>
            self.gen[String](q.text)
        }.map { tutor =>
            for
                a1 <- tutor.ask(Question("I'm new to programming and I like puzzles and games. Reply in one sentence."))
                a2 <- tutor.ask(Question("Given that, suggest one first project for me, in one sentence."))
                _  <- Console.printLine(s"ask 1: $a1")
                _  <- Console.printLine(s"ask 2: $a2")
            yield ()
        }
    }
end AgentDemo
