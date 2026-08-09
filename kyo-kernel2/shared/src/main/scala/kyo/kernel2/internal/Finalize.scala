package kyo.kernel2.internal

import kyo.Chunk
import kyo.Frame
import kyo.kernel2.*
import kyo.kernel2.internal.Kyo
import scala.annotation.tailrec

final private[kyo] class Finalize[R, A, S](val bracket: Kyo.Bracket[R, ?, S], val value: R)
    extends Arrow.Transform[A, A, S]:
    def frame = bracket.frame
    def run[C, S2](v: A, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (S & S2) =
        cont(Finalize.yieldValue(v)(bracket.release(value), context, handlers), context, handlers)
end Finalize

/** The bracket machinery around the [[Kyo.Bracket]] node: the release step above, the transforms the drive parks on a suspended
  * bracket, and the finalization that runs the releases a dropped remainder still carries.
  */
private[kyo] object Finalize:

    private def yieldValue[A](v: A): Arrow[Unit, A, Any] =
        val lifted = LiftMacro.defaultLift[A, Any](v)
        new Arrow.Transform[Unit, A, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Unit, context: Context, handlers: Handlers, cont: Arrow[A, C, S2]): C < (Any & S2) =
                cont(lifted, context, handlers)
        end new
    end yieldValue

    private[kyo] def constant(v: Any < Any): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](x: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(v, context, handlers)

    private[kyo] def reacquire(bracket: Kyo.Bracket[Any, Any, Any]): Arrow[Any, Any, Any] =
        new Arrow.Transform[Any, Any, Any]:
            def frame = Frame.internal
            def run[C, S2](r: Any, context: Context, handlers: Handlers, cont: Arrow[Any, C, S2]): C < (Any & S2) =
                cont(
                    new Kyo.Bracket[Any, Any, Any]:
                        def acquire         = r
                        def release(x: Any) = bracket.release(x)
                        def cont            = bracket.cont
                        def frame           = bracket.frame
                        // the resumption value is the resource: nothing left to fold
                        override private[kyo] def settled =
                            true
                    ,
                    context,
                    handlers
                )

    private[kyo] def cleanup(bracket: Kyo.Bracket[Any, Any, Any], resource: Any, t: Throwable): Unit =
        try
            val _ = bracket.release(resource).eval
        catch
            case t2: Throwable =>
                EffectTrace.attach(t2, "release", bracket.frame)
                t.addSuppressed(t2)

    private[kyo] inline def BracketDepth = 512

    private[kyo] def finalizeValue[A, S](v: A < S): Chunk[Throwable] =
        v match
            case kyo: Kyo.Suspend[?, ?, ?, ?, ?, ?] => finalizeArrow(kyo.cont)
            case kyo: Kyo.Defer[?, ?, ?]            => finalizeArrow(kyo.cont)
            case _                                  => Chunk.empty

    private def finalizeArrow(arrow: Any): Chunk[Throwable] =
        arrow match
            case finalize: Finalize[?, ?, ?] =>
                try
                    val _ = finalize.bracket.release(finalize.value).asInstanceOf[Unit < Any].eval
                    Chunk.empty
                catch
                    case t: Throwable =>
                        EffectTrace.attach(t, "release", finalize.bracket.frame)
                        Chunk(t)
            case r: ArrowEffect.Rotate[?, ?, ?] =>
                // a rotate step contains its handler's remaining chain: finalizers in there run too
                finalizeArrow(r.inner)
            case o: Arrow.Step[Any, Any, Any, Any] @unchecked =>
                finalizeChain(o)
            case at: Arrow.AndThen[?, ?, ?, ?] =>
                finalizeArrow(at.a).concat(finalizeArrow(at.b))
            case _ =>
                Chunk.empty

    private def finalizeChain(o: Arrow.Step[Any, Any, Any, Any]): Chunk[Throwable] =
        @tailrec def loop(cur: Any, errors: Chunk[Throwable]): Chunk[Throwable] =
            cur match
                case o: Arrow.Step[Any, Any, Any, Any] @unchecked => loop(o.next, errors.concat(finalizeArrow(o.head)))
                case _                                            => errors
        loop(o, Chunk.empty)
    end finalizeChain

end Finalize
