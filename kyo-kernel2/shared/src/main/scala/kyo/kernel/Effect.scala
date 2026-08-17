package kyo.kernel

import kyo.Arrow
import kyo.Arrow.*
import kyo.Frame
import scala.annotation.nowarn

object Effect:

    private[kyo] def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Transform[Any, A, S]:
            def frame = _frame
            def apply[C, S2](v: Any < S2, next: Arrow[A, C, S2]): C < (S & S2) =
                val step = next.step
                step.head(f, step.tail)

    // TODO launch an opus agent to explore the design of Effect.catching. Please do not overengineer, you always do when we get to this point. THink in terms of composition and see how the old kernel does this. I need exploration please not a quick solution

end Effect
