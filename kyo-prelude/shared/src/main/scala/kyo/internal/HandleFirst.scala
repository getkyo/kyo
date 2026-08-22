package kyo.internal

import kyo.*
import kyo.kernel.ArrowEffect

/** Handles exactly the first suspension of an effect, handing the raw remainder to the clause.
  *
  * The old kernel carried this as `ArrowEffect.handleFirst`; kernel2 deliberately does not, because it is
  * expressible with `handleCont` and one marker value. The region's body wraps its result in `Done`, and
  * the clause answers the first suspension by returning `Suspended(input, cont)` outright, which completes
  * the region on the spot: no later suspension ever reaches the handler, and the dumped continuation
  * inside `Suspended` is the raw remainder, with the effect still in its row. The wrapper is unwrapped
  * outside the region, where `handle` receives the input and a continuation that strips the `Done` the
  * body's map added. The remainder must stay raw because every consumer re-handles it with a fresh
  * clause: the stream zip family feeds it back through this helper with different leftovers per round.
  */
private[kyo] object HandleFirst:

    final private class Done(val a: Any)
    final private class Suspended(val input: Any, val cont: kyo.Arrow[Any, Any, Any])

    def apply[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](tag: Tag[E], v: A < (E & S))(
        handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
        done: A => B < S2
    )(using Frame): B < (S & S2) =
        val first: Any < S =
            ArrowEffect.handleCont(tag, v.map(a => new Done(a): Any))(
                [C] => (input, cont) => new Suspended(input, cont.asInstanceOf[kyo.Arrow[Any, Any, Any]])
            )
        first.map {
            case d: Done      => done(d.a.asInstanceOf[A])
            case s: Suspended =>
                // the C the suspension was made at is existential here; the erased instantiation is the
                // same liberty the kernel's own decompose hook takes
                handle[Any](
                    s.input.asInstanceOf[I[Any]],
                    o =>
                        s.cont(o.asInstanceOf[Any]).asInstanceOf[Any < (E & S)].map {
                            case d: Done => d.a.asInstanceOf[A]
                            case other   => bug(s"handleFirst remainder ended without its Done marker: $other")
                        }
                )
            case other => bug(s"handleFirst region ended without a marker: $other")
        }
    end apply
end HandleFirst
