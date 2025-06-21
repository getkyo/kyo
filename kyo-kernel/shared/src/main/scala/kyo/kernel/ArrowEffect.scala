package kyo.kernel

import kyo.*
import kyo.kernel.internal.*
import scala.annotation.nowarn
import scala.util.control.NonFatal

/** Represents abstract functions whose implementations are provided later by a handler.
  *
  * ArrowEffect captures the shape of a function without specifying its implementation. It describes a transformation from Input[A] to
  * Output[A] for any type A, but defers how that transformation actually happens until a handler interprets it. This makes it a powerful
  * way to write code that is abstract over how its operations are performed.
  *
  * ArrowEffect supports multi-shot continuations, meaning that handlers can invoke the continuation function multiple times or not at all.
  * This enables powerful control flow effects like backtracking, non-determinism, or early returns. For example, a choice effect could
  * invoke its continuation multiple times with different values to explore multiple execution paths.
  *
  * The type parameters Input[_] and Output[_] define the "shape" of the function being abstracted:
  *
  * @tparam Input
  *   The input type constructor - what arguments the function takes
  * @tparam Output
  *   The output type constructor - what results the function produces
  *
  * Every use of an ArrowEffect creates a suspended function call. This suspended call contains all the information needed to perform the
  * operation, but doesn't specify how to perform it.
  *
  * A handler then provides the actual function implementation that determines what happens when that suspended call is executed. Each
  * handler takes two parameters: an input value of type I[C] that contains the input of the operation, and a continuation function
  * representing the remainder of the computation from the point where the effect was suspended to the point where it's being handled.
  *
  * ArrowEffect provides two main kinds of handling methods with distinct capabilities:
  *   - handle: Basic handler that doesn't allow introducing new effects during handling
  *   - handleLoop: Enhanced handler that explicitly allows introducing new effects (via S2 type parameter) during handling:
  *     - Without state: When you need to add effects but not state between occurrences
  *     - With state: When you need both new effects and state maintenance between occurrences
  *
  * When defining concrete effects, ArrowEffect is commonly used with two special type constructors: Const and Id. The Const[X] type
  * constructor ignores its type parameter and always returns X, while Id[X] simply returns X unchanged. For instance, an effect that needs
  * to fail with errors of type E would use Const[E] as its input type - it only needs the error value itself, not any type parameters.
  * Similarly, an effect for making choices among values would use Id as its output type - it passes through the chosen value unchanged.
  */
abstract class ArrowEffect[-Input[_], +Output[_]] extends Effect

object ArrowEffect:

    /** Creates a suspended computation that requests a function implementation from an arrow effect. This establishes a requirement for a
      * function that must be satisfied by a handler higher up in the program. The requirement becomes part of the effect type, ensuring
      * that handlers must provide the requested function before the program can execute.
      *
      * @param effectTag
      *   Identifies which arrow effect to request the function from
      * @param funcionInput
      *   The input value to be transformed by the function
      * @return
      *   A computation that will receive the requested function when executed
      */
    @nowarn("msg=anonymous")
    inline def suspend[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O]](
        inline effectTag: Tag[E],
        inline funcionInput: I[A]
    ): O[A] < E =
        new KyoSuspend[I, O, E, A, O[A], E]:
            def frame = _frame
            def tag   = effectTag
            def input = funcionInput
            def apply(v: O[A], context: Context)(using Safepoint) =
                v

    /** Creates a suspended computation that requests a function implementation and transforms its result immediately upon receipt. This
      * combines the operations of requesting and transforming a function into a single step.
      *
      * @param effectTag
      *   Identifies which arrow effect to request the function from
      * @param funcionInput
      *   The input value to be transformed by the function
      * @param f
      *   The function to transform the handler's result
      * @return
      *   A computation containing the transformed result
      */
    @nowarn("msg=anonymous")
    inline def suspendWith[A](
        using inline _frame: Frame
    )[I[_], O[_], E <: ArrowEffect[I, O], B, S](
        inline effectTag: Tag[E],
        inline funcionInput: I[A]
    )(
        inline f: Safepoint ?=> O[A] => B < S
    ): B < (S & E) =
        new KyoSuspend[I, O, E, A, B, S & E]:
            def frame = _frame
            def tag   = effectTag
            def input = funcionInput
            def apply(v: O[A], context: Context)(using Safepoint) =
                Safepoint.handle(v)(
                    suspend = f(v),
                    continue = f(v)
                )

    /** WireTap into both the input and the output of an arrow effect as it is handled.
      *
      * This function allows you to intercept and modify both the input and the output of each effectful operation in a computation. The
      * `mapInput` function is applied to each input before the effect is handled, and the `mapOutput` function is applied to the output
      * value produced by the handler for the input, just before the continuation is invoked. Note that O[_] represents the output value
      * produced by the handler for a given input, not the final result of the computation.
      *
      * This is useful for scenarios where you want to preprocess the input or postprocess the handler's output before resuming the
      * computation.
      *
      * @note
      *   The S2 type parameter allows you to introduce new effects during the transformation of input and output. Any effects introduced by
      *   `mapInput` or `mapOutput` will be reflected in the resulting computation's effect type.
      * @param effectTag
      *   Identifies which arrow effect to tap into
      * @param v
      *   The computation requiring the function implementation
      * @param mapInput
      *   A function to transform the input before it is handled
      * @param mapOutput
      *   A function to transform the output value produced by the handler for the input before the continuation
      * @return
      *   The computation with transformed input and output for each effect occurrence
      */
    inline def wireTap[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline mapInput: [C] => I[C] => I[C] < S2,
        inline mapOutput: [C] => (I[C], O[C]) => O[C] < S2
    )(using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & E & S2) =
        handleLoop(effectTag, v):
            [C] =>
                (originalInput, cont) =>
                    mapInput[C](originalInput).map: input =>
                        suspendWith[C](effectTag, input): o =>
                            mapOutput[C](originalInput, o).map: o =>
                                Loop.continue:
                                    cont(o)
    end wireTap

    /** WireTap only the output of an arrow effect as it is handled.
      *
      * This function allows you to modify the output value produced by the handler for the input for each effectful operation in a
      * computation, leaving the input unchanged. The transformation is applied just before the continuation is invoked. O[_] here
      * represents the output value produced by the handler for the input, not the final result.
      *
      * @note
      *   The S2 type parameter allows you to introduce new effects during the transformation of the output. Any effects introduced by
      *   `mapOutput` will be reflected in the resulting computation's effect type.
      * @param effectTag
      *   Identifies which arrow effect to transform
      * @param v
      *   The computation requiring the function implementation
      * @param mapOutput
      *   A function to transform the output value produced by the handler for the input before the continuation
      * @return
      *   The computation with transformed output for each effect occurrence
      */
    inline def wireTapOutput[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline mapOutput: [C] => (I[C], O[C]) => O[C] < S2
    )(using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & E & S2) = wireTap(effectTag, v)(mapInput = [C] => i => i, mapOutput = mapOutput)

    /** wireTap only the input of an arrow effect as it is handled.
      *
      * This function allows you to modify the input of each effectful operation in a computation, leaving the output unchanged. O[_] is the
      * output value produced by the handler for the input, not the final result.
      *
      * @note
      *   The S2 type parameter allows you to introduce new effects during the transformation of the input. Any effects introduced by
      *   `mapInput` will be reflected in the resulting computation's effect type.
      * @param effectTag
      *   Identifies which arrow effect to transform
      * @param v
      *   The computation requiring the function implementation
      * @param mapInput
      *   A function to transform the input before it is handled
      * @return
      *   The computation with transformed input for each effect occurrence
      */
    inline def wireTapInput[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(inline mapInput: [C] => I[C] => I[C] < S2)(using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & E & S2) = wireTap(effectTag, v)(mapInput = mapInput, mapOutput = [C] => (_, o) => o)

    /** Handles an arrow effect by providing a handler function implementation.
      *
      * This is the basic form of effect handling where each effect occurrence is processed independently. Unlike handleLoop, handle does
      * not allow introducing new effects during the handling process (no S2 type parameter), making it more restrictive but simpler for
      * basic transformations.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation to provide
      * @return
      *   The computation result with the function implementation provided
      */
    inline def handle[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Safepoint ?=> O[C] => A < (E & S & S2)) => A < (E & S & S2)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & S2) =
        @nowarn("msg=anonymous")
        def handleLoop(v: A < (E & S & S2), context: Context)(using Safepoint): A < (S & S2) =
            v match
                case kyo: KyoSuspend[I, O, E, Any, A, E & S & S2] @unchecked if effectTag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle[Any](kyo.input, kyo(_, context)),
                        continue = handleLoop(_, context),
                        suspend = handleLoop(kyo, context)
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S & S2] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            handleLoop(kyo(v, context), context)
                    end new
                case kyo =>
                    kyo.unsafeGet
            end match
        end handleLoop
        handleLoop(v, Context.empty)
    end handle

    /** Handles two arrow effects by providing function implementations.
      *
      * @param effect1Tag
      *   First effect tag to handle
      * @param effect2Tag
      *   Second effect tag to handle
      * @param v
      *   The computation requiring the function implementations
      * @param handle1
      *   Implementation for first effect
      * @param handle2
      *   Implementation for second effect
      * @return
      *   The computation result with both function implementations provided
      */
    inline def handle[I1[_], O1[_], E1 <: ArrowEffect[I1, O1], I2[_], O2[_], E2 <: ArrowEffect[I2, O2], A, S, S2](
        inline effect1Tag: Tag[E1],
        inline effect2Tag: Tag[E2],
        v: A < (E1 & E2 & S)
    )(
        inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2),
        inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & S2) =
        @nowarn("msg=anonymous")
        def handle2Loop(kyo: A < (E1 & E2 & S & S2), context: Context)(using Safepoint): A < (S & S2) =
            kyo match
                case kyo: KyoSuspend[I1, O1, E1, Any, A, E1 & E2 & S & S2] @unchecked if effect1Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle1[Any](kyo.input, kyo(_, context)),
                        suspend = handle2Loop(kyo, context),
                        continue = handle2Loop(_, context)
                    )
                case kyo: KyoSuspend[I2, O2, E2, Any, A, E1 & E2 & S & S2] @unchecked if effect2Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle2[Any](kyo.input, kyo(_, context)),
                        suspend = handle2Loop(kyo, context),
                        continue = handle2Loop(_, context)
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E1 & E2 & S & S2] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            handle2Loop(kyo(v, context), context)
                    end new
                case kyo =>
                    kyo.unsafeGet
            end match
        end handle2Loop
        handle2Loop(v, Context.empty)
    end handle

    /** Handles three arrow effects by providing function implementations.
      *
      * @param effect1Tag
      *   First effect tag to handle
      * @param effect2Tag
      *   Second effect tag to handle
      * @param effect3Tag
      *   Third effect tag to handle
      * @param v
      *   The computation requiring the function implementations
      * @param handle1
      *   Implementation for first effect
      * @param handle2
      *   Implementation for second effect
      * @param handle3
      *   Implementation for third effect
      * @return
      *   The computation result with all three function implementations provided
      */
    inline def handle[I1[_], O1[_], E1 <: ArrowEffect[I1, O1], I2[_], O2[_], E2 <: ArrowEffect[I2, O2], I3[_], O3[_], E3 <: ArrowEffect[
        I3,
        O3
    ], A, S, S2](
        inline effect1Tag: Tag[E1],
        inline effect2Tag: Tag[E2],
        inline effect3Tag: Tag[E3],
        v: A < (E1 & E2 & E3 & S)
    )(
        inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & E3 & S & S2)) => A < (E1 & E2 & E3 & S & S2),
        inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & E3 & S & S2)) => A < (E1 & E2 & E3 & S & S2),
        inline handle3: [C] => (I3[C], Safepoint ?=> O3[C] => A < (E1 & E2 & E3 & S & S2)) => A < (E1 & E2 & E3 & S & S2)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & S2) =
        @nowarn("msg=anonymous")
        def handle3Loop(v: A < (E1 & E2 & E3 & S & S2), context: Context)(using Safepoint): A < (S & S2) =
            v match
                case kyo: KyoSuspend[I1, O1, E1, Any, A, E1 & E2 & E3 & S & S2] @unchecked if effect1Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle1[Any](kyo.input, kyo(_, context)),
                        suspend = handle3Loop(kyo, context),
                        continue = handle3Loop(_, context)
                    )
                case kyo: KyoSuspend[I2, O2, E2, Any, A, E1 & E2 & E3 & S & S2] @unchecked if effect2Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle2[Any](kyo.input, kyo(_, context)),
                        suspend = handle3Loop(kyo, context),
                        continue = handle3Loop(_, context)
                    )
                case kyo: KyoSuspend[I3, O3, E3, Any, A, E1 & E2 & E3 & S & S2] @unchecked if effect3Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle3[Any](kyo.input, kyo(_, context)),
                        suspend = handle3Loop(kyo, context),
                        continue = handle3Loop(_, context)
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E1 & E2 & E3 & S & S2] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            handle3Loop(kyo(v, context), context)
                    end new
                case kyo =>
                    kyo.unsafeGet
            end match
        end handle3Loop
        handle3Loop(v, Context.empty)
    end handle

    /** Handles four arrow effects by providing function implementations.
      *
      * @param effect1Tag
      *   First effect tag to handle
      * @param effect2Tag
      *   Second effect tag to handle
      * @param effect3Tag
      *   Third effect tag to handle
      * @param effect4Tag
      *   Fourth effect tag to handle
      * @param v
      *   The computation requiring the function implementations
      * @param handle1
      *   Implementation for first effect
      * @param handle2
      *   Implementation for second effect
      * @param handle3
      *   Implementation for third effect
      * @param handle4
      *   Implementation for fourth effect
      * @return
      *   The computation result with all four function implementations provided
      */
    inline def handle[I1[_], O1[_], E1 <: ArrowEffect[I1, O1], I2[_], O2[_], E2 <: ArrowEffect[I2, O2], I3[_], O3[_], E3 <: ArrowEffect[
        I3,
        O3
    ], I4[_], O4[_], E4 <: ArrowEffect[I4, O4], A, S, S2](
        inline effect1Tag: Tag[E1],
        inline effect2Tag: Tag[E2],
        inline effect3Tag: Tag[E3],
        inline effect4Tag: Tag[E4],
        v: A < (E1 & E2 & E3 & E4 & S)
    )(
        inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & E3 & E4 & S & S2)) => A < (E1 & E2 & E3 & E4 & S & S2),
        inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & E3 & E4 & S & S2)) => A < (E1 & E2 & E3 & E4 & S & S2),
        inline handle3: [C] => (I3[C], Safepoint ?=> O3[C] => A < (E1 & E2 & E3 & E4 & S & S2)) => A < (E1 & E2 & E3 & E4 & S & S2),
        inline handle4: [C] => (I4[C], Safepoint ?=> O4[C] => A < (E1 & E2 & E3 & E4 & S & S2)) => A < (E1 & E2 & E3 & E4 & S & S2)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & S2) =
        @nowarn("msg=anonymous")
        def handle4Loop(v: A < (E1 & E2 & E3 & E4 & S & S2), context: Context)(using Safepoint): A < (S & S2) =
            v match
                case kyo: KyoSuspend[I1, O1, E1, Any, A, E1 & E2 & E3 & E4 & S & S2] @unchecked if effect1Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle1[Any](kyo.input, kyo(_, context)),
                        suspend = handle4Loop(kyo, context),
                        continue = handle4Loop(_, context)
                    )
                case kyo: KyoSuspend[I2, O2, E2, Any, A, E1 & E2 & E3 & E4 & S & S2] @unchecked if effect2Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle2[Any](kyo.input, kyo(_, context)),
                        suspend = handle4Loop(kyo, context),
                        continue = handle4Loop(_, context)
                    )
                case kyo: KyoSuspend[I3, O3, E3, Any, A, E1 & E2 & E3 & E4 & S & S2] @unchecked if effect3Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle3[Any](kyo.input, kyo(_, context)),
                        suspend = handle4Loop(kyo, context),
                        continue = handle4Loop(_, context)
                    )
                case kyo: KyoSuspend[I4, O4, E4, Any, A, E1 & E2 & E3 & E4 & S & S2] @unchecked if effect4Tag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        eval = handle4[Any](kyo.input, kyo(_, context)),
                        suspend = handle4Loop(kyo, context),
                        continue = handle4Loop(_, context)
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E1 & E2 & E3 & E4 & S & S2] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            handle4Loop(kyo(v, context), context)
                    end new
                case kyo =>
                    kyo.unsafeGet
            end match
        end handle4Loop
        handle4Loop(v, Context.empty)
    end handle

    /** Handles the first occurrence of an arrow effect and transforms the final result. This is useful when you want to handle just the
      * first instance of an effect and transform its result into a different type, while leaving any subsequent occurrences of the effect
      * unhandled.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation containing the effect to handle
      * @param handle
      *   Function to handle the first occurrence of the effect and transform its result
      * @param done
      *   Function to transform the final result if no effect is found
      * @return
      *   The transformed computation result
      */
    inline def handleFirst[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2](effectTag: Tag[E], v: A < (E & S))(
        inline handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2,
        inline done: A => B < S2
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): B < (S & S2) =
        @nowarn("msg=anonymous")
        def handleFirstLoop(v: A < (E & S), context: Context)(using Safepoint): B < (S & S2) =
            v match
                case kyo: KyoSuspend[I, O, E, Any, A, E & S] @unchecked if effectTag <:< kyo.tag =>
                    Safepoint.handle(kyo.input)(
                        suspend = handleFirstLoop(kyo, context),
                        continue = handle[Any](kyo.input, kyo(_, context))
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            handleFirstLoop(kyo(v, context), context)
                    end new
                case kyo =>
                    done(kyo.unsafeGet)
            end match
        end handleFirstLoop
        handleFirstLoop(v, Context.empty)
    end handleFirst

    /** Handles an arrow effect with a loop-based approach for greater flexibility.
      *
      * This variant provides two key advantages over basic handle:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It provides control flow through the Loop abstraction to continue or terminate processing
      *
      * This non-stateful handleLoop is ideal when you need to perform effectful operations with access to new effects during handling, but
      * don't need to maintain state between effect occurrences.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @return
      *   The computation result with the function implementation provided
      */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2](
        inline effectTag: Tag[E],
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], Safepoint ?=> O[C] => A < (E & S)) => Loop.Outcome[A < (E & S), A] < S2
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & S2) =
        @nowarn("msg=anonymous")
        def handleLoopLoop(v: Loop.Outcome[A < (E & S), A] < S2, context: Context)(using Safepoint): A < (S & S2) =
            v match
                case _: KyoSuspend[?, ?, ?, ?, ?, ?] =>
                    v.map(handleLoopLoop(_, context))
                case continue: Loop.Continue[A < (E & S)] @unchecked =>
                    continue._1 match
                        case kyo: KyoSuspend[I, O, E, Any, A, E & S] @unchecked if effectTag <:< kyo.tag =>
                            handleLoopLoop(handle(kyo.input, kyo(_, context)), context)
                        case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S] @unchecked =>
                            new KyoContinue[IX, OX, EX, Any, A, S & S2](kyo):
                                def frame = _frame
                                def apply(v: OX[Any], context: Context)(using Safepoint) =
                                    handleLoopLoop(Loop.continue(kyo(v, context)), context)
                            end new
                        case kyo =>
                            kyo.asInstanceOf[A]
                case _ =>
                    v.asInstanceOf[A < (S & S2)]
        end handleLoopLoop
        handleLoopLoop(Loop.continue(v), Context.empty)
    end handleLoop

    /** Handles an arrow effect with stateful loop-based approach for maximum flexibility.
      *
      * This most powerful variant combines three key capabilities:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It provides control flow through the Loop abstraction to continue or terminate processing
      *   3. It maintains state between effect occurrences through the State type
      *
      * The stateful handleLoop should be used when you need the full range of capabilities: introducing new effects during handling,
      * maintaining state between occurrences, and controlling when to terminate processing.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param state
      *   The initial state value
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation that returns a Loop.Outcome for each iteration
      * @return
      *   The computation result with the function implementation provided
      */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], State, Safepoint ?=> O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), A] < S2
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (S & S2) =
        handleLoop[I, O, E, A, A, S, S2, State](effectTag, state, v)(handle, (_, v) => v)

    /** Handles an arrow effect with stateful loop-based approach and custom completion handling.
      *
      * This specialized variant of handleLoop provides maximum flexibility with three key capabilities:
      *   1. It explicitly allows introducing new effects during handling via the S2 type parameter
      *   2. It maintains state between effect occurrences through the State type
      *   3. It allows custom transformation of the final state and result via the done function
      *
      * The key advantage of this variant is the separate done function, which gives precise control over how the final state and result are
      * transformed when the loop completes. This is particularly useful when the final result needs different handling from intermediate
      * steps.
      *
      * @param effectTag
      *   Identifies which arrow effect to handle
      * @param state
      *   The initial state value
      * @param v
      *   The computation requiring the function implementation
      * @param handle
      *   The function implementation for each iteration
      * @param done
      *   The function to transform the final state and result
      * @return
      *   The computation result with the function implementation provided
      */
    inline def handleLoop[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, State](
        inline effectTag: Tag[E],
        state: State,
        v: A < (E & S)
    )(
        inline handle: [C] => (I[C], State, Safepoint ?=> O[C] => A < (E & S)) => Loop.Outcome2[State, A < (E & S), B] < S2,
        inline done: (State, A) => B < (S & S2)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): B < (S & S2) =
        @nowarn("msg=anonymous")
        def handleLoopLoop(v: Loop.Outcome2[State, A < (E & S), B] < S2, context: Context)(using Safepoint): B < (S & S2) =
            v match
                case _: KyoSuspend[?, ?, ?, ?, ?, ?] =>
                    v.map(handleLoopLoop(_, context))
                case continue: Loop.Continue2[State, A < (E & S)] @unchecked =>
                    val state = continue._1
                    continue._2 match
                        case kyo: KyoSuspend[I, O, E, Any, A, E & S] @unchecked if effectTag <:< kyo.tag =>
                            handleLoopLoop(handle(kyo.input, state, kyo(_, context)), context)
                        case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S] @unchecked =>
                            new KyoContinue[IX, OX, EX, Any, B, S & S2](kyo):
                                def frame = _frame
                                def apply(v: OX[Any], context: Context)(using Safepoint) =
                                    handleLoopLoop(Loop.continue(state, kyo(v, context)), context)
                            end new
                        case kyo =>
                            done(state, kyo.asInstanceOf[A])
                    end match
                case _ =>
                    v.asInstanceOf[B < (S & S2)] // Loop.done
        end handleLoopLoop
        handleLoopLoop(Loop.continue(state, v), Context.empty)
    end handleLoop

    private[kyo] inline def handleCatching[I[_], O[_], E <: ArrowEffect[I, O], A, B, S, S2, S3](
        inline effectTag: Tag[E],
        inline v: => A < (E & S)
    )(
        inline handle: [C] => (I[C], Safepoint ?=> O[C] => A < (E & S & S2)) => A < (E & S & S2),
        inline done: A => B < S3 = (v: A) => v,
        inline accept: [C] => I[C] => Boolean = [C] => (_: I[C]) => true,
        inline recover: Throwable => B < (S & S2 & S3)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): B < (S & S2 & S3) =
        @nowarn("msg=anonymous")
        def handleLoop(v: A < (E & S & S2 & S3), context: Context)(using Safepoint): B < (S & S2 & S3) =
            v match
                case kyo: KyoSuspend[I, O, E, Any, A, E & S & S2] @unchecked if effectTag <:< kyo.tag && accept(kyo.input) =>
                    Safepoint.handle(kyo.input)(
                        eval = handle[Any](kyo.input, kyo(_, context)),
                        continue = handleLoop(_, context),
                        suspend = handleLoop(kyo, context)
                    )
                case kyo: KyoSuspend[IX, OX, EX, Any, A, E & S & S2 & S3] @unchecked =>
                    new KyoContinue[IX, OX, EX, Any, B, S & S2 & S3](kyo):
                        def frame = _frame
                        def apply(v: OX[Any], context: Context)(using Safepoint) =
                            try handleLoop(kyo(v, context), context)
                            catch
                                case ex if NonFatal(ex) =>
                                    Safepoint.enrich(ex)
                                    recover(ex)
                        end apply
                    end new
                case kyo =>
                    done(kyo.unsafeGet)
            end match
        end handleLoop

        try handleLoop(v, Context.empty)
        catch
            case ex if NonFatal(ex) =>
                Safepoint.enrich(ex)
                recover(ex)
        end try
    end handleCatching

    private[kyo] inline def handlePartial[I1[_], O1[_], E1 <: ArrowEffect[I1, O1], I2[_], O2[_], E2 <: ArrowEffect[I2, O2], A, S, S2](
        inline tag1: Tag[E1],
        inline tag2: Tag[E2],
        v: A < (E1 & E2 & S),
        context: Context
    )(
        inline stop: => Boolean,
        inline handle1: [C] => (I1[C], Safepoint ?=> O1[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2),
        inline handle2: [C] => (I2[C], Safepoint ?=> O2[C] => A < (E1 & E2 & S & S2)) => A < (E1 & E2 & S & S2)
    )(
        using
        inline _frame: Frame,
        safepoint: Safepoint
    ): A < (E1 & E2 & S & S2) =
        def partialLoop(v: A < (E1 & E2 & S & S2), context: Context)(using safepoint: Safepoint): A < (E1 & E2 & S & S2) =
            if stop then v
            else
                v match
                    case kyo: KyoSuspend[?, ?, ?, ?, ?, ?] =>
                        type Suspend[I[_], O[_], E <: ArrowEffect[I, O]] = KyoSuspend[I, O, E, Any, A, E1 & E2 & S & S2]
                        if kyo.tag <:< Tag[Defer] then
                            val k = kyo.asInstanceOf[Suspend[Const[Unit], Const[Unit], Defer]]
                            partialLoop(k((), context), context)
                        else
                            safepoint.pushFrame(kyo.frame)
                            if tag1 <:< kyo.tag then
                                val k = kyo.asInstanceOf[Suspend[I1, O1, E1]]
                                partialLoop(handle1[Any](k.input, k(_, context)), context)
                            else if tag2 <:< kyo.tag then
                                val k = kyo.asInstanceOf[Suspend[I2, O2, E2]]
                                partialLoop(handle2[Any](k.input, k(_, context)), context)
                            else
                                v
                            end if
                        end if
                    case _ =>
                        v
                end match
        end partialLoop
        partialLoop(v, context)
    end handlePartial
end ArrowEffect
