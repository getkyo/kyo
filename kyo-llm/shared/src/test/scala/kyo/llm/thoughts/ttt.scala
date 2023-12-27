package kyo.llm.thoughts

import kyo._
import kyo.llm.ais._
import kyo.llm.thoughts._
import kyo.llm.KyoLLMApp
import kyo.llm.configs.Config
import kyo.llm.configs.Model

object ttt extends KyoLLMApp {

  run {
    AIs.parallel(List.fill(1) {
      for {
        ai <- AIs.init
        _  <- ai.thought[RolePlay["You're an expert and a community lead in Scala"]]
        _  <- ai.thought[Contextualize]
        _  <- ai.thought[Brainstorm]
        _  <- ai.thought[Humorize]
        _  <- ai.thought[Elaborate]
      } yield ai.gen[String](
          "What's the future of Scala? Why does it have so many dramas?"
      )
    }).map(_.mkString("\n============================\n"))
  }
}
