package kyo.llm

import kyo._
import kyo.llm.completions._

trait Mode {
  def apply(ai: AI)(next: AI => Completion < AIs): Completion < AIs
}

object Modes {

  private def toCut(mode: Mode) =
    new Cut[AI, Completion, AIs] {
      def apply[S2](v: AI < S2)(f: AI => Completion < (IOs & AIs)) =
        AIs.ephemeral {
          v.map(mode(_)(f))
        }
    }

  def enable[T, S](head: Mode, tail: Mode*)(v: T < S): T < (AIs & S) = {
    val cut = Aspects.chain(toCut(head), tail.map(toCut))
    AIs.completionAspect.let(cut)(v)
  }
}
