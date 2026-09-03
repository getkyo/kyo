package kyo.kernel

import java.util.concurrent.atomic.AtomicBoolean
import kyo.Closed
import kyo.Frame
import kyo.Maybe
import kyo.Result
import kyo.Tag
import kyo.kernel.Arrow
import kyo.kernel.internal.*
import kyo.kernel.internal.Pending.*
import language.implicitConversions
import scala.annotation.nowarn
import scala.util.control.NonFatal

abstract class Effect private[kernel] ()

object Effect:

    sealed private[kyo] trait Finalize extends ContextEffect[Cell]

    final private[kyo] class Cell(fin: Maybe[Throwable] => Unit) extends AtomicBoolean:
        private[kyo] def complete(): Unit           = if compareAndSet(false, true) then fin(Maybe.Absent)
        private[kyo] def drain(ex: Throwable): Unit = if compareAndSet(false, true) then fin(Maybe(ex))
    end Cell

    def bracket[A, S1](acquire: A < S1)(
        release: (A, Maybe[Throwable]) => Unit
    )[B, S2](use: A => B < S2)(using _frame: Frame): B < (S1 & S2) =
        val ensure = new Arrow.Ensure[A, B, S1 & S2]:
            def frame = _frame
            override def apply(a: A) =
                val cell = new Cell(outcome => release(a, outcome))
                val body =
                    try use(a)
                    catch
                        case ex =>
                            try cell.drain(ex)
                            catch case t if NonFatal(t) && (t ne ex) => ex.addSuppressed(t)
                            throw ex
                val h = new Handler.ContextHandler[Cell, Finalize, B, S1 & S2]:
                    def tag                                                             = Tag[Finalize]
                    def derive(outer: Maybe[Cell])                                      = cell
                    def fork(parent: Cell)                                              = parent
                    def join(parent: Cell, fk: Cell, child: Cell)                       = parent
                    override private[kyo] def done(state: Cell): Unit                   = state.complete()
                    override private[kyo] def release(state: Cell, ex: Throwable): Unit = state.drain(ex)
                    override private[kyo] def reenter(state: Cell): Unit =
                        if state.get() then
                            throw new Closed("Bracket resource", _frame)(using _frame)
                new Pending.HandleContext[Cell, Finalize, B, S1 & S2]:
                    override def frame = _frame
                    def value          = body
                    def handler        = h
                end new
            end apply
        defer(acquire).chain(ensure)
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
        new Pending.DeferWith[Unit, A, S]:
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
