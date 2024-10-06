package kyo.kernel

import internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

object Loop:

    abstract class Continue[A]:
        private[Loop] def _1: A
    abstract class Continue2[A, B]:
        private[Loop] def _1: A
        private[Loop] def _2: B
    abstract class Continue3[A, B, C]:
        private[Loop] def _1: A
        private[Loop] def _2: B
        private[Loop] def _3: C
    end Continue3
    abstract class Continue4[A, B, C, D]:
        private[Loop] def _1: A
        private[Loop] def _2: B
        private[Loop] def _3: C
        private[Loop] def _4: D
    end Continue4

    opaque type Outcome[A, O]           = O | Continue[A]
    opaque type Outcome2[A, B, O]       = O | Continue2[A, B]
    opaque type Outcome3[A, B, C, O]    = O | Continue3[A, B, C]
    opaque type Outcome4[A, B, C, D, O] = O | Continue4[A, B, C, D]

    private val _continueUnit: Continue[Unit] =
        new Continue:
            def _1 = ()

    inline def continue[A]: Outcome[Unit, A] = _continueUnit

    @targetName("done0")
    def done[A]: Outcome[A, Unit] = ()
    @targetName("done1")
    def done[A, O](v: O): Outcome[A, O] = v
    @targetName("done2")
    def done[A, B, O](v: O): Outcome2[A, B, O] = v
    @targetName("done3")
    def done[A, B, C, O](v: O): Outcome3[A, B, C, O] = v
    @targetName("done4")
    def done[A, B, C, D, O](v: O): Outcome4[A, B, C, D, O] = v

    @nowarn("msg=anonymous")
    inline def continue[A, O, S](inline v: A): Outcome[A, O] =
        new Continue:
            def _1 = v

    @nowarn("msg=anonymous")
    inline def continue[A, B, o](inline v1: A, inline v2: B): Outcome2[A, B, o] =
        new Continue2:
            def _1 = v1
            def _2 = v2

    @nowarn("msg=anonymous")
    inline def continue[A, B, C, O](inline v1: A, inline v2: B, inline v3: C): Outcome3[A, B, C, O] =
        new Continue3:
            def _1 = v1
            def _2 = v2
            def _3 = v3

    @nowarn("msg=anonymous")
    inline def continue[A, B, C, D, O](inline v1: A, inline v2: B, inline v3: C, inline v4: D): Outcome4[A, B, C, D, O] =
        new Continue4:
            def _1 = v1
            def _2 = v2
            def _3 = v3
            def _4 = v4

    inline def apply[A, O, S](inline input: A)(inline run: Safepoint ?=> A => Outcome[A, O] < S)(using
        inline _frame: Frame,
        safepoint: Safepoint
    ): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(i1: A)(v: Outcome[A, O] < S = run(i1))(using Safepoint): O < S =
            v match
                case next: Continue[A] @unchecked =>
                    loop(next._1)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[A, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(i1)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(input)()
    end apply

    inline def apply[A, B, O, S](input1: A, input2: B)(inline run: Safepoint ?=> (A, B) => Outcome2[A, B, O] < S)(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(i1: A, i2: B)(v: Outcome2[A, B, O] < S = run(i1, i2))(using Safepoint): O < S =
            v match
                case next: Continue2[A, B] @unchecked =>
                    loop(next._1, next._2)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome2[A, B, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(i1, i2)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(input1, input2)()
    end apply

    inline def apply[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: Safepoint ?=> (A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame, safepoint: Safepoint): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(i1: A, i2: B, i3: C)(v: Outcome3[A, B, C, O] < S = run(i1, i2, i3))(using Safepoint): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked =>
                    loop(next._1, next._2, next._3)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome3[A, B, C, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(i1, i2, i3)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(input1, input2, input3)()
    end apply

    inline def apply[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: Safepoint ?=> (A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame, safepoint: Safepoint): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(i1: A, i2: B, i3: C, i4: D)(v: Outcome4[A, B, C, D, O] < S = run(i1, i2, i3, i4))(using Safepoint): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked =>
                    loop(next._1, next._2, next._3, next._4)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome4[A, B, C, D, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(i1, i2, i3, i4)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(input1, input2, input3, input4)()
    end apply

    inline def indexed[O, S](inline run: Int => Outcome[Unit, O] < S)(using
        inline _frame: Frame,
        safepoint: Safepoint
    ): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(idx: Int)(v: Outcome[Unit, O] < S = run(idx))(using Safepoint): O < S =
            v match
                case next: Continue[Unit] @unchecked =>
                    loop(idx + 1)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[Unit, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(idx)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(0)()
    end indexed

    inline def indexed[A, O, S](input: A)(inline run: Safepoint ?=> (Int, A) => Outcome[A, O] < S)(using
        inline _frame: Frame,
        safepoint: Safepoint
    ): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(idx: Int, i1: A)(v: Outcome[A, O] < S = run(idx, i1))(using Safepoint): O < S =
            v match
                case next: Continue[A] @unchecked =>
                    loop(idx + 1, next._1)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[A, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(idx, i1)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(0, input)()
    end indexed

    inline def indexed[A, B, O, S](input1: A, input2: B)(
        inline run: Safepoint ?=> (Int, A, B) => Outcome2[A, B, O] < S
    )(using inline _frame: Frame, safepoint: Safepoint): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(idx: Int, i1: A, i2: B)(v: Outcome2[A, B, O] < S = run(idx, i1, i2))(using Safepoint): O < S =
            v match
                case next: Continue2[A, B] @unchecked =>
                    loop(idx + 1, next._1, next._2)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome2[A, B, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(idx, i1, i2)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(0, input1, input2)()
    end indexed

    inline def indexed[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: Safepoint ?=> (Int, A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame, safepoint: Safepoint): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(idx: Int, i1: A, i2: B, i3: C)(v: Outcome3[A, B, C, O] < S = run(idx, i1, i2, i3))(using Safepoint): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked =>
                    loop(idx + 1, next._1, next._2, next._3)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome3[A, B, C, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(idx, i1, i2, i3)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(0, input1, input2, input3)()
    end indexed

    inline def indexed[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: Safepoint ?=> (Int, A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame, safepoint: Safepoint): O < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(idx: Int, i1: A, i2: B, i3: C, i4: D)(v: Outcome4[A, B, C, D, O] < S =
            run(idx, i1, i2, i3, i4))(using Safepoint): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked =>
                    loop(idx + 1, next._1, next._2, next._3, next._4)()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome4[A, B, C, D, O], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, O, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(idx, i1, i2, i3, i4)(kyo(v, context))
                case res =>
                    res.asInstanceOf[O]
        loop(0, input1, input2, input3, input4)()
    end indexed

    inline def foreach[S](inline run: Safepoint ?=> Outcome[Unit, Unit] < S)(using inline _frame: Frame, safepoint: Safepoint): Unit < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(v: Outcome[Unit, Unit] < S = run)(using Safepoint): Unit < S =
            v match
                case next: Continue[Unit] @unchecked =>
                    loop()
                case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[Unit, Unit], S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            loop(kyo(v, context))
                case res =>
                    ()
        loop()
    end foreach

    inline def repeat[S](n: Int)(inline run: Safepoint ?=> Unit < S)(using inline _frame: Frame, safepoint: Safepoint): Unit < S =
        @nowarn("msg=anonymous")
        @tailrec def loop(i: Int)(v: Outcome[Unit, Unit] < S = run)(using Safepoint): Unit < S =
            if i == n then ()
            else
                v match
                    case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[Unit, Unit], S] @unchecked =>
                        new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
                            def frame = _frame
                            def apply(v: OX[Any], context: Context)(using Safepoint) =
                                loop(i)(kyo(v, context))
                        end new
                    case _ =>
                        loop(i + 1)()
            end if
        end loop
        loop(0)()
    end repeat

    inline def forever[S](inline run: Safepoint ?=> Unit < S)(using Frame, Safepoint): Unit < S =
        def _loop(): Unit < S = loop()
        @tailrec def loop(): Unit < S =
            run match
                case kyo: Kyo[Unit, S] @unchecked =>
                    kyo.andThen(_loop())
                case _ =>
                    loop()
        loop()
    end forever
end Loop
