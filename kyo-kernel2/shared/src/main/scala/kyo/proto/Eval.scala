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

        @tailrec def loop(curr: Any < Nothing): Any =
            curr match
                case kyo: Kyo.Defer[?, ?, A, S] @unchecked =>
                    stack.push(kyo.contB)
                    stack.push(kyo.contA)
                    loop(kyo.value)
                case kyo: Kyo.Suspend[IX, OX, EX, CX, A, S] @unchecked =>
                    stack.push(kyo.cont)
                    val pos = stack.find(kyo.tag)
                    if pos < 0 then bug(s"unhandled suspension: ${kyo.tag}")
                    else
                        stack.handler(pos) match
                            case h: HandlerCont[IX, OX, EX, AX, ?, S] @unchecked =>
                                loop(h.run(kyo.input, stack.dump(pos)))
                            case h: HandlerLoop[IX, OX, EX, AX, BX, S] @unchecked =>
                                h.run(kyo.input) match
                                    case clause: Kyo[Loop.Outcome[OX[CX] < (EX & S), BX], S] @unchecked =>
                                        val k = stack.dump(pos)
                                        discard(stack.pop())
                                        loop(
                                            new Kyo.Defer[Loop.Outcome[OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                with Arrow.Transform[Loop.Outcome[OX[CX] < (EX & S), BX], BX, EX & S]:
                                                def frame = Frame.internal
                                                def value = clause
                                                def contA = this
                                                def contB = Arrow.id[BX]
                                                override def apply(o: Loop.Outcome[OX[CX] < (EX & S), BX]) =
                                                    o match
                                                        case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                            Effect.defer(r._1.map(k), h)
                                                        case v => v.asInstanceOf[BX]
                                                def apply[D, S2](o: Loop.Outcome[OX[CX] < (EX & S), BX] < S2, next: Arrow[BX, D, S2])
                                                    : D < (EX & S & S2) =
                                                    o match
                                                        case kyo: Kyo[Loop.Outcome[OX[CX] < (EX & S), BX], S2] @unchecked =>
                                                            Effect.defer(kyo, this, next)
                                                        case _ => next(apply(o.unsafeGet), Arrow.id)
                                        )
                                    case o =>
                                        o.unsafeGet match
                                            case r: Loop.Continue[OX[CX] < (EX & S)] @unchecked =>
                                                r._1 match
                                                    case _: Kyo[OX[CX], EX & S] @unchecked =>
                                                        val k = stack.dump(pos)
                                                        loop(r._1.map(k))
                                                    case _ => loop(r._1)
                                            case _ =>
                                                stack.truncate(pos + 1)
                                                loop(o)
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                val s = stack.state(pos).getOrElse(h.initialState)
                                h.run(s, kyo.input) match
                                    case clause: Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], S] @unchecked =>
                                        val k = stack.dump(pos)
                                        discard(stack.pop())
                                        loop(
                                            new Kyo.Defer[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], BX, BX, EX & S]
                                                with Arrow.Transform[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], BX, EX & S]:
                                                def frame = Frame.internal
                                                def value = clause
                                                def contA = this
                                                def contB = Arrow.id[BX]
                                                override def apply(o: Loop.Outcome2[StateX, OX[CX] < (EX & S), BX]) =
                                                    o match
                                                        case r: Loop.Continue2[StateX, OX[CX] < (EX & S)] @unchecked =>
                                                            Effect.defer(r._2.map(k), HandlerLoopState(h, r._1))
                                                        case v => v.asInstanceOf[BX]
                                                def apply[D, S2](
                                                    o: Loop.Outcome2[StateX, OX[CX] < (EX & S), BX] < S2,
                                                    next: Arrow[BX, D, S2]
                                                ): D < (EX & S & S2) =
                                                    o match
                                                        case kyo: Kyo[Loop.Outcome2[StateX, OX[CX] < (EX & S), BX], S2] @unchecked =>
                                                            Effect.defer(kyo, this, next)
                                                        case _ => next(apply(o.unsafeGet), Arrow.id)
                                        )
                                    case o =>
                                        o.unsafeGet match
                                            case r: Loop.Continue2[StateX, OX[CX] < (EX & S)] @unchecked =>
                                                stack.putState(pos, r._1)
                                                r._2 match
                                                    case _: Kyo[OX[CX], EX & S] @unchecked =>
                                                        val k = stack.dump(pos)
                                                        loop(r._2.map(k))
                                                    case _ => loop(r._2)
                                                end match
                                            case _ =>
                                                stack.truncate(pos + 1)
                                                loop(o)
                                end match
                        end match
                    end if
                case kyo: Kyo.Handle[EX, ?, ?, A, S] @unchecked =>
                    stack.push(kyo.cont)
                    stack.push(kyo.handler)
                    loop(kyo.value)
                case _ =>
                    val r = curr.unsafeGet
                    if !stack.isEmpty then
                        val s = stack.state[StateX](0)
                        stack.pop() match
                            case h: HandlerLoopState[IX, OX, EX, AX, BX, S, StateX] @unchecked =>
                                loop(h.apply(s.getOrElse(h.initialState), r.asInstanceOf[AX]))
                            case h: Handler[EX, AX, BX, S] @unchecked =>
                                loop(h(curr.asInstanceOf[AX < (EX & S)], stack.dump()))
                            case c: Arrow.Chain[Any, ?, Any, EX & S] @unchecked =>
                                loop(c(curr, stack.dump()))
                            case head =>
                                loop(head.asInstanceOf[Arrow[Any, ?, EX & S]](curr, stack.dump()))
                        end match
                    else r
                    end if
        end loop

        val slot  = Safepoint.get()
        val saved = Safepoint.save(slot)
        try
            loop(v.asInstanceOf[Any < Nothing]).asInstanceOf[A]
        finally
            Stack.release(stack)
            Safepoint.restore(slot, saved)
        end try
    end apply

end Eval
