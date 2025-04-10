package kyo.kernel2

import kyo.Frame
import kyo.kernel2.internal.*
import scala.annotation.nowarn
import scala.annotation.tailrec
import scala.annotation.targetName
import scala.util.NotGiven

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
    abstract class Continue[A]:
        private[Loop] def _1: A

    /** Represents the state of two values to be carried forward to the next iteration.
      *
      * @tparam A
      *   The type of the first state value
      * @tparam B
      *   The type of the second state value
      */
    abstract class Continue2[A, B]:
        private[Loop] def _1: A
        private[Loop] def _2: B

    /** Represents the state of three values to be carried forward to the next iteration.
      *
      * @tparam A
      *   The type of the first state value
      * @tparam B
      *   The type of the second state value
      * @tparam C
      *   The type of the third state value
      */
    abstract class Continue3[A, B, C]:
        private[Loop] def _1: A
        private[Loop] def _2: B
        private[Loop] def _3: C
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
    abstract class Continue4[A, B, C, D]:
        private[Loop] def _1: A
        private[Loop] def _2: B
        private[Loop] def _3: C
        private[Loop] def _4: D
    end Continue4

    /** Represents the result of a loop iteration, which can either continue with new state or complete with a final value.
      *
      * @tparam A
      *   The type of the state value if continuing
      * @tparam O
      *   The type of the final value if completing
      */
    opaque type Outcome[A, O] = O | Continue[A]

    /** Represents the result of a loop iteration with two state values.
      *
      * @tparam A
      *   The type of the first state value if continuing
      * @tparam B
      *   The type of the second state value if continuing
      * @tparam O
      *   The type of the final value if completing
      */
    opaque type Outcome2[A, B, O] = O | Continue2[A, B]

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
    opaque type Outcome3[A, B, C, O] = O | Continue3[A, B, C]

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
    opaque type Outcome4[A, B, C, D, O] = O | Continue4[A, B, C, D]

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
    inline def continue[A]: Outcome[Unit, A] = _continueUnit

    /** Creates an outcome signaling continuation with a single state value.
      *
      * @param v
      *   The state value to continue with
      */
    @nowarn("msg=anonymous")
    inline def continue[A, O, S](inline v: A): Outcome[A, O] =
        new Continue:
            def _1 = v

    /** Creates an outcome signaling continuation with two state values.
      *
      * @param v1
      *   The first state value
      * @param v2
      *   The second state value
      */
    @nowarn("msg=anonymous")
    inline def continue[A, B, o](inline v1: A, inline v2: B): Outcome2[A, B, o] =
        new Continue2:
            def _1 = v1
            def _2 = v2

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
    inline def continue[A, B, C, O](inline v1: A, inline v2: B, inline v3: C): Outcome3[A, B, C, O] =
        new Continue3:
            def _1 = v1
            def _2 = v2
            def _3 = v3

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
    inline def continue[A, B, C, D, O](inline v1: A, inline v2: B, inline v3: C, inline v4: D): Outcome4[A, B, C, D, O] =
        new Continue4:
            def _1 = v1
            def _2 = v2
            def _3 = v3
            def _4 = v4

    /** Creates an outcome signaling completion with no value. */
    @targetName("done0")
    def done[A]: Outcome[A, Unit] = ()

    /** Creates an outcome signaling completion with a final value.
      *
      * @param v
      *   The final value
      */
    @targetName("done1")
    def done[A, O](v: O): Outcome[A, O] = v

    /** Creates an outcome signaling completion with a final value for a two-state loop.
      *
      * @param v
      *   The final value
      */
    @targetName("done2")
    def done[A, B, O](v: O): Outcome2[A, B, O] = v

    /** Creates an outcome signaling completion with a final value for a three-state loop.
      *
      * @param v
      *   The final value
      */
    @targetName("done3")
    def done[A, B, C, O](v: O): Outcome3[A, B, C, O] = v

    /** Creates an outcome signaling completion with a final value for a four-state loop.
      *
      * @param v
      *   The final value
      */
    @targetName("done4")
    def done[A, B, C, D, O](v: O): Outcome4[A, B, C, D, O] = v

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
    inline def apply[A, O, S](input: A)(inline run: Safepoint ?=> A => Outcome[A, O] < S)(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): O < S =
        val arrow =
            Arrow.loop[Outcome[A, O], O, S] { self =>
                _ match
                    case next: Continue[A] @unchecked =>
                        self(run(next._1))
                    case done =>
                        done.asInstanceOf[O]
            }
        arrow(Loop.continue(input))
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
    inline def apply[A, B, O, S](input1: A, input2: B)(inline run: Safepoint ?=> (A, B) => Outcome2[A, B, O] < S)(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): O < S =
        val arrow =
            Arrow.loop[Outcome2[A, B, O], O, S] { self =>
                _ match
                    case next: Continue2[A, B] @unchecked =>
                        self(run(next._1, next._2))
                    case done =>
                        done.asInstanceOf[O]
            }
        arrow(Loop.continue(input1, input2))
    end apply

    // /** Executes a loop with four state values.
    //   *
    //   * The most complex variant, maintaining four independent state values between iterations. This enables sophisticated stateful
    //   * computations while keeping all state values properly typed and organized.
    //   *
    //   * @param input1
    //   *   The first initial state value
    //   * @param input2
    //   *   The second initial state value
    //   * @param input3
    //   *   The third initial state value
    //   * @param input4
    //   *   The fourth initial state value
    //   * @param run
    //   *   The function to execute for each iteration, receiving all current states and producing an outcome
    //   * @return
    //   *   The final result after loop completion
    //   */
    // inline def apply[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
    //     inline run: Safepoint ?=> (A, B, C, D) => Outcome4[A, B, C, D, O] < S
    // )(using inline _frame: Frame, safepoint: Safepoint): O < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(v: Outcome4[A, B, C, D, O] < S)(using Safepoint): O < S =
    //         v match
    //             case next: Continue4[A, B, C, D] @unchecked =>
    //                 loop(run(next._1, next._2, next._3, next._4))
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome4[A, B, C, D, O], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, O, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(kyo(v, context))
    //             case res =>
    //                 res.asInstanceOf[O]
    //     loop(Loop.continue(input1, input2, input3, input4))
    // end apply

    // /** Executes an indexed loop without state values.
    //   *
    //   * This method runs an iterative computation that maintains a counter between iterations. Each iteration receives the current index and
    //   * can either continue to the next index or complete with a final result. The loop continues until an iteration produces a completion
    //   * outcome.
    //   *
    //   * @param run
    //   *   The function to execute for each iteration, receiving the current index and producing an outcome
    //   * @return
    //   *   The final result after loop completion
    //   */
    // inline def indexed[O, S](inline run: Int => Outcome[Unit, O] < S)(using
    //     inline _frame: Frame,
    //     safepoint: Safepoint
    // ): O < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(idx: Int)(v: Outcome[Unit, O] < S)(using Safepoint): O < S =
    //         v match
    //             case next: Continue[Unit] @unchecked =>
    //                 loop(idx + 1)(run(idx))
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[Unit, O], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, O, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(idx)(kyo(v, context))
    //             case res =>
    //                 res.asInstanceOf[O]
    //     loop(0)(Loop.continue)
    // end indexed

    // /** Executes an indexed loop with a single state value.
    //   *
    //   * Similar to the standard indexed loop, but also maintains one state value between iterations. Each iteration receives both the
    //   * current index and state value, enabling more complex stateful computations with index tracking.
    //   *
    //   * @param input
    //   *   The initial state value
    //   * @param run
    //   *   The function to execute for each iteration, receiving the current index and state, and producing an outcome
    //   * @return
    //   *   The final result after loop completion
    //   */
    // inline def indexed[A, O, S](input: A)(inline run: Safepoint ?=> (Int, A) => Outcome[A, O] < S)(using
    //     inline _frame: Frame,
    //     safepoint: Safepoint
    // ): O < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(idx: Int)(v: Outcome[A, O] < S)(using Safepoint): O < S =
    //         v match
    //             case next: Continue[A] @unchecked =>
    //                 loop(idx + 1)(run(idx, next._1))
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[A, O], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, O, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(idx)(kyo(v, context))
    //             case res =>
    //                 res.asInstanceOf[O]
    //     loop(0)(Loop.continue(input))
    // end indexed

    // /** Executes an indexed loop with two state values.
    //   *
    //   * Maintains two independent state values between iterations along with an index counter. This enables complex stateful computations
    //   * that need to track iteration count while managing multiple state values.
    //   *
    //   * @param input1
    //   *   The first initial state value
    //   * @param input2
    //   *   The second initial state value
    //   * @param run
    //   *   The function to execute for each iteration, receiving the current index and both states
    //   * @return
    //   *   The final result after loop completion
    //   */
    // inline def indexed[A, B, O, S](input1: A, input2: B)(
    //     inline run: Safepoint ?=> (Int, A, B) => Outcome2[A, B, O] < S
    // )(using inline _frame: Frame, safepoint: Safepoint): O < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(idx: Int)(v: Outcome2[A, B, O] < S)(using Safepoint): O < S =
    //         v match
    //             case next: Continue2[A, B] @unchecked =>
    //                 loop(idx + 1)(run(idx, next._1, next._2))
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome2[A, B, O], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, O, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(idx)(kyo(v, context))
    //             case res =>
    //                 res.asInstanceOf[O]
    //     loop(0)(Loop.continue(input1, input2))
    // end indexed

    // /** Executes an indexed loop with three state values.
    //   *
    //   * Maintains three independent state values between iterations along with an index counter. This enables sophisticated stateful
    //   * computations that need to track iteration count while managing multiple state values.
    //   *
    //   * @param input1
    //   *   The first initial state value
    //   * @param input2
    //   *   The second initial state value
    //   * @param input3
    //   *   The third initial state value
    //   * @param run
    //   *   The function to execute for each iteration, receiving the current index and all states
    //   * @return
    //   *   The final result after loop completion
    //   */
    // inline def indexed[A, B, C, O, S](input1: A, input2: B, input3: C)(
    //     inline run: Safepoint ?=> (Int, A, B, C) => Outcome3[A, B, C, O] < S
    // )(using inline _frame: Frame, safepoint: Safepoint): O < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(idx: Int)(v: Outcome3[A, B, C, O] < S)(using Safepoint): O < S =
    //         v match
    //             case next: Continue3[A, B, C] @unchecked =>
    //                 loop(idx + 1)(run(idx, next._1, next._2, next._3))
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome3[A, B, C, O], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, O, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(idx)(kyo(v, context))
    //             case res =>
    //                 res.asInstanceOf[O]
    //     loop(0)(Loop.continue(input1, input2, input3))
    // end indexed

    // /** Executes an indexed loop with four state values.
    //   *
    //   * The most complex indexed variant, maintaining four independent state values between iterations along with an index counter. This
    //   * enables highly sophisticated stateful computations that need to track iteration count while managing multiple state values.
    //   *
    //   * @param input1
    //   *   The first initial state value
    //   * @param input2
    //   *   The second initial state value
    //   * @param input3
    //   *   The third initial state value
    //   * @param input4
    //   *   The fourth initial state value
    //   * @param run
    //   *   The function to execute for each iteration, receiving the current index and all states
    //   * @return
    //   *   The final result after loop completion
    //   */
    // inline def indexed[A, B, C, D, O, S](input1: A, input2: B, input3: C, input4: D)(
    //     inline run: Safepoint ?=> (Int, A, B, C, D) => Outcome4[A, B, C, D, O] < S
    // )(using inline _frame: Frame, safepoint: Safepoint): O < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(idx: Int)(v: Outcome4[A, B, C, D, O] < S)(using Safepoint): O < S =
    //         v match
    //             case next: Continue4[A, B, C, D] @unchecked =>
    //                 loop(idx + 1)(run(idx, next._1, next._2, next._3, next._4))
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome4[A, B, C, D, O], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, O, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(idx)(kyo(v, context))
    //             case res =>
    //                 res.asInstanceOf[O]
    //     loop(0)(Loop.continue(input1, input2, input3, input4))
    // end indexed

    // /** Executes a loop that continues until explicitly completed.
    //   *
    //   * This method runs a loop that will continue executing until an iteration produces a completion outcome. It's useful for loops that
    //   * need to run indefinitely or until some condition is met.
    //   *
    //   * @param run
    //   *   The function to execute repeatedly until completion
    //   * @return
    //   *   Unit after the loop completes
    //   */
    // inline def foreach[S](inline run: Safepoint ?=> Outcome[Unit, Unit] < S)(using inline _frame: Frame, safepoint: Safepoint): Unit < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(v: Outcome[Unit, Unit] < S)(using Safepoint): Unit < S =
    //         v match
    //             case next: Continue[Unit] @unchecked =>
    //                 loop(run)
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Outcome[Unit, Unit], S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(kyo(v, context))
    //             case res =>
    //                 ()
    //     loop(Loop.continue)
    // end foreach

    // /** Repeats an operation a specified number of times.
    //   *
    //   * A simpler looping construct that executes an operation exactly n times. Unlike other loop variants, this doesn't maintain state or
    //   * require explicit continuation/completion decisions.
    //   *
    //   * @param n
    //   *   The number of times to repeat the operation
    //   * @param run
    //   *   The operation to repeat
    //   * @return
    //   *   Unit after completing all iterations
    //   */
    // inline def repeat[S](n: Int)(inline run: Safepoint ?=> Any < S)(using inline _frame: Frame, safepoint: Safepoint): Unit < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(i: Int)(v: Any < S)(using Safepoint): Unit < S =
    //         if i > n then ()
    //         else
    //             v match
    //                 case kyo: KyoSuspend[IX, OX, EX, Any, Unit, S] @unchecked =>
    //                     new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
    //                         def frame = _frame
    //                         def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                             loop(i)(kyo(v, context))
    //                 case _ =>
    //                     loop(i + 1)(run)
    //         end if
    //     end loop
    //     loop(0)(())
    // end repeat

    // /** Executes a loop indefinitely until explicitly terminated.
    //   *
    //   * Creates an infinite loop that will continue executing until interrupted by some other means (like an effect handler). This is useful
    //   * for creating long-running processes or servers that should run continuously.
    //   *
    //   * @param run
    //   *   The function to execute repeatedly
    //   * @return
    //   *   Nothing, as this loop runs forever unless interrupted
    //   */
    // inline def forever[S](inline run: Safepoint ?=> Any < S)(using inline _frame: Frame, safepoint: Safepoint): Unit < S =
    //     @nowarn("msg=anonymous")
    //     @tailrec def loop(v: Any < S)(using Safepoint): Unit < S =
    //         v match
    //             case kyo: KyoSuspend[IX, OX, EX, Any, Unit, S] @unchecked =>
    //                 new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
    //                     def frame = _frame
    //                     def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                         loop(kyo(v, context))
    //             case _ =>
    //                 loop(run)
    //     end loop
    //     loop(())
    // end forever

    // /** Executes an operation repeatedly while a condition remains true.
    //   *
    //   * @param condition
    //   *   The condition to check before each iteration. The loop continues while this is true.
    //   * @param run
    //   *   The operation to execute in each iteration
    //   * @return
    //   *   Unit after the loop completes
    //   */
    // inline def whileTrue[S](inline condition: Safepoint ?=> Boolean < S)(inline run: Unit < S)(
    //     using
    //     inline _frame: Frame,
    //     safepoint: Safepoint
    // ): Unit < S =
    //     @nowarn("msg=anonymous")
    //     def loop(v: Unit < S)(using Safepoint): Unit < S =
    //         condition.map {
    //             case true =>
    //                 v match
    //                     case kyo: KyoSuspend[IX, OX, EX, Any, Unit, S] @unchecked =>
    //                         new KyoContinue[IX, OX, EX, Any, Unit, S](kyo):
    //                             def frame = _frame
    //                             def apply(v: OX[Any], context: Context)(using Safepoint) =
    //                                 loop(kyo(v, context))
    //                     case _ =>
    //                         loop(run)
    //             case false => ()
    //         }
    //     end loop
    //     loop(())
    // end whileTrue
end Loop
