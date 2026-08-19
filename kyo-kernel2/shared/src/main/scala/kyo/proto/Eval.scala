package kyo.proto

import kyo.Frame
import kyo.Maybe
import kyo.Maybe.*
import kyo.Tag
import kyo.bug
import kyo.discard
import kyo.proto.Handler.HandlerCont
import kyo.proto.Handler.HandlerLoop
import kyo.proto.Handler.HandlerLoopState
import kyo.proto.Loop.Outcome
import scala.annotation.static
import scala.annotation.tailrec

object Eval:

    type IX[_]
    type OX[_]
    type EX <: ArrowEffect[IX, OX]
    type CX
    type AX
    type BX
    type StateX

    private inline given Frame = Frame.internal

    def apply[A, S](v: A < S): A =
        val stack = Stack.borrow()
        var curr  = v.asInstanceOf[Any < Nothing]

        @tailrec def loop(): Unit =
            curr.lower(
                pending =
                    case kyo: Kyo.Defer[?, ?, A, S] @unchecked =>
                        stack.push(kyo.contB)
                        stack.push(kyo.contA)
                        curr = kyo.value
                    case kyo: Kyo.Suspend[IX, OX, EX, CX, A, S] @unchecked =>
                        stack.push(kyo.cont)
                        val pos = stack.find(kyo.tag)
                        if pos < 0 then bug(s"unhandled suspension: ${kyo.tag}")
                        else
                            stack.handler(pos) match
                                case h: HandlerCont[IX, OX, EX, AX, ?, S] @unchecked =>
                                    curr = h.run(kyo.input, stack.dump(pos))
                                case h: HandlerLoop[IX, OX, EX, AX, BX, S] @unchecked =>
                                    curr =
                                        h.run(kyo.input).lower(
                                            pending = clause =>
                                                val k = stack.dump[OX[CX], AX, EX & S](pos)
                                                discard(stack.pop())
                                                new Kyo.Defer[Loop.Outcome[OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                    with Arrow.Transform[Loop.Outcome[OX[CX] < (EX & S), BX], BX, EX & S]:
                                                    def frame = Frame.internal
                                                    def value = clause
                                                    def contA = this
                                                    def contB = Arrow.id[BX]
                                                    override def apply(o: Loop.Outcome[OX[CX] < (EX & S), BX]) =
                                                        o match
                                                            case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                                Kyo.Defer(r._1.map(k(_)), h)
                                                            case v => v.asInstanceOf[BX]
                                                    def apply[D, S2](o: Loop.Outcome[OX[CX] < (EX & S), BX] < S2, next: Arrow[BX, D, S2])
                                                        : D < (EX & S & S2) =
                                                        o.lower(
                                                            pending = Kyo.Defer(_, this, next),
                                                            done = o => next(apply(o), Arrow.id)
                                                        )
                                                end new
                                            ,
                                            done =
                                                case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                    r._1.lower(
                                                        pending = _ =>
                                                            val k = stack.dump[OX[CX], AX, EX & S](pos)
                                                            r._1.map(k(_))
                                                        ,
                                                        done = _ => r._1
                                                    )
                                                case v =>
                                                    stack.truncate(pos + 1)
                                                    v
                                        )
                                case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                    val s = stack.state(pos).getOrElse(h.initialState)
                                    curr =
                                        h.run(s, kyo.input).lower(
                                            pending = clause =>
                                                val k = stack.dump[OX[CX], AX, EX & S](pos)
                                                discard(stack.pop())
                                                new Kyo.Defer[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                    with Arrow.Transform[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], BX, EX & S]:
                                                    def frame = Frame.internal
                                                    def value = clause
                                                    def contA = this
                                                    def contB = Arrow.id[BX]
                                                    override def apply(o: Loop.Outcome2[StateX, OX[CX] < (EX & S), BX]) =
                                                        o match
                                                            case r: Loop.Continue2[StateX, OX[CX] < (EX & S)] @unchecked =>
                                                                Kyo.Defer(r._2.map(k(_)), HandlerLoopState(h, r._1))
                                                            case v => v.asInstanceOf[BX]
                                                    def apply[D, S2](
                                                        o: Loop.Outcome2[StateX, OX[CX] < (EX & S), BX] < S2,
                                                        next: Arrow[BX, D, S2]
                                                    ): D < (EX & S & S2) =
                                                        o.lower(
                                                            pending = Kyo.Defer(_, this, next),
                                                            done = o => next(apply(o), Arrow.id)
                                                        )
                                                end new
                                            ,
                                            done =
                                                case r: Loop.Continue2[StateX, OX[CX] < (EX & S)] @unchecked =>
                                                    stack.putState(pos, r._1)
                                                    r._2.lower(
                                                        pending = _ =>
                                                            val k = stack.dump[OX[CX], AX, EX & S](pos)
                                                            r._2.map(k(_))
                                                        ,
                                                        done = _ => r._2
                                                    )
                                                case v =>
                                                    stack.truncate(pos + 1)
                                                    v
                                        )
                            end match
                        end if
                    case kyo: Kyo.Handle[EX, ?, ?, A, S] @unchecked =>
                        stack.push(kyo.cont)
                        stack.push(kyo.handler)
                        curr = kyo.value
                ,
                done = r =>
                    if !stack.isEmpty then
                        val s = stack.state[StateX](0)
                        stack.pop() match
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                curr = h.apply(s.getOrElse(h.initialState), r.asInstanceOf[AX])
                            case head =>
                                val tail = stack.dump()
                                curr = head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, tail)
                        end match
            )
            if !stack.isEmpty || curr.evalNow.isEmpty then
                loop()
        end loop

        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try
            loop()
            curr.lower(
                pending = kyo => bug(s"unhandled suspension: ${kyo}"),
                done = _.asInstanceOf[A]
            )
        finally
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
    end apply

end Eval
