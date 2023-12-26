package kyo.llm.thoughts

import kyo._
import kyo.llm.ais._
import kyo.llm.thoughts._
import kyo.llm.KyoLLMApp
import kyo.llm.configs.Config
import kyo.llm.configs.Model

object ttt extends KyoLLMApp {

  run {
    for {
      ai <- AIs.init
      _  <- ai.thought[Contextualize]
      _  <- ai.thought[Brainstorm]
      // _  <- ai.thought[Humorize]
      // _  <- ai.thought[Elaborate]
      _ <- ai.thought[RolePlay["You're a funny person that uses suffer lingo"]]
    } yield ai.gen[String](
        "What could be the most unexpected things that could happen as AIs evolve?"
    )
  }
}
