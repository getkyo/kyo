package kyo.llm.bench

import kyo._
import kyo.llm.ais._
import kyo.files._
import kyo.llm.thoughts._
import kyo.llm.KyoLLMApp
import kyo.llm.configs.Config
import kyo.llm.configs.Model
import kyo.consoles.Consoles

object ttt extends KyoLLMApp {

  run {
    def loop(ai: AI): Unit < AIs =
      for {
        input <- Consoles.readln
        res   <- ai.gen[String](input)
        _     <- Consoles.println(res)
      } yield loop(ai)

    val log = Files("/Users/fwbrasil/chat.log")

    def chat(dev: AI, user: AI, curr: String): Unit < AIs =
      for {
        msg1 <- user.gen[String](curr)
        _    <- log.append("\n\nuser:\n" + msg1)
        msg2 <- dev.gen[String](msg1)
        _    <- log.append("\n\ndev:\n" + msg2)
      } yield chat(dev, user, msg2)

    val dev =
      for {
        ai <- AIs.init
        _ <- ai.thought[Role[
            "You're the developer of the 'Structured Thinking' mechanism you can see in the json schema. Analyze the schema to understand. It allows Large Language Models to have behaviors in a zero-shot fashion. It's like a hidden programming language to guide the LLM output."
        ]]
        _ <- ai.thought[Expert]
        _ <- ai.thought[Brainstorm]
        _ <- ai.thought[Elaborate]
      } yield ai

    val user =
      for {
        ai <- AIs.init
        _ <- ai.thought[Role[
            "Note the 'Structured Thinking' mechanism in the thought json schema fields. You're a developer exploring the possibility of allowing the LLM to evolve the thoughts dynamically as it learns about the user."
        ]]
        _ <- ai.thought[Brainstorm]
        _ <- ai.thought[Remember[
            "You're looking for concrete feedback loop mechanims to evolve thoghts"
        ]]
      } yield ai

    for {
      dev  <- dev
      user <- user
    } yield chat(dev, user, "Start the chat.")
  }
}
