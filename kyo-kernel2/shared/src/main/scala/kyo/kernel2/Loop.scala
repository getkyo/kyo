package kyo.kernel2

import kyo.Frame
import kyo.kernel2.internal.Kyo
import scala.annotation.nowarn
import scala.annotation.tailrec

/** Stack-safe loops with unboxed iteration outcomes.
  *
  * Each iteration produces an [[Loop.Outcome]]: continue with the next state, or complete with a final value. Completion is unboxed (the
  * final value is the outcome), and continuing allocates only the state carrier. The drivers recurse through `map`, so the depth guard's
  * rescue keeps arbitrarily long pure loops and effectful loops stack safe alike.
  */
object Loop:

    sealed abstract class Continue[A] extends Serializable:
        private[kyo] def _1: A

    sealed abstract class Continue2[A, B] extends Serializable:
        private[kyo] def _1: A
        private[kyo] def _2: B

    sealed abstract class Continue3[A, B, C] extends Serializable:
        private[kyo] def _1: A
        private[kyo] def _2: B
        private[kyo] def _3: C
    end Continue3

    sealed abstract class Continue4[A, B, C, D] extends Serializable:
        private[kyo] def _1: A
        private[kyo] def _2: B
        private[kyo] def _3: C
        private[kyo] def _4: D
    end Continue4

    opaque type Outcome[A, O]           = O | Continue[A]
    opaque type Outcome2[A, B, O]       = O | Continue2[A, B]
    opaque type Outcome3[A, B, C, O]    = O | Continue3[A, B, C]
    opaque type Outcome4[A, B, C, D, O] = O | Continue4[A, B, C, D]

    private val _continueUnit: Continue[Unit] =
        new Continue[Unit]:
            def _1 = ()

    /** Continues a stateless loop. */
    inline def continue[A]: Outcome[Unit, A] = _continueUnit

    /** Continues with the next state. */
    @nowarn("msg=anonymous")
    inline def continue[A, O, S](inline v: A): Outcome[A, O] =
        new Continue[A]:
            def _1 = v

    /** Continues with the next two state values. */
    @nowarn("msg=anonymous")
    inline def continue[A, B, o](inline v1: A, inline v2: B): Outcome2[A, B, o] =
        new Continue2[A, B]:
            def _1 = v1
            def _2 = v2

    /** Continues with the next three state values. */
    @nowarn("msg=anonymous")
    inline def continue[A, B, C, O](inline v1: A, inline v2: B, inline v3: C): Outcome3[A, B, C, O] =
        new Continue3[A, B, C]:
            def _1 = v1
            def _2 = v2
            def _3 = v3

    /** Continues with the next four state values. */
    @nowarn("msg=anonymous")
    inline def continue[A, B, C, D, O](inline v1: A, inline v2: B, inline v3: C, inline v4: D): Outcome4[A, B, C, D, O] =
        new Continue4[A, B, C, D]:
            def _1 = v1
            def _2 = v2
            def _3 = v3
            def _4 = v4

    // TODO I don't think this is optimized liek the old kernel was?

    /** Completes a stateful loop with unit. */
    inline def done[A]: Outcome[A, Unit] = ()

    /** Completes the loop with a final value. */
    inline def done[A, O](inline v: O): Outcome[A, O] = v

    /** Completes a two-state loop with a final value. */
    inline def done[A, B, O](inline v: O): Outcome2[A, B, O] = v

    /** Completes a three-state loop with a final value. */
    inline def done[A, B, C, O](inline v: O): Outcome3[A, B, C, O] = v

    /** Completes a four-state loop with a final value. */
    inline def done[A, B, C, D, O](inline v: O): Outcome4[A, B, C, D, O] = v

    /** Loops over one state value until the iteration completes. */
    @nowarn("msg=anonymous")
    inline def apply[A, O, S](inline input: A)(inline run: A => Outcome[A, O] < S)(using inline _frame: Frame): O < S =
        @tailrec def loop(v: Outcome[A, O] < S): O < S =
            v match
                case next: Continue[A] @unchecked => loop(run(next._1))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome[A, O], O, S]:
                        def frame = _frame
                        def run[C, S2](o: Any, cont: Arrow[O, C, S2]): C < (S & S2) =
                            cont(loop(Kyo.lift(o.asInstanceOf[Outcome[A, O]])))
                    kyo.asInstanceOf[Kyo[Outcome[A, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(run(input))
    end apply

    /** Loops over two state values until the iteration completes. */
    @nowarn("msg=anonymous")
    inline def apply[A, B, O, S](input1: A, input2: B)(inline run: (A, B) => Outcome2[A, B, O] < S)(using inline _frame: Frame): O < S =
        @tailrec def loop(v: Outcome2[A, B, O] < S): O < S =
            v match
                case next: Continue2[A, B] @unchecked => loop(run(next._1, next._2))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome2[A, B, O], O, S]:
                        def frame = _frame
                        def run[C, S2](o: Any, cont: Arrow[O, C, S2]): C < (S & S2) =
                            cont(loop(Kyo.lift(o.asInstanceOf[Outcome2[A, B, O]])))
                    kyo.asInstanceOf[Kyo[Outcome2[A, B, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(run(input1, input2))
    end apply

    /** Loops over three state values until the iteration completes. */
    @nowarn("msg=anonymous")
    inline def apply[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: (A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame): O < S =
        @tailrec def loop(v: Outcome3[A, B, C, O] < S): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked => loop(run(next._1, next._2, next._3))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome3[A, B, C, O], O, S]:
                        def frame = _frame
                        def run[C2, S2](o: Any, cont: Arrow[O, C2, S2]): C2 < (S & S2) =
                            cont(loop(Kyo.lift(o.asInstanceOf[Outcome3[A, B, C, O]])))
                    kyo.asInstanceOf[Kyo[Outcome3[A, B, C, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(run(input1, input2, input3))
    end apply

    /** Loops over four state values until the iteration completes. */
    @nowarn("msg=anonymous")
    inline def apply[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: (A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame): O < S =
        @tailrec def loop(v: Outcome4[A, B, C, D, O] < S): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked => loop(run(next._1, next._2, next._3, next._4))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome4[A, B, C, D, O], O, S]:
                        def frame = _frame
                        def run[C2, S2](o: Any, cont: Arrow[O, C2, S2]): C2 < (S & S2) =
                            cont(loop(Kyo.lift(o.asInstanceOf[Outcome4[A, B, C, D, O]])))
                    kyo.asInstanceOf[Kyo[Outcome4[A, B, C, D, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(run(input1, input2, input3, input4))
    end apply

    /** Loops with the iteration index until completion. */
    @nowarn("msg=anonymous")
    inline def indexed[O, S](inline run: Int => Outcome[Unit, O] < S)(using inline _frame: Frame): O < S =
        @tailrec def loop(idx: Int, v: Outcome[Unit, O] < S): O < S =
            v match
                case next: Continue[Unit] @unchecked => loop(idx + 1, run(idx + 1))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome[Unit, O], O, S]:
                        def frame = _frame
                        def run[C, S2](o: Any, cont: Arrow[O, C, S2]): C < (S & S2) =
                            cont(loop(idx, Kyo.lift(o.asInstanceOf[Outcome[Unit, O]])))
                    kyo.asInstanceOf[Kyo[Outcome[Unit, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(0, run(0))
    end indexed

    /** Loops with the iteration index and one state value. */
    @nowarn("msg=anonymous")
    inline def indexed[A, O, S](input: A)(inline run: (Int, A) => Outcome[A, O] < S)(using inline _frame: Frame): O < S =
        @tailrec def loop(idx: Int, v: Outcome[A, O] < S): O < S =
            v match
                case next: Continue[A] @unchecked => loop(idx + 1, run(idx + 1, next._1))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome[A, O], O, S]:
                        def frame = _frame
                        def run[C, S2](o: Any, cont: Arrow[O, C, S2]): C < (S & S2) =
                            cont(loop(idx, Kyo.lift(o.asInstanceOf[Outcome[A, O]])))
                    kyo.asInstanceOf[Kyo[Outcome[A, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(0, run(0, input))
    end indexed

    /** Loops with the iteration index and two state values. */
    @nowarn("msg=anonymous")
    inline def indexed[A, B, O, S](input1: A, input2: B)(
        inline run: (Int, A, B) => Outcome2[A, B, O] < S
    )(using inline _frame: Frame): O < S =
        @tailrec def loop(idx: Int, v: Outcome2[A, B, O] < S): O < S =
            v match
                case next: Continue2[A, B] @unchecked => loop(idx + 1, run(idx + 1, next._1, next._2))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome2[A, B, O], O, S]:
                        def frame = _frame
                        def run[C, S2](o: Any, cont: Arrow[O, C, S2]): C < (S & S2) =
                            cont(loop(idx, Kyo.lift(o.asInstanceOf[Outcome2[A, B, O]])))
                    kyo.asInstanceOf[Kyo[Outcome2[A, B, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(0, run(0, input1, input2))
    end indexed

    /** Loops with the iteration index and three state values. */
    @nowarn("msg=anonymous")
    inline def indexed[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: (Int, A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame): O < S =
        @tailrec def loop(idx: Int, v: Outcome3[A, B, C, O] < S): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked => loop(idx + 1, run(idx + 1, next._1, next._2, next._3))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome3[A, B, C, O], O, S]:
                        def frame = _frame
                        def run[C2, S2](o: Any, cont: Arrow[O, C2, S2]): C2 < (S & S2) =
                            cont(loop(idx, Kyo.lift(o.asInstanceOf[Outcome3[A, B, C, O]])))
                    kyo.asInstanceOf[Kyo[Outcome3[A, B, C, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(0, run(0, input1, input2, input3))
    end indexed

    /** Loops with four state values and the iteration index. */
    @nowarn("msg=anonymous")
    inline def indexed[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: (Int, A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame): O < S =
        @tailrec def loop(idx: Int, v: Outcome4[A, B, C, D, O] < S): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked =>
                    loop(idx + 1, run(idx + 1, next._1, next._2, next._3, next._4))
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome4[A, B, C, D, O], O, S]:
                        def frame = _frame
                        def run[C2, S2](o: Any, cont: Arrow[O, C2, S2]): C2 < (S & S2) =
                            cont(loop(idx, Kyo.lift(o.asInstanceOf[Outcome4[A, B, C, D, O]])))
                    kyo.asInstanceOf[Kyo[Outcome4[A, B, C, D, O], S]].map(step)
                case res => res.asInstanceOf[O]
        loop(0, run(0, input1, input2, input3, input4))
    end indexed

    /** Loops until the iteration completes, without state. */
    @nowarn("msg=anonymous")
    inline def foreach[A, S](inline run: => Outcome[Unit, A] < S)(using inline _frame: Frame): A < S =
        @tailrec def loop(v: Outcome[Unit, A] < S): A < S =
            v match
                case next: Continue[Unit] @unchecked => loop(run)
                case kyo: Kyo[?, ?] =>
                    val step = new Arrow.Transform[Outcome[Unit, A], A, S]:
                        def frame = _frame
                        def run[C, S2](o: Any, cont: Arrow[A, C, S2]): C < (S & S2) =
                            cont(loop(Kyo.lift(o.asInstanceOf[Outcome[Unit, A]])))
                    kyo.asInstanceOf[Kyo[Outcome[Unit, A], S]].map(step)
                case res => res.asInstanceOf[A]
        loop(run)
    end foreach

    /** Repeats an operation a fixed number of times. */
    @nowarn("msg=anonymous")
    inline def repeat[S](n: Int)(inline run: Any < S)(using inline _frame: Frame): Unit < S =
        @tailrec def loop(i: Int): Unit < S =
            if i >= n then ()
            else
                run match
                    case kyo: Kyo[?, ?] =>
                        val step = new Arrow.Transform[Any, Unit, S]:
                            def frame = _frame
                            def run[C, S2](o: Any, cont: Arrow[Unit, C, S2]): C < (S & S2) =
                                cont(loop(i + 1))
                        kyo.asInstanceOf[Kyo[Any, S]].map(step)
                    case _ => loop(i + 1)
        loop(0)
    end repeat

    /** Loops indefinitely until interrupted by an effect. */
    inline def forever[S](inline run: Any < S)(using inline _frame: Frame): Nothing < S =
        def loop(): Nothing < S =
            run.map(_ => loop())
        loop()
    end forever

    /** Repeats an operation while the condition holds. */
    inline def whileTrue[S](inline condition: Boolean < S)(inline run: Any < S)(using inline _frame: Frame): Unit < S =
        def loop(): Unit < S =
            condition.map {
                case true  => run.map(_ => loop())
                case false => ()
            }
        loop()
    end whileTrue

end Loop
