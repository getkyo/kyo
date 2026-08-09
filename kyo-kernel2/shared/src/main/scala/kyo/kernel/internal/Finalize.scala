package kyo.kernel.internal

import kyo.Chunk
import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.kernel.*
import kyo.kernel.internal.Kyo
import scala.annotation.tailrec

/** Carries a parked region at the front of its remainder: the drive installs it as the head of a foreign crossing's
  * continuation, and on resume it rebuilds the bracket around the rest of the computation, so the region's protections
  * (release on completion, at a region-exit crossing, on a throw, and on a discarded remainder) hold across the park.
  */
final private[kyo] class Finalize[R, S](val bracket: Kyo.Bracket[R, ?, S], val value: R, val rest: Arrow[Any, Any, Any])
    extends Arrow.Transform[Any, Any, S]:
    def frame = bracket.frame
    def run[C, S2](v: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (S & S2) =
        // the rest applies inside the rebuilt region: the Defer node keeps the application on
        // the region arm's stack, under its cleanup, and stays walkable on discard
        cont(
            Finalize.resumeRegion(
                bracket.asInstanceOf[Kyo.Bracket[Any, Any, Any]],
                value,
                Kyo.Defer(LiftMacro.defaultLift[Any, Any](v), rest)
            ).asInstanceOf[Any < (S & S2)],
            context,
            handlers
        )
end Finalize

/** The bracket machinery around the [[Kyo.Bracket]] node: the region park above, the region rebuild, the transforms the drive
  * parks on a suspended bracket, and the finalization that runs the releases a dropped remainder still carries.
  */
private[kyo] object Finalize:

    /** Rebuilds a region around an in-flight remainder: a settled bracket holding the resource whose use is the remainder
      * itself, so the drive's arm re-establishes the release-on-throw and region-exit protocol when it re-enters, and the
      * finalization walk finds the remainder through the [[Constant]] continuation on discard.
      */
    private[kyo] def resumeRegion(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, remainder: Any < Any): Kyo[Any, Any] =
        new Kyo.Bracket[Any, Any, Any]:
            def acquire                                            = LiftMacro.defaultLift(resource)
            def release(x: Any, outcome: Maybe[Result.Error[Any]]) = bracket.release(x, outcome)
            def cont                                               = new Constant(remainder)
            def frame                                              = bracket.frame
            override private[kyo] def settled                      = true
            override private[kyo] def crossingBuried               = true
    end resumeRegion

    /** Yields a fixed pending value, ignoring the input: the drive parks it where a remainder must survive a completed
      * step, and the finalization walk reads the carried value through it on discard.
      */
    final private[kyo] class Constant(val value: Any < Any) extends Arrow.Transform[Any, Any, Any]:
        def frame = Frame.internal
        def run[C, S2](x: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
            cont(value, context, handlers)
    end Constant

    private[kyo] def constant(v: Any < Any): Arrow[Any, Any, Any] = new Constant(v)

    private[kyo] def reacquire(bracket: Kyo.Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](r: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(
                    new Kyo.Bracket[Any, Any, Any]:
                        def acquire                                            = r
                        def release(x: Any, outcome: Maybe[Result.Error[Any]]) = bracket.release(x, outcome)
                        def cont                                               = bracket.cont
                        def frame                                              = bracket.frame
                        // the resumption value is the resource: nothing left to fold
                        override private[kyo] def settled =
                            true
                        override private[kyo] def crossingBuried =
                            bracket.crossingBuried
                    ,
                    context,
                    handlers
                )

    private[kyo] def cleanup(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource, Maybe(Result.Panic(t))).eval
        catch
            case t2: Throwable =>
                EffectTrace.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    private[kyo] inline def BracketDepth = 512

    private[kyo] def finalizeValue[A, S](v: A < S, outcome: Maybe[Result.Error[Any]]): Chunk[Throwable] =
        v match
            case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?]                    => finalizeArrow(kyo.cont, outcome)
            case kyo: Kyo.Defer[?, ?, ?]                               => finalizeArrow(kyo.cont, outcome)
            case b: Kyo.Bracket[Any, Any, Any] @unchecked if b.settled =>
                // a settled bracket is an acquired region parked as a rebuild (a preemption or a
                // resumed park): its release is owed. Regions nested in the remainder finalize
                // first through the continuation walk
                val inner = finalizeArrow(b.cont, outcome)
                try
                    val _ = b.release(Kyo.settled(b.acquire), outcome).asInstanceOf[Unit < Any].eval
                    inner
                catch
                    case t: Throwable =>
                        EffectTrace.attach(t, "release", b.frame)
                        inner.concat(Chunk(t))
                end try
            case seq: Kyo.Sequenced[?, ?, ?, ?] =>
                // the downstream may carry parked regions fused after this one: they finalize
                // after the region's own, preserving discard order
                finalizeValue(seq.bracket, outcome).concat(finalizeArrow(seq.after, outcome))
            case _ => Chunk.empty

    private def finalizeArrow(arrow: Any, outcome: Maybe[Result.Error[Any]]): Chunk[Throwable] =
        arrow match
            case finalize: Finalize[?, ?] =>
                // the rest of the parked region finalizes first: its releases belong to regions
                // nested inside this one
                val inner = finalizeArrow(finalize.rest, outcome)
                try
                    // the walk is structural, so the release row is existential here; running it
                    // through eval demands the closed row the bracket's construction guaranteed
                    val _ = finalize.bracket.release(finalize.value, outcome).asInstanceOf[Unit < Any].eval
                    inner
                catch
                    case t: Throwable =>
                        EffectTrace.attach(t, "release", finalize.bracket.frame)
                        inner.concat(Chunk(t))
                end try
            case c: Constant =>
                // a parked remainder carried past a completed step: regions in it finalize too
                finalizeValue(c.value, outcome)
            case r: ArrowEffect.Rotate[?, ?, ?] =>
                // a rotate step contains its handler's remaining chain: finalizers in there run too
                finalizeArrow(r.inner, outcome)
            case o: Arrow.Step[Any, Any, Any, Any] @unchecked =>
                finalizeChain(o, outcome)
            case at: Arrow.AndThen[?, ?, ?, ?] =>
                finalizeArrow(at.a, outcome).concat(finalizeArrow(at.b, outcome))
            case _ =>
                Chunk.empty

    private def finalizeChain(o: Arrow.Step[Any, Any, Any, Any], outcome: Maybe[Result.Error[Any]]): Chunk[Throwable] =
        @tailrec def loop(cur: Any, errors: Chunk[Throwable]): Chunk[Throwable] =
            cur match
                case o: Arrow.Step[Any, Any, Any, Any] @unchecked => loop(o.next, errors.concat(finalizeArrow(o.head, outcome)))
                case _                                            => errors
        loop(o, Chunk.empty)
    end finalizeChain

end Finalize
