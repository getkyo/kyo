package kyo

import Var.internal.*
import kyo.Tag
import kyo.kernel.*
import scala.annotation.nowarn

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

    final class UseOps[V](dummy: Unit) extends AnyVal:
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
        inline def apply[A, S](inline f: V => A < S)(
            using
            inline tag: Tag[Var[V]],
            inline frame: Frame
        ): A < (Var[V] & S) =
            ArrowEffect.suspendAndMap[V](tag, Get: Op[V])(f)
    end UseOps

    /** Creates a new UseOps instance for the given type V.
      *
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   A new UseOps instance
      */
    inline def use[V]: UseOps[V] = UseOps(())

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
    private[kyo] inline def setAndThen[V, A, S](inline value: V)(inline f: => A < S)(using
        inline tag: Tag[Var[V]],
        inline frame: Frame
    ): A < (Var[V] & S) =
        ArrowEffect.suspendAndMap[Unit](tag, value: Op[V])(_ => f)

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
        ArrowEffect.suspendAndMap[Unit](tag, value: Op[V])(_ => ())

    /** Applies the update function and returns the new value.
      *
      * @param f
      *   The update function to apply
      * @tparam V
      *   The type of the value stored in the Var
      * @return
      *   The new value after applying the update function
      */
    @nowarn("msg=anonymous")
    inline def update[V](inline f: V => V)(using inline tag: Tag[Var[V]], inline frame: Frame): V < Var[V] =
        ArrowEffect.suspend[V](tag, (v => f(v)): Update[V])

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
        ArrowEffect.suspendAndMap[Unit](tag, (v => f(v)): Update[V])(_ => ())

    private[kyo] inline def runWith[V, A: Flat, S, B, S2](state: V)(v: A < (Var[V] & S))(
        inline f: (V, A) => B < S2
    )(using inline tag: Tag[Var[V]], inline frame: Frame): B < (S & S2) =
        ArrowEffect.handleState(tag, state, v)(
            [C] =>
                (input, state, cont) =>
                    input match
                        case input: Get.type =>
                            (state, cont(state))
                        case input: Update[V] @unchecked =>
                            val nst = input(state)
                            (nst, cont(nst))
                        case input: V @unchecked =>
                            (input, cont(state)),
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
    def run[V, A: Flat, S](state: V)(v: A < (Var[V] & S))(using Tag[Var[V]], Frame): A < S =
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
    def runTuple[V, A: Flat, S](state: V)(v: A < (Var[V] & S))(using Tag[Var[V]], Frame): (V, A) < S =
        runWith(state)(v)((state, result) => (state, result))

    object isolate:
        abstract private[kyo] class Base[V: Tag] extends Isolate[Var[V]]:
            type State = V
            def use[A, S2](f: V => A < S2)(using Frame) = Var.use(f)
            def resume[A: Flat, S2](state: State, v: A < (Var[V] & S2))(using Frame) =
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
        def update[V: Tag]: Isolate[Var[V]] =
            new Base[V]:
                def restore[A: Flat, S2](state: V, v: A < S2)(using Frame) =
                    Var.set(state).andThen(v)

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
        def merge[V: Tag](f: (V, V) => V): Isolate[Var[V]] =
            new Base[V]:
                def restore[A: Flat, S2](state: V, v: A < S2)(using Frame) =
                    Var.use[V](prev => Var.set(f(prev, state)).andThen(v))

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
        def discard[V: Tag]: Isolate[Var[V]] =
            new Base[V]:
                def restore[A: Flat, S2](state: V, v: A < S2)(using Frame) =
                    v

    end isolate

    object internal:
        type Op[V] = Get.type | V | Update[V]
        object Get
        abstract class Update[V]:
            def apply(v: V): V
    end internal

end Var
