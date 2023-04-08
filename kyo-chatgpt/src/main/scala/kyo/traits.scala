package kyo

import kyo.core._
import kyo.ais._
import kyo.envs._
import kyo.locals._
import kyo.ais._
import kyo.ios._
import kyo.aspects._
import kyo.consoles._

object traits {

  object Traits {

    private object SelfReflection extends AIs.askAspect.Handle {
      def apply[S2](tup: (AI, String))(next: ((AI, String)) => String > (AIs | S2 | Aspects))
          : String > (AIs | S2 | Aspects) =
        val (ai, msg) = tup
        val reflect =
          for {
            _ <- ai.ask(
                "Provide an analysis of possible improvements of your previous response " +
                  "and report any mistakes. One sentence to be read by you later."
            )
            r <- ai.ask(
                s"Please improve your original response."
            )
          } yield r
        AIs.iso(next(tup)(result => AIs.ephemeral(reflect)))
    }

    def selfReflection[T, S](v: T > (S | AIs)): T > (S | AIs) =
      AIs.iso(SelfReflection(v))
  }
}
