package kyo.proto.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.proto.Arrow
import kyo.proto.kernel.internal.*
import kyo.proto.kernel.internal.Kyo.*
import language.implicitConversions
import scala.annotation.nowarn
import scala.util.control.NonFatal

abstract class Effect private[kernel] ()

object Effect:

    /** The bracket region's effect: never suspended, a region exists only to be reached. */
    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    /** The obligation: a claim around the release thunk. The resource lives in the thunk's
      * closure. Atomic because a parked remainder can be drained on one thread while a
      * captured continuation completes on another.
      */
    final private[kyo] class Cell(fin: Maybe[Throwable] => Unit) extends AtomicBoolean:
        private[kyo] def complete(): Unit           = if compareAndSet(false, true) then fin(Maybe.Absent)
        private[kyo] def drain(ex: Throwable): Unit = if compareAndSet(false, true) then fin(Maybe(ex))
    end Cell

    private[kyo] object Cell:
        // What a fork installs: a child's view of a bracket it does not own. Already
        // claimed, so a child's death cannot drain the parent's obligation.
        private[kyo] val inert: Cell =
            val cell = new Cell(_ => ())
            cell.set(true)
            cell
        end inert
    end Cell

    /** Acquires a resource, uses it, and releases it exactly once: on completion, when a
      * failure unwinds past the bracket, when the computation is abandoned, and when a
      * capture holding it is discarded. The region opens through a bind step, in the same
      * slice the acquire settles: no safepoint can separate the two, so an existing
      * resource is never left without its region. Absent means the extent completed.
      */
    def bracket[A, S1](acquire: A < S1)(
        release: (A, Maybe[Throwable]) => Unit
    )[B, S2](use: A => B < S2)(using _frame: Frame): B < (S1 & S2) =
        val open = new Arrow.Bind[A, B, S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell(outcome => release(a, outcome))
                val body =
                    // The region does not exist until the handle below wraps the body, so
                    // a use that throws during application drains here on the way out.
                    try use(a)
                    catch
                        case ex if NonFatal(ex) =>
                            cell.drain(ex)
                            throw ex
                ContextEffect.handle(Tag[Finalize])(
                    (_: Maybe[Cell]) => cell,
                    fork = (_: Cell) => Cell.inert,
                    join = (parent: Cell, _: Cell, _: Cell) => parent,
                    done = (c: Cell) => c.complete(),
                    release = (c: Cell, ex: Throwable) => c.drain(ex)
                )(body)
            end apply
        defer(acquire).chain(open)
    end bracket

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

    def defer[A, B, C, D, S](v: A < S, cont1: Arrow[A, B, S], cont2: Arrow[B, C, S], cont3: Arrow[C, D, S]): D < S =
        if cont1.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont2.asInstanceOf[Arrow[A, C, S]], cont3)
        else if cont2.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1.asInstanceOf[Arrow[A, C, S]], cont3)
        else if cont3.isInstanceOf[Arrow.Id[?]] then
            defer(v, cont1, cont2.asInstanceOf[Arrow[B, D, S]])
        else
            defer(v, cont1, cont2.chain(cont3))

    def defer[A, S](f: => A < S)(using Frame): A < S =
        deferInline(f)

    private val unitValue: Unit < Any = ()

    @nowarn("msg=anonymous")
    private[kyo] inline def deferInline[A, S](inline f: => A < S)(using inline _frame: Frame): A < S =
        new Kyo.DeferWith[Unit, A, S]:
            override def frame          = _frame
            def value                   = unitValue
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
