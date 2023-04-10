package kyo

import core._
import aspects._
import ais._

object reflects {

  private class Reflects(val prompt: String, ais: Set[AI]) extends Trait(ais) {
    def apply[S](ai: AI, msg: String)(next: String => String > (S | Aspects)) =
      for {
        r1 <- AIs.ephemeral(next(msg))
        _  <- ai.user(msg)
        r2 <- ai.ask(
            "This could be your response:\n\n" + r1 + "\n\n\n" + prompt + " Provide an improved response without mentioning this prompt."
        )
      } yield r2
  }

  object Reflects {

    def apply[T, S](ais: Set[AI])(v: T > (S | AIs)): T > (S | AIs) =
      apply("Please ensure the user request is satisfied and correct any mistakes", ais)(v)

    def apply[T, S](prompt: String, ais: Set[AI])(v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(AIs.askAspect.let(new Reflects(prompt, ais.toSet))(v))
  }
}
