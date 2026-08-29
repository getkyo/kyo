package kyo.proto.kernel

import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.internal.*
import kyo.proto.kernel.internal.Kyo.*
import language.implicitConversions
import scala.annotation.nowarn

abstract class Effect private[kernel] ()

/** The internal effect a bracket's extent is named by. Never suspended and never handled by name: the region's tag answers "not mine" for
  * every real suspend, so everything crosses as foreign and the extent's own edges, done and recover, are all the bracket speaks.
  */
sealed abstract private[kyo] class Bracket private () extends Effect

object Effect:

    /** Reifies the application of a continuation to a computation as a record. The raw factory: callers know their shapes, so no
      * normalization happens here; value composition with the free-slot laws is `<.chain`'s job.
      */
    def defer[A, B, S](v: A < S, cont: Arrow[A, B, S]): B < S =
        cont match
            case cont: Arrow.Chain[A, x, B, S] @unchecked =>
                new Defer[A, x, B, S]:
                    def value = v
                    def contA = cont.a
                    def contB = cont.b
            case _ =>
                new Defer[A, B, B, S]:
                    def value = v
                    def contA = cont
                    def contB = Arrow.id

    def defer[A, B, C, S](v: A < S, cont1: Arrow[A, B, S], cont2: Arrow[B, C, S]): C < S =
        if cont1.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont2.asInstanceOf[Arrow[A, C, S]])
        else if cont2.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1.asInstanceOf[Arrow[A, C, S]])
        else
            new Defer[A, B, C, S]:
                def value = v
                def contA = cont1
                def contB = cont2
            end new
    end defer

    /** Holds a computation unevaluated until an eval reaches it. */
    def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    /** Acquires a resource, uses it, and releases it, with the release running whether or not the use completes.
      *
      * A resource is a value scoped to an extent, which is what a region is, so this is one: the acquire's result is the region's state
      * for the extent of `use`, and the region's edges carry the release. An extent that completes runs it where the use ends, before
      * anything composed after the bracket; one that throws runs it on the way out, and the failure still leaves. The region answers no
      * effect, so nothing can read the resource out of it: `use` is handed it directly.
      *
      * The release takes no effects. It has to be able to run where nothing is installed to answer for it, which is what an ending extent
      * can offer. Under multi-shot handling each completed extent releases what its own installation acquired. A captured continuation
      * abandoned by its holder owes a release the machine cannot see; draining it is the holder's obligation, and the surface for that is
      * still open.
      *
      * This form's release only wants the resource, and delegates to the one that also takes the outcome.
      */
    inline def bracket[A, B, S](inline acquire: A < S)(inline release: A => Any < Any)(
        inline use: A => B < S
    )(using inline _frame: Frame): B < S =
        bracket(acquire)((a: A, _: Result[Any, B]) => release(a))(use)

    /** The outcome-taking form: the release is told how the extent ended, Success with the use's result or Panic with the throw, so it can
      * commit on a value and roll back on a failure.
      */
    @nowarn("msg=anonymous")
    inline def bracket[A, B, S](inline acquire: A < S)(inline _release: (A, Result[Any, B]) => Any < Any)(
        inline _use: A => B < S
    )(using inline _frame: Frame): B < S =
        acquire.map { a =>
            val h =
                new Handler[Bracket, B, B, S, A]:
                    def tag = Tag[Bracket]
                    override def recover(state: A, ex: Throwable) =
                        Maybe(_release(state, Result.panic(ex)).map(_ => throw ex))
                    def done(state: A, v: B) =
                        _release(state, Result.succeed(v)).map(_ => v)
            // the use is deferred into the extent, so a throw while the computation is being built
            // still releases; the acquire sits outside it, so a failed acquisition owes nothing
            Kyo.handle[Bracket, B, B, S, A](deferInline(_use(a)), h, a)
        }

    // The payload a deferred body stands on: a raw value inhabits the union's first arm through the
    // `>: A` bound, so nothing nests and nothing lifts.
    private val unitValue: Unit < Any = ()

    // The body lives in the arrow, not the payload: building the record does not run it, and applying
    // the record's own transform is what runs it. The record is its own step, so the by-name form stays
    // one object.
    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Defer[Unit, A, A, S] with Arrow.Transform[Unit, A, S]:
            override def frame          = _frame
            def value                   = unitValue
            def contA                   = this
            def contB                   = Arrow.id
            override def apply(v: Unit) = f
            override def apply[C, S2](v: Unit < S2, cont: Arrow[A, C, S2]) =
                v match
                    case kyo: Pending[Unit, S2] @unchecked =>
                        defer(kyo, this, cont)
                    case _ =>
                        val slot = Safepoint.get()
                        if !Safepoint.enter(slot) then
                            defer(v, this, cont)
                        else
                            val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                            Safepoint.exit(slot)
                            out
                        end if
end Effect
