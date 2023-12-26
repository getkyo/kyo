package kyo.llm.thoughts

import kyo._
import kyo.llm.ais._
import kyo.llm.thoughts._
import kyo.llm.KyoLLMApp

object ttt extends KyoLLMApp {
 
  run {
    for {
      ai <- AIs.init
      // _  <- ai.thought[Contextualize]
      // _  <- ai.thought[Elaborate] 
      // _  <- ai.thought[Brainstorm]
    } yield ai.gen[String]("what are the dangers of AI? what kind of apocalypse it could create?")
  }
}
