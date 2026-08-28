package kyo.proto

import kyo.Frame
import kyo.Maybe
import kyo.proto.Arrow.Transform
import kyo.proto.kernel.Effect
import kyo.proto.kernel.internal.Debugger
import kyo.proto.kernel.internal.Nested
import kyo.proto.kernel.internal.Pending
import kyo.proto.kernel.internal.Safepoint
import kyo.proto.kernel.internal.site
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.annotation.targetName

/** Provides utilities for creating and managing iterative computations with effects.
  *
  * While Kyo already provides stack-safe recursion through its core functionality, Loop offers a more performant and ergonomic way to write
  * iterative computations that can perform effects between iterations. It manages state between iterations and provides control over when
  * to continue or terminate the loop.
  *
  * Loops can maintain multiple state values (up to 4) between iterations through Continue variants. This enables complex stateful
  * computations while maintaining type safety and pure functional semantics.
  *
  * The outcome of each iteration is represented by an Outcome type, which can either signal continuation with new state values or
  * completion with a final result.
  */
object Loop:

    /** Represents the state to be carried forward to the next iteration of a loop.
      *
      * @tparam A
      *   The type of the single state value maintained between iterations
      */
    sealed abstract class Continue[A] extends Serializable:
        Debugger.get.onAlloc(this)
        private[kyo] def _1: A
        override def toString = s"Continue(${_1})"
    end Continue

    /** Represents the state of two values to be carried forward to the next iteration.
      *
      * @tparam A
      *   The type of the first state value
      * @tparam B
      *   The type of the second state value
      */
    sealed abstract class Continue2[A, B] extends Serializable:
        Debugger.get.onAlloc(this)
        private[kyo] def _1: A
        private[kyo] def _2: B
        override def toString = s"Continue2(${_1}, ${_2})"
    end Continue2

    /** Represents the state of three values to be carried forward to the next iteration.
      *
      * @tparam A
      *   The type of the first state value
      * @tparam B
      *   The type of the second state value
      * @tparam C
      *   The type of the third state value
      */
    sealed abstract class Continue3[A, B, C] extends Serializable:
        Debugger.get.onAlloc(this)
        private[kyo] def _1: A
        private[kyo] def _2: B
        private[kyo] def _3: C
        override def toString = s"Continue3(${_1}, ${_2}, ${_3})"
    end Continue3

    /** Represents the state of four values to be carried forward to the next iteration.
      *
      * @tparam A
      *   The type of the first state value
      * @tparam B
      *   The type of the second state value
      * @tparam C
      *   The type of the third state value
      * @tparam D
      *   The type of the fourth state value
      */
    sealed abstract class Continue4[A, B, C, D] extends Serializable:
        Debugger.get.onAlloc(this)
        private[kyo] def _1: A
        private[kyo] def _2: B
        private[kyo] def _3: C
        private[kyo] def _4: D
        override def toString = s"Continue4(${_1}, ${_2}, ${_3}, ${_4})"
    end Continue4

    /** Represents the result of a loop iteration, which can either continue with new state or complete with a final value.
      *
      * @tparam A
      *   The type of the state value if continuing
      * @tparam O
      *   The type of the final value if completing
      */
    // TODO Do we need to handle nesting like in the pending type? I've always wondred if we have unsoundess here
    // TODO Continue is the most common case by far, maybe it shouldn't be unboxed and done is boxed instead?
    opaque type Outcome[A, +O] = O | Continue[A]

    /** Represents the result of a loop iteration with two state values.
      *
      * @tparam A
      *   The type of the first state value if continuing
      * @tparam B
      *   The type of the second state value if continuing
      * @tparam O
      *   The type of the final value if completing
      */
    opaque type Outcome2[A, B, +O] = O | Continue2[A, B]

    /** Represents the result of a loop iteration with three state values.
      *
      * @tparam A
      *   The type of the first state value if continuing
      * @tparam B
      *   The type of the second state value if continuing
      * @tparam C
      *   The type of the third state value if continuing
      * @tparam O
      *   The type of the final value if completing
      */
    opaque type Outcome3[A, B, C, +O] = O | Continue3[A, B, C]

    /** Represents the result of a loop iteration with four state values.
      *
      * @tparam A
      *   The type of the first state value if continuing
      * @tparam B
      *   The type of the second state value if continuing
      * @tparam C
      *   The type of the third state value if continuing
      * @tparam D
      *   The type of the fourth state value if continuing
      * @tparam O
      *   The type of the final value if completing
      */
    opaque type Outcome4[A, B, C, D, +O] = O | Continue4[A, B, C, D]

    private val _continueUnit: Continue[Unit] =
        new Continue:
            def _1 = ()

    /** Creates an outcome signaling continuation with no state value.
      *
      * This is a convenience method for continuing a loop without maintaining any state between iterations. It's particularly useful for
      * simple loops that only need to track iteration progress.
      *
      * @return
      *   An Outcome indicating continuation with Unit state
      */
    inline def continue[A]: Outcome[Unit, A] < Any =
        // pending like the other continue arities, so a branch pairing this with a pending sibling
        // unifies instead of forming a union; the shared instance is never Boxed, so the cast asserts
        // exactly what the lift would have produced
        _continueUnit.asInstanceOf[Outcome[Unit, A] < Any]

    /** Creates an outcome signaling continuation with a single state value.
      *
      * The return type is pending on purpose: in a region clause this arity's slot is the answer, whose expected type is pending
      * (`O[C] < (E & S)`), and only a pending return lets that expected type reach the argument when the call sits behind another
      * combinator's lambda, where a raw return is typed before the conversion and the answer's type is minimized. The row is `Any` because
      * the outcome itself is settled, which conforms to every expected row. The value is the same raw `Continue`: it extends nothing but
      * `Serializable`, so it is valid union currency as-is and the cast asserts exactly what the lift would have produced.
      *
      * @param v
      *   The state value to continue with
      */
    @nowarn("msg=anonymous")
    inline def continue[A, O, S](inline v: A): Outcome[A, O] < S =
        // captures over vals: a capture field assigns before the carrier's constructor, so the
        // alloc hook can render the slots during construction
        val v0 = v
        (new Continue[A]:
            def _1 = v0
        ).asInstanceOf[Outcome[A, O] < S]
    end continue

    /** Creates an outcome signaling continuation with two state values.
      *
      * Pending return for the same reason as the single-value variant: in a stateful region clause the second slot is the answer, and the
      * pending return carries the expected answer type into the argument through nested lambdas.
      *
      * @param v1
      *   The first state value
      * @param v2
      *   The second state value
      */
    @nowarn("msg=anonymous")
    inline def continue[A, B, O](inline v1: A, inline v2: B): Outcome2[A, B, O] < Any =
        val v1x = v1
        val v2x = v2
        (new Continue2[A, B]:
            def _1 = v1x
            def _2 = v2x
        ).asInstanceOf[Outcome2[A, B, O] < Any]
    end continue

    /** Creates an outcome signaling continuation with three state values.
      *
      * @param v1
      *   The first state value
      * @param v2
      *   The second state value
      * @param v3
      *   The third state value
      */
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

    /** Creates an outcome signaling continuation with four state values.
      *
      * @param v1
      *   The first state value
      * @param v2
      *   The second state value
      * @param v3
      *   The third state value
      * @param v4
      *   The fourth state value
      */
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

    /** Creates an outcome signaling completion with no value.
      *
      * Pending return like the continue constructors, so a branch pairing done with a pending continue unifies instead of forming a union
      * the conversion cannot adapt; the raw unit is never Boxed, so the cast asserts exactly what the lift would have produced.
      */
    @targetName("done0")
    inline def done[A]: Outcome[A, Unit] < Any =
        ().asInstanceOf[Outcome[A, Unit] < Any]

    /** Creates an outcome signaling completion with a final value.
      *
      * Pending return for the same reason as done0, through the nest rather than a cast: the payload can be a computation held as data,
      * which the nest's runtime analysis wraps so the evaluator cannot mistake it for a suspension of the outcome itself.
      *
      * @param v
      *   The final value
      */
    @targetName("done1")
    inline def done[A, O](inline v: O): Outcome[A, O] < Any =
        // the nest, not a cast: a payload held as data is a pending node, and only the nest keeps the
        // eval from mistaking it for a suspension of the outcome
        Nested.nest[Outcome[A, O], Any](v)

    /** Creates an outcome signaling completion with a final value for a two-state loop.
      *
      * @param v
      *   The final value
      */
    @targetName("done2")
    inline def done[A, B, O](inline v: O): Outcome2[A, B, O] < Any =
        Nested.nest[Outcome2[A, B, O], Any](v)

    /** Creates an outcome signaling completion with a final value for a three-state loop.
      *
      * @param v
      *   The final value
      */
    @targetName("done3")
    inline def done[A, B, C, O](inline v: O): Outcome3[A, B, C, O] < Any =
        Nested.nest[Outcome3[A, B, C, O], Any](v)

    /** Creates an outcome signaling completion with a final value for a four-state loop.
      *
      * @param v
      *   The final value
      */
    @targetName("done4")
    inline def done[A, B, C, D, O](inline v: O): Outcome4[A, B, C, D, O] < Any =
        Nested.nest[Outcome4[A, B, C, D, O], Any](v)

    /** Executes a loop with a single state value.
      *
      * This method runs an iterative computation that maintains one state value between iterations. Each iteration can either continue with
      * a new state value or complete with a final result. The loop continues until an iteration produces a completion outcome.
      *
      * @param input
      *   The initial state value
      * @param run
      *   The function to execute for each iteration, receiving the current state and producing an outcome
      * @return
      *   The final result after loop completion
      */
    @nowarn("msg=anonymous")
    inline def apply[A, O, S](inline input: A)(inline run: A => Outcome[A, O] < S)(
        using inline _frame: Frame
    ): O < S =
        // the loop's re-entry arrow travels as a parameter: built at the first suspension and
        // reused by every later one, so an effectful loop pays one node per suspension, not a
        // node and an arrow. A loop that never suspends never builds it; a resumption re-enters
        // through the arrow, which passes itself back in
        @tailrec def loop(step: Maybe[Arrow[Outcome[A, O], O, S]], v: Outcome[A, O] < S): O < S =
            v match
                case next: Continue[A] @unchecked =>
                    loop(step, run(next._1))
                case kyo: Pending[Outcome[A, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Transform[Outcome[A, O], O, S]:
                            def frame             = _frame
                            override def toString = s"Transform(${site(frame)})"
                            def apply[C, S2](v: Outcome[A, O] < S2, cont: Arrow[O, C, S2]): C < (S & S2) =
                                v match
                                    case kyo: Pending[Outcome[A, O], S2] @unchecked =>
                                        Effect.defer(kyo, this, cont)
                                    case _ =>
                                        val slot = Safepoint.get()
                                        if !Safepoint.enter(slot) then Effect.defer(v, this, cont)
                                        else
                                            // the union passes through whole: the loop's arms speak the
                                            // representation (a raw Continue, a done value, a boxed payload),
                                            // so unnesting here and re-lifting at the call would be the
                                            // identity, and skipping the unnest is what keeps a done payload
                                            // held as data from being mistaken for a suspended outcome. The
                                            // cast is the protocol's row erasure: the arrow was built at S
                                            val out = cont.head(loop(Maybe(this), v.asInstanceOf[Outcome[A, O] < S]), cont.tail)
                                            Safepoint.exit(slot)
                                            out
                                        end if
                    }
                    Effect.defer(kyo, arrow, Arrow.id)
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input))
    end apply

    /** Executes a loop with two state values.
      *
      * Similar to the single-state version, but maintains two independent state values between iterations. This enables more complex
      * stateful computations while keeping the values properly typed and separated.
      *
      * @param input1
      *   The first initial state value
      * @param input2
      *   The second initial state value
      * @param run
      *   The function to execute for each iteration, receiving both current states and producing an outcome
      * @return
      *   The final result after loop completion
      */
    @nowarn("msg=anonymous")
    inline def apply[A, B, O, S](input1: A, input2: B)(inline run: (A, B) => Outcome2[A, B, O] < S)(
        using inline _frame: Frame
    ): O < S =
        // the re-entry arrow is shared across suspensions; see the single-state apply for the shape
        @tailrec def loop(step: Maybe[Arrow[Outcome2[A, B, O], O, S]], v: Outcome2[A, B, O] < S): O < S =
            v match
                case next: Continue2[A, B] @unchecked =>
                    loop(step, run(next._1, next._2))
                case kyo: Pending[Outcome2[A, B, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Transform[Outcome2[A, B, O], O, S]:
                            def frame             = _frame
                            override def toString = s"Transform(${site(frame)})"
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
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input1, input2))
    end apply

    /** Executes a loop with three state values.
      *
      * Maintains three independent state values between iterations, allowing for even more complex stateful computations while preserving
      * type safety and separation of concerns.
      *
      * @param input1
      *   The first initial state value
      * @param input2
      *   The second initial state value
      * @param input3
      *   The third initial state value
      * @param run
      *   The function to execute for each iteration, receiving all current states and producing an outcome
      * @return
      *   The final result after loop completion
      */
    @nowarn("msg=anonymous")
    inline def apply[A, B, C, O, S](input1: A, input2: B, input3: C)(
        inline run: (A, B, C) => Outcome3[A, B, C, O] < S
    )(using inline _frame: Frame): O < S =
        // the re-entry arrow is shared across suspensions; see the single-state apply for the shape
        @tailrec def loop(step: Maybe[Arrow[Outcome3[A, B, C, O], O, S]], v: Outcome3[A, B, C, O] < S): O < S =
            v match
                case next: Continue3[A, B, C] @unchecked =>
                    loop(step, run(next._1, next._2, next._3))
                case kyo: Pending[Outcome3[A, B, C, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Transform[Outcome3[A, B, C, O], O, S]:
                            def frame             = _frame
                            override def toString = s"Transform(${site(frame)})"
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
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input1, input2, input3))
    end apply

    /** Executes a loop with four state values.
      *
      * The most complex variant, maintaining four independent state values between iterations. This enables sophisticated stateful
      * computations while keeping all state values properly typed and organized.
      *
      * @param input1
      *   The first initial state value
      * @param input2
      *   The second initial state value
      * @param input3
      *   The third initial state value
      * @param input4
      *   The fourth initial state value
      * @param run
      *   The function to execute for each iteration, receiving all current states and producing an outcome
      * @return
      *   The final result after loop completion
      */
    @nowarn("msg=anonymous")
    inline def apply[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
        inline run: (A, B, C, D) => Outcome4[A, B, C, D, O] < S
    )(using inline _frame: Frame): O < S =
        // the re-entry arrow is shared across suspensions; see the single-state apply for the shape
        @tailrec def loop(step: Maybe[Arrow[Outcome4[A, B, C, D, O], O, S]], v: Outcome4[A, B, C, D, O] < S): O < S =
            v match
                case next: Continue4[A, B, C, D] @unchecked =>
                    loop(step, run(next._1, next._2, next._3, next._4))
                case kyo: Pending[Outcome4[A, B, C, D, O], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Transform[Outcome4[A, B, C, D, O], O, S]:
                            def frame             = _frame
                            override def toString = s"Transform(${site(frame)})"
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
                case res =>
                    res.asInstanceOf[O < S]
        loop(Maybe.empty, Loop.continue(input1, input2, input3, input4))
    end apply

    /** Executes an indexed loop without state values.
      *
      * This method runs an iterative computation that maintains a counter between iterations. Each iteration receives the current index and
      * can either continue to the next index or complete with a final result. The loop continues until an iteration produces a completion
      * outcome.
      *
      * @param run
      *   The function to execute for each iteration, receiving the current index and producing an outcome
      * @return
      *   The final result after loop completion
      */
    inline def indexed[O, S](inline run: Int => Outcome[Unit, O] < S)(using inline _frame: Frame): O < S =
        // the indexed variants and repeat keep the fresh-arrow-per-suspension map spelling: the
        // re-entry captures the index at suspension time, and a captured continuation replays from
        // capture-time state, so the arrow cannot be shared the way the stateless loops share theirs
        def suspended(idx: Int)(v: Outcome[Unit, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome[Unit, O] < S): O < S =
            v match
                case next: Continue[Unit] @unchecked =>
                    loop(idx + 1)(run(idx))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue)
    end indexed

    /** Executes an indexed loop with a single state value.
      *
      * Similar to the standard indexed loop, but also maintains one state value between iterations. Each iteration receives both the
      * current index and state value, enabling more complex stateful computations with index tracking.
      *
      * @param input
      *   The initial state value
      * @param run
      *   The function to execute for each iteration, receiving the current index and state, and producing an outcome
      * @return
      *   The final result after loop completion
      */
    inline def indexed[A, O, S](input: A)(inline run: (Int, A) => Outcome[A, O] < S)(using inline _frame: Frame): O < S =
        def suspended(idx: Int)(v: Outcome[A, O] < S): O < S =
            v.map(loop(idx)(_))
        @tailrec def loop(idx: Int)(v: Outcome[A, O] < S): O < S =
            v match
                case next: Continue[A] @unchecked =>
                    loop(idx + 1)(run(idx, next._1))
                case _: Arrow[?, ?, ?] =>
                    suspended(idx)(v)
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input))
    end indexed

    /** Executes an indexed loop with two state values.
      *
      * Maintains two independent state values between iterations along with an index counter. This enables complex stateful computations
      * that need to track iteration count while managing multiple state values.
      *
      * @param input1
      *   The first initial state value
      * @param input2
      *   The second initial state value
      * @param run
      *   The function to execute for each iteration, receiving the current index and both states
      * @return
      *   The final result after loop completion
      */
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
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input1, input2))
    end indexed

    /** Executes an indexed loop with three state values.
      *
      * Maintains three independent state values between iterations along with an index counter. This enables sophisticated stateful
      * computations that need to track iteration count while managing multiple state values.
      *
      * @param input1
      *   The first initial state value
      * @param input2
      *   The second initial state value
      * @param input3
      *   The third initial state value
      * @param run
      *   The function to execute for each iteration, receiving the current index and all states
      * @return
      *   The final result after loop completion
      */
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
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input1, input2, input3))
    end indexed

    /** Executes an indexed loop with four state values.
      *
      * The most complex indexed variant, maintaining four independent state values between iterations along with an index counter. This
      * enables highly sophisticated stateful computations that need to track iteration count while managing multiple state values.
      *
      * @param input1
      *   The first initial state value
      * @param input2
      *   The second initial state value
      * @param input3
      *   The third initial state value
      * @param input4
      *   The fourth initial state value
      * @param run
      *   The function to execute for each iteration, receiving the current index and all states
      * @return
      *   The final result after loop completion
      */
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
                case res =>
                    res.asInstanceOf[O < S]
        loop(0)(Loop.continue(input1, input2, input3, input4))
    end indexed

    /** Executes a loop that continues until explicitly completed.
      *
      * This method runs a loop that will continue executing until an iteration produces a completion outcome. It's useful for loops that
      * need to run indefinitely or until some condition is met.
      *
      * @param run
      *   The function to execute repeatedly until completion
      * @return
      *   The final value after the loop completes
      */
    @nowarn("msg=anonymous")
    inline def foreach[A, S](inline run: Outcome[Unit, A] < S)(using inline _frame: Frame): A < S =
        // the re-entry arrow is shared across suspensions; see the single-state apply for the shape
        @tailrec def loop(step: Maybe[Arrow[Outcome[Unit, A], A, S]], v: Outcome[Unit, A] < S): A < S =
            v match
                case next: Continue[Unit] @unchecked =>
                    loop(step, run)
                case kyo: Pending[Outcome[Unit, A], S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Transform[Outcome[Unit, A], A, S]:
                            def frame             = _frame
                            override def toString = s"Transform(${site(frame)})"
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
                case res =>
                    res.asInstanceOf[A < S]
        loop(Maybe.empty, Loop.continue)
    end foreach

    /** Repeats an operation a specified number of times.
      *
      * A simpler looping construct that executes an operation exactly n times. Unlike other loop variants, this doesn't maintain state or
      * require explicit continuation/completion decisions.
      *
      * @param n
      *   The number of times to repeat the operation
      * @param run
      *   The operation to repeat
      * @return
      *   Unit after completing all iterations
      */
    // the count is checked before `run` is reached rather than after, so the body is evaluated exactly n
    // times. Carrying the previous iteration's value into the check instead evaluates `run` once more than
    // that: harmless for a deferred body, where the extra evaluation only builds a node that is then
    // discarded unexecuted, and one extra execution for a settled one
    inline def repeat[S](n: Int)(inline run: Any < S)(using inline _frame: Frame): Unit < S =
        def suspended(i: Int)(v: Any < S): Unit < S =
            v.map(_ => loop(i))
        @tailrec def loop(i: Int): Unit < S =
            if i >= n then ()
            else
                // bound at the row before it is matched, the way every other loop here takes its scrutinee
                // as a typed parameter. Matching the inline parameter itself leaves the expansion carrying
                // whatever the call site passed, and the exhaustivity check reads the arms against that
                // rather than against the row
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

    /** Executes a loop indefinitely until explicitly terminated.
      *
      * Creates an infinite loop that will continue executing until interrupted by some other means (like an effect handler). This is useful
      * for creating long-running processes or servers that should run continuously.
      *
      * @param run
      *   The function to execute repeatedly
      * @return
      *   Nothing, as this loop runs forever unless interrupted
      */
    @nowarn("msg=anonymous")
    inline def forever[S](inline run: Any < S)(using inline _frame: Frame): Nothing < S =
        // the re-entry arrow is shared across suspensions; see the single-state apply for the shape.
        // Delivery discards the settled value, so no representation crosses it
        @tailrec def loop(step: Maybe[Arrow[Any, Nothing, S]], v: Any < S): Nothing < S =
            v match
                case kyo: Pending[Any, S] @unchecked =>
                    val arrow = step.getOrElse {
                        new Transform[Any, Nothing, S]:
                            def frame             = _frame
                            override def toString = s"Transform(${site(frame)})"
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

    /** Executes an operation repeatedly while a condition remains true.
      *
      * @param condition
      *   The condition to check before each iteration. The loop continues while this is true.
      * @param run
      *   The operation to execute in each iteration
      * @return
      *   Unit after the loop completes
      */
    @nowarn("msg=anonymous")
    inline def whileTrue[S](inline condition: Boolean < S)(inline run: Unit < S)(
        using inline _frame: Frame
    ): Unit < S =
        // the body's re-entry arrow is shared across suspensions; see the single-state apply for the
        // shape. Delivery discards the settled body value, so no representation crosses it. The
        // condition's own suspensions stay on the map spelling: its continuation captures the body
        // value it sequences after
        def loop(step: Maybe[Arrow[Any, Unit, S]], v: Unit < S): Unit < S =
            condition.map {
                case true =>
                    v match
                        case kyo: Pending[Any, S] @unchecked =>
                            val arrow = step.getOrElse {
                                new Transform[Any, Unit, S]:
                                    def frame             = _frame
                                    override def toString = s"Transform(${site(frame)})"
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
