package kyo.proto

import kyo.Frame
import kyo.Maybe
import kyo.proto.Arrow.Step
import kyo.proto.kernel.Effect
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import kyo.proto.kernel.internal.site
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.annotation.targetName

object Loop:

    sealed abstract class Continue[A] extends Serializable:
        Debugger.onAlloc(this)
        private[kyo] def _1: A
        override def toString = s"Continue(${_1})"
    end Continue

    sealed abstract class Continue2[A, B] extends Serializable:
        Debugger.onAlloc(this)
        private[kyo] def _1: A
        private[kyo] def _2: B
        override def toString = s"Continue2(${_1}, ${_2})"
    end Continue2

    sealed abstract class Continue3[A, B, C] extends Serializable:
        Debugger.onAlloc(this)
        private[kyo] def _1: A
        private[kyo] def _2: B
        private[kyo] def _3: C
        override def toString = s"Continue3(${_1}, ${_2}, ${_3})"
    end Continue3

    sealed abstract class Continue4[A, B, C, D] extends Serializable:
        Debugger.onAlloc(this)
        private[kyo] def _1: A
        private[kyo] def _2: B
        private[kyo] def _3: C
        private[kyo] def _4: D
        override def toString = s"Continue4(${_1}, ${_2}, ${_3}, ${_4})"
    end Continue4

    opaque type Outcome[A, +O] = O | Continue[A]

    opaque type Outcome2[A, B, +O] = O | Continue2[A, B]

    opaque type Outcome3[A, B, C, +O] = O | Continue3[A, B, C]

    opaque type Outcome4[A, B, C, D, +O] = O | Continue4[A, B, C, D]

    final private[kyo] class Done[O](val value: O)

    private[kyo] def unnest[T](v: Any): T =
        v match
            case v: Done[?] => v.value.asInstanceOf[T]
            case v          => Nested.unnest[T](v)

    private val _continueUnit: Continue[Unit] =
        new Continue:
            def _1 = ()

    inline def continue[A]: Outcome[Unit, A] < Any =

        _continueUnit.asInstanceOf[Outcome[Unit, A] < Any]

    @nowarn("msg=anonymous")
    inline def continue[A, O, S](inline v: A): Outcome[A, O] < S =

        val v0 = v
        (new Continue[A]:
            def _1 = v0
        ).asInstanceOf[Outcome[A, O] < S]
    end continue

    @nowarn("msg=anonymous")
    inline def continue[A, B, O](inline v1: A, inline v2: B): Outcome2[A, B, O] < Any =
        val v1x = v1
        val v2x = v2
        (new Continue2[A, B]:
            def _1 = v1x
            def _2 = v2x
        ).asInstanceOf[Outcome2[A, B, O] < Any]
    end continue

    @nowarn("msg=anonymous")
    inline def continue[A, B, C, O](inline v1: A, inline v2: B, inline v3: C): Outcome3[A, B, C, O] < Any =
        val v1x = v1
        val v2x = v2
        val v3x = v3
        (new Continue3[A, B, C]:
            def _1 = v1x
            def _2 = v2x
            def _3 = v3x
        ).asInstanceOf[Outcome3[A, B, C, O] < Any]
    end continue

    @nowarn("msg=anonymous")
    inline def continue[A, B, C, D, O](
        inline v1: A,
        inline v2: B,
        inline v3: C,
        inline v4: D
    ): Outcome4[A, B, C, D, O] < Any =
        val v1x = v1
        val v2x = v2
        val v3x = v3
        val v4x = v4
        (new Continue4[A, B, C, D]:
            def _1 = v1x
            def _2 = v2x
            def _3 = v3x
            def _4 = v4x
        ).asInstanceOf[Outcome4[A, B, C, D, O] < Any]
    end continue

    @targetName("done0")
    inline def done[A]: Outcome[A, Unit] < Any =
        ().asInstanceOf[Outcome[A, Unit] < Any]

    @targetName("done1")
    inline def done[A, O](inline v: O): Outcome[A, O] < Any =
        v match
            case v: Continue[?] => new Done(v).asInstanceOf[Outcome[A, O] < Any]
            case v              => Nested.nest[Outcome[A, O], Any](v)

    @targetName("done2")
    inline def done[A, B, O](inline v: O): Outcome2[A, B, O] < Any =
        v match
            case v: Continue2[?, ?] => new Done(v).asInstanceOf[Outcome2[A, B, O] < Any]
            case v                  => Nested.nest[Outcome2[A, B, O], Any](v)

    @targetName("done3")
    inline def done[A, B, C, O](inline v: O): Outcome3[A, B, C, O] < Any =
        v match
            case v: Continue3[?, ?, ?] => new Done(v).asInstanceOf[Outcome3[A, B, C, O] < Any]
            case v                     => Nested.nest[Outcome3[A, B, C, O], Any](v)

    @targetName("done4")
    inline def done[A, B, C, D, O](inline v: O): Outcome4[A, B, C, D, O] < Any =
        v match
            case v: Continue4[?, ?, ?, ?] => new Done(v).asInstanceOf[Outcome4[A, B, C, D, O] < Any]
            case v                        => Nested.nest[Outcome4[A, B, C, D, O], Any](v)

    @nowarn("msg=anonymous")
    inline def apply[A, O, S](inline input: A)(inline run: A => Outcome[A, O] < S)(
        using inline _frame: Frame
    ): O < S =

        @tailrec def loop(step: Maybe[Arrow[Outcome[A, O], O, S]], v: Outcome[A, O] < S): O < S =
            v match
                case next: Continue[A] @unchecked =>
                    loop(step, run(next._1))
                case kyo: Pending[Outcome[A, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Step[Outcome[A, O], O, S]:
                            def frame = _frame
                            def apply[C, S2](v: Outcome[A, O] < S2, cont: Arrow[O, C, S2]): C < (S & S2) =
                                v match
                                    case kyo: Pending[Outcome[A, O], S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else

                                            val out = cont.head(loop(Maybe(this), v.asInstanceOf[Outcome[A, O] < S]), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input))
    end apply

    @nowarn("msg=anonymous")
    inline def apply[A, B, O, S](input1: A, input2: B)(inline run: (A, B) => Outcome2[A, B, O] < S)(
        using inline _frame: Frame
    ): O < S =

        @tailrec def loop(step: Maybe[Arrow[Outcome2[A, B, O], O, S]], v: Outcome2[A, B, O] < S): O < S =
            v match
                case next: Continue2[A, B] @unchecked =>
                    loop(step, run(next._1, next._2))
                case kyo: Pending[Outcome2[A, B, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Step[Outcome2[A, B, O], O, S]:
                            def frame = _frame
                            def apply[C, S2](v: Outcome2[A, B, O] < S2, cont: Arrow[O, C, S2]): C < (S & S2) =
                                v match
                                    case kyo: Pending[Outcome2[A, B, O], S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else
                                            val out = cont.head(loop(Maybe(this), v.asInstanceOf[Outcome2[A, B, O] < S]), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input1, input2))
    end apply

    @nowarn("msg=anonymous")
    inline def apply[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: (A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame): O < S =

        @tailrec def loop(step: Maybe[Arrow[Outcome3[A, B, C, O], O, S]], v: Outcome3[A, B, C, O] < S): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked =>
                    loop(step, run(next._1, next._2, next._3))
                case kyo: Pending[Outcome3[A, B, C, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Step[Outcome3[A, B, C, O], O, S]:
                            def frame = _frame
                            def apply[C2, S2](v: Outcome3[A, B, C, O] < S2, cont: Arrow[O, C2, S2]): C2 < (S & S2) =
                                v match
                                    case kyo: Pending[Outcome3[A, B, C, O], S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else
                                            val out = cont.head(loop(Maybe(this), v.asInstanceOf[Outcome3[A, B, C, O] < S]), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input1, input2, input3))
    end apply

    @nowarn("msg=anonymous")
    inline def apply[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: (A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame): O < S =

        @tailrec def loop(step: Maybe[Arrow[Outcome4[A, B, C, D, O], O, S]], v: Outcome4[A, B, C, D, O] < S): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked =>
                    loop(step, run(next._1, next._2, next._3, next._4))
                case kyo: Pending[Outcome4[A, B, C, D, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Step[Outcome4[A, B, C, D, O], O, S]:
                            def frame = _frame
                            def apply[C2, S2](v: Outcome4[A, B, C, D, O] < S2, cont: Arrow[O, C2, S2]): C2 < (S & S2) =
                                v match
                                    case kyo: Pending[Outcome4[A, B, C, D, O], S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else
                                            val out = cont.head(loop(Maybe(this), v.asInstanceOf[Outcome4[A, B, C, D, O] < S]), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input1, input2, input3, input4))
    end apply

    inline def indexed[O, S](inline run: Int => Outcome[Unit, O] < S)(using inline _frame: Frame): O < S =

        def suspended(idx: Int)(v: Outcome[Unit, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome[Unit, O] < S): O < S =
            v match
                case next: Continue[Unit] @unchecked =>
                    loop(idx + 1)(run(idx))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue)
    end indexed

    inline def indexed[A, O, S](input: A)(inline run: (Int, A) => Outcome[A, O] < S)(using inline _frame: Frame): O < S =
        def suspended(idx: Int)(v: Outcome[A, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome[A, O] < S): O < S =
            v match
                case next: Continue[A] @unchecked =>
                    loop(idx + 1)(run(idx, next._1))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input))
    end indexed

    inline def indexed[A, B, O, S](input1: A, input2: B)(
        inline run: (Int, A, B) => Outcome2[A, B, O] < S
    )(using inline _frame: Frame): O < S =
        def suspended(idx: Int)(v: Outcome2[A, B, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome2[A, B, O] < S): O < S =
            v match
                case next: Continue2[A, B] @unchecked =>
                    loop(idx + 1)(run(idx, next._1, next._2))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input1, input2))
    end indexed

    inline def indexed[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: (Int, A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame): O < S =
        def suspended(idx: Int)(v: Outcome3[A, B, C, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome3[A, B, C, O] < S): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked =>
                    loop(idx + 1)(run(idx, next._1, next._2, next._3))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input1, input2, input3))
    end indexed

    inline def indexed[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: (Int, A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame): O < S =
        def suspended(idx: Int)(v: Outcome4[A, B, C, D, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome4[A, B, C, D, O] < S): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked =>
                    loop(idx + 1)(run(idx, next._1, next._2, next._3, next._4))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res: Done[?] =>
                    res.value.asInstanceOf[O < S]
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input1, input2, input3, input4))
    end indexed

    @nowarn("msg=anonymous")
    inline def foreach[A, S](inline run: Outcome[Unit, A] < S)(using inline _frame: Frame): A < S =

        @tailrec def loop(step: Maybe[Arrow[Outcome[Unit, A], A, S]], v: Outcome[Unit, A] < S): A < S =
            v match
                case next: Continue[Unit] @unchecked =>
                    loop(step, run)
                case kyo: Pending[Outcome[Unit, A], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Step[Outcome[Unit, A], A, S]:
                            def frame = _frame
                            def apply[C, S2](v: Outcome[Unit, A] < S2, cont: Arrow[A, C, S2]): C < (S & S2) =
                                v match
                                    case kyo: Pending[Outcome[Unit, A], S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else
                                            val out = cont.head(loop(Maybe(this), v.asInstanceOf[Outcome[Unit, A] < S]), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case res: Done[?] =>
                    res.value.asInstanceOf[A < S]
                case res =>
                    res.asInstanceOf[A < S]
        loop(Maybe.empty, Loop.continue)
    end foreach

    inline def repeat[S](n: Int)(inline run: Any < S)(using inline _frame: Frame): Unit < S =
        def suspended(i: Int)(v: Any < S): Unit < S =
            v.map(_ => loop(i))
        @tailrec def loop(i: Int): Unit < S =
            if i >= n then ()
            else

                val v: Any < S = run
                v match
                    case _: Arrow[?, ?, ?] =>
                        suspended(i + 1)(v)
                    case _ =>
                        loop(i + 1)
                end match
            end if
        end loop
        loop(0)
    end repeat

    @nowarn("msg=anonymous")
    inline def forever[S](inline run: Any < S)(using inline _frame: Frame): Nothing < S =

        @tailrec def loop(step: Maybe[Arrow[Any, Nothing, S]], v: Any < S): Nothing < S =
            v match
                case kyo: Pending[Any, S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Step[Any, Nothing, S]:
                            def frame = _frame
                            def apply[C, S2](v: Any < S2, cont: Arrow[Nothing, C, S2]): C < (S & S2) =
                                v match
                                    case kyo: Pending[Any, S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else
                                            val out = cont.head(loop(Maybe(this), run), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case _ =>
                    loop(step, run)
        end loop
        loop(Maybe.empty, ())
    end forever

    @nowarn("msg=anonymous")
    inline def whileTrue[S](inline condition: Boolean < S)(inline run: Unit < S)(
        using inline _frame: Frame
    ): Unit < S =

        def loop(step: Maybe[Arrow[Any, Unit, S]], v: Unit < S): Unit < S =
            condition.map {
                case true =>
                    v match
                        case kyo: Pending[Any, S] @unchecked =>
                            val arrow = step.getOrElse {
                                new Step[Any, Unit, S]:
                                    def frame = _frame
                                    def apply[C, S2](v: Any < S2, cont: Arrow[Unit, C, S2]): C < (S & S2) =
                                        v match
                                            case kyo: Pending[Any, S2] @unchecked =>
                                                Effect.defer(kyo, this, cont)
                                            case _ =>
                                                val slot = Safepoint.get()
                                                if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                                else
                                                    val out = cont.head(loop(Maybe(this), run), cont.tail)
                                                    Safepoint.exit(slot)
                                                    out
                                                end if
                            }
                            Effect.defer(kyo, arrow, Arrow.id)
                        case _ =>
                            loop(step, run)
                case false => ()
            }
        end loop
        loop(Maybe.empty, ())
    end whileTrue
end Loop
