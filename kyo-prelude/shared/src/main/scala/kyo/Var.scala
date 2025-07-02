package kyo

import Var.internal.*
import kyo.Tag
import kyo.kernel.*
import scala.annotation.nowarn

/** Represents mutable state in the Kyo effect system.
  *
  * `Var` provides a functional approach to mutable state, allowing computations to maintain and modify values throughout their execution.
  * Unlike traditional variables, changes to a `Var` are tracked as effects, preserving referential transparency while enabling stateful
  * operations.
  *
  * The effect encapsulates three fundamental operations:
  *   - Reading the current state via `get` and `use` methods
  *   - Setting a new state via `set` and `setDiscard` methods
  *   - Updating the state based on its current value via `update` and `updateDiscard` methods
  *
  * This simple API enables complex stateful patterns while keeping state changes explicit and manageable.
  *
  * State is isolated to specific computation scopes through the handlers like `run` and `runTuple`. When a computation completes, the state
  * can either be discarded or returned alongside the computation result, providing flexibility in how state is managed. Further isolation
  * strategies are available through the `Isolate`, allowing for sophisticated state management patterns.
  *
  * Var is valuable for implementing accumulators, local mutable caches, stateful parsers, or any computation requiring tracked state
  * modifications. It serves as a building block for higher-level stateful effects in the Kyo ecosystem.
  *
  * @tparam V
  *   The type of value stored in the state container
  *
  * @see
  *   [[kyo.Var.get]], [[kyo.Var.use]] for retrieving values
  * @see
  *   [[kyo.Var.set]], [[kyo.Var.update]] for modifying values
  * @see
  *   [[kyo.Var.run]], [[kyo.Var.runTuple]] for running computations with state
  * @see
  *   [[kyo.Var.isolate]] for state isolation strategies
  */
sealed trait Var[V] extends ArrowEffect[Const[Op[V]], Const[V]]

object Var:

    /** Obtains the current value of the 'Var'.
      *
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   The current value of the Var
      */
    inline def get[V](using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        use[V](identity)

    /** Invokes the provided function with the current value of the `Var`.
      *
      * @param f
      *   The function to apply to the current value
      * @tparam A
      *   The return type of the function
      * @tparam S
      *   Additional effects in the function
      * @return
      *   The result of applying the function to the current value
      */
    inline def use[V](
        using inline frame: Frame
    )[A, S](inline f: V => A < S)(
        using inline tag: Tag[Var[V]]
    ): A < (Var[V] & S) =
        ArrowEffect.suspendWith[V](tag, Get: Op[V])(f)

    /** Sets a new value and returns the previous one.
      *
      * @param value
      *   The new value to set
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   The previous value of the Var
      */
    inline def set[V](inline value: V)(using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        ArrowEffect.suspend[Unit](tag, value: Op[V])

    /** Sets a new value and then executes another computation.
      *
      * @param value
      *   The new value to set in the Var
      * @param f
      *   The computation to execute after setting the value
      * @return
      *   The result of the computation after setting the new value
      */
    inline def setWith[V, A, S](inline value: V)(inline f: => A < S)(using
        inline tag: Tag[Var[V]],
        inline frame: Frame
    ): A < (Var[V] & S) =
        ArrowEffect.suspendWith[Unit](tag, value: Op[V])(_ => f)

    /** Sets a new value and returns `Unit`.
      *
      * @param value
      *   The new value to set
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   Unit
      */
    inline def setDiscard[V](inline value: V)(using inline tag: Tag[Var[V]], inline frame: Frame): Unit < Var[V] =
        ArrowEffect.suspendWith[Unit](tag, value: Op[V])(_ => ())

    /** Applies the update function and returns the new value.
      *
      * @param f
      *   The update function to apply
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   The new value after applying the update function
      */
    inline def update[V](inline update: V => V)(using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        updateWith(update)(identity)

    @nowarn("msg=anonymous")
    inline def updateWith[V](inline update: V => V)[A, S](inline f: V => A < S)(
        using
        inline tag: Tag[Var[V]],
        inline frame: Frame
    ): A < (Var[V] & S) =
        ArrowEffect.suspendWith[V](tag, (v => update(v)): Update[V])(f)

    /** Applies the update function and returns `Unit`.
      *
      * @param f
      *   The update function to apply
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   Unit
      */
    @nowarn("msg=anonymous")
    inline def updateDiscard[V](inline f: V => V)(using inline tag: Tag[Var[V]], inline frame: Frame): Unit < Var[V] =
        ArrowEffect.suspendWith[Unit](tag, (v => f(v)): Update[V])(_ => ())

    private[kyo] inline def runWith[V, A, S, B, S2](state: V)(v: A < (Var[V] & S))(
        inline f: (V, A) => B < S2
    )(using inline tag: Tag[Var[V]], inline frame: Frame): B < (S & S2) =
        ArrowEffect.handleLoop(tag, state, v)(
            [C] =>
                (input, state, cont) =>
                    input match
                        case input: Get.type =>
                            Loop.continue(state, cont(state))
                        case input: Update[V] @unchecked =>
                            val nst = input(state)
                            Loop.continue(nst, cont(nst))
                        case input: V @unchecked =>
                            Loop.continue(input, cont(state)),
            done = f
        )

    /** Handles the effect and discards the 'Var' state.
      *
      * @param state
      *   The initial state of the Var
      * @param v
      *   The computation to run
      * @tparam V
      *   The type of the value stored in the Var
      * @tparam A
      *   The result type of the computation
      * @tparam S
      *   Additional effects in the computation
      * @return
      *   The result of the computation without the Var state
      */
    def run[V, A, S](state: V)(v: A < (Var[V] & S))(using Tag[Var[V]], Frame): A < S =
        runWith(state)(v)((_, result) => result)

    /** Handles the effect and returns a tuple with the final `Var` state and the computation's result.
      *
      * @param state
      *   The initial state of the Var
      * @param v
      *   The computation to run
      * @tparam V
      *   The type of the value stored in the Var
      * @tparam A
      *   The result type of the computation
      * @tparam S
      *   Additional effects in the computation
      * @return
      *   A tuple containing the final Var state and the result of the computation
      */
    def runTuple[V, A, S](state: V)(v: A < (Var[V] & S))(using Tag[Var[V]], Frame): (V, A) < S =
        runWith(state)(v)((state, result) => (state, result))

    object isolate:

        abstract private[isolate] class Base[V, P](using Tag[Var[V]]) extends Isolate.Stateful[Var[V], P]:

            type State = V

            type Transform[A] = (V, A)

            def capture[A, S2](f: V => A < S2)(using Frame) = Var.use(f)

            def isolate[A, S2](state: State, v: A < (Var[V] & S2))(using Frame) =
                Var.runTuple(state)(v)
        end Base

        /** Creates an isolate that sets the Var to its final isolated value.
          *
          * When the isolation ends, unconditionally updates the Var with the last value it had in the isolated computation.
          *
          * @tparam V
          *   The type of value in the Var
          * @return
          *   An isolate that updates the Var with its isolated value
          */
        def update[V](using Tag[Var[V]]): Isolate.Stateful[Var[V], Any] =
            new Base[V, Any]:
                def restore[A, S2](v: (V, A) < S2)(using Frame) =
                    v.map(Var.setWith(_)(_))

        /** Creates an isolate that merges Var values using a combination function.
          *
          * When the isolation ends, combines the Var's current value with the value from the isolated computation using the provided merge
          * function. Useful when you want to reconcile isolated Var modifications with any concurrent changes to the same Var.
          *
          * @param f
          *   Function that combines outer and isolated Var values
          * @tparam V
          *   The type of value in the Var
          * @return
          *   An isolate that merges Var values
          */
        def merge[V](using Tag[Var[V]])[S](f: (V, V) => V < S): Isolate.Stateful[Var[V], S] =
            new Base[V, S]:
                def restore[A, S2](v: (V, A) < S2)(using Frame): A < (Var[V] & S2 & S) =
                    v.map: (state, a) =>
                        Var.use[V]: prev =>
                            f(prev, state).map: next =>
                                Var.setWith(next)(a)

        /** Creates an isolate that keeps Var modifications local.
          *
          * Allows the isolated computation to read and modify the Var freely, but discards all modifications when the isolation ends. The
          * Var retains its original value as if the isolated modifications never happened.
          *
          * @tparam V
          *   The type of value in the Var
          * @return
          *   An isolate that discards Var modifications
          */
        def discard[V](using Tag[Var[V]]): Isolate.Stateful[Var[V], Any] =
            new Base[V, Any]:
                def restore[A, S2](v: (V, A) < S2)(using Frame) =
                    v.map(_._2)

    end isolate

    object internal:
        type Op[V] = Get.type | V | Update[V]
        object Get
        abstract class Update[V]:
            def apply(v: V): V
    end internal

end Var
