package kyo

import Aspect.*

/** Represents an aspect in the Kyo effect system.
  *
  * An aspect provides a way to modify or intercept behavior across multiple points in a program without directly changing the affected
  * code. It works by allowing users to provide implementations for abstract operations at runtime, similar to dependency injection but with
  * more powerful composition capabilities.
  *
  * Aspects are created using `Aspect.init` and are typically stored as vals at module level. Once initialized, an aspect can be used to
  * wrap computations that need to be modified, and its behavior can be customized using the `let` method to provide specific
  * implementations within a given scope. This pattern allows for clean separation between the definition of interceptable operations and
  * their actual implementations.
  *
  * Aspects support multi-shot continuations, meaning that cut implementations can invoke the continuation function multiple times or not at
  * all. This enables powerful control flow modifications like retry logic, fallback behavior, or conditional execution.
  *
  * Internally, aspects function as a form of reified ArrowEffect that can be stored, passed around, and modified at runtime. They maintain
  * state through a `Local` map of active implementations, allowing them to be dynamically activated and deactivated through operations like
  * `let` and `sandbox`.
  *
  * @tparam Input
  *   The input type constructor - what values the aspect can process
  * @tparam Output
  *   The output type constructor - what results the aspect can produce
  * @tparam S
  *   The effect type - what effects the aspect can perform
  */
final class Aspect[Input[_], Output[_], S] private[kyo] (
    default: Cut[Input, Output, S]
)(using Frame) extends Cut[Input, Output, S]:

    /** Applies this aspect to transform a computation.
      *
      * When called, this method checks if there's a currently active cut for this aspect. If found, it applies that cut, otherwise falls
      * back to the default behavior.
      *
      * @param input
      *   The input value to transform
      * @param cont
      *   The continuation function to apply after the transformation
      * @tparam C
      *   The type parameter for the Input/Output type constructors
      */
    def apply[C](input: Input[C])(cont: Input[C] => Output[C] < S) =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[Input, Output, S] @unchecked) =>
                    local.let(map - this) {
                        a(input)(cont)
                    }
                case _ =>
                    default(input)(cont)
        }

    /** Executes a computation in a sandboxed environment where this aspect's modifications are temporarily disabled.
      *
      * @param v
      *   The computation to sandbox
      * @tparam S
      *   Additional effects
      */
    def sandbox[A, S](v: A < S)(using Frame): A < S =
        local.use { map =>
            map.get(this) match
                case Some(a: Cut[Input, Output, S] @unchecked) =>
                    local.let(map - this) {
                        v
                    }
                case _ =>
                    v
        }

    /** Temporarily modifies this aspect's behavior for a given computation.
      *
      * @param binding
      *   The new behavior to apply
      * @param v
      *   The computation to run with the modified behavior
      * @tparam A
      *   The computation result type
      * @tparam S2
      *   Additional effects
      */
    def let[A, S2](binding: Cut.Binding[Input, Output, S])(v: A < S2)(using Frame): A < (S & S2) =
        letCut(Cut(binding))(v)

    /** Temporarily modifies this aspect's behavior for a given computation.
      *
      * @param a
      *   The new behavior to apply
      * @param v
      *   The computation to run with the modified behavior
      * @tparam A
      *   The computation result type
      * @tparam S2
      *   Additional effects
      */
    def letCut[A, S2](a: Cut[Input, Output, S])(v: A < S2)(using Frame): A < (S & S2) =
        local.use { map =>
            val cut =
                map.get(this) match
                    case Some(b: Cut[Input, Output, S] @unchecked) =>
                        b.andThen(a)
                    case _ =>
                        a
            local.let(map + (this -> cut))(v)
        }
end Aspect

object Aspect:

    private[kyo] val local = Local.init(Map.empty[Aspect[?, ?, ?], Cut[?, ?, ?]])

    /** Represents a cut in the aspect-oriented programming model.
      *
      * A cut defines how an aspect modifies or intercepts computations. It provides a way to inject behavior before, after, or around the
      * intercepted operations.
      *
      * @tparam Input
      *   The input type constructor
      * @tparam Output
      *   The output type constructor
      * @tparam S
      *   The effect type
      */
    abstract class Cut[Input[_], Output[_], S]:
        /** Applies this cut to transform a computation.
          *
          * @param input
          *   The input value to transform
          * @param cont
          *   The continuation function to apply after transformation
          * @tparam C
          *   The type parameter for Input/Output
          */
        def apply[C](input: Input[C])(cont: Input[C] => Output[C] < S): Output[C] < S

        /** Chains this cut with another, creating a new cut that applies both transformations in sequence.
          *
          * @param other
          *   The cut to apply after this one
          */
        def andThen(other: Cut[Input, Output, S])(using Frame): Cut[Input, Output, S] =
            new Cut[Input, Output, S]:
                def apply[A](input: Input[A])(cont: Input[A] => Output[A] < S): Output[A] < S =
                    Cut.this(input)(other(_)(cont))
    end Cut

    object Cut:

        /** Represents a binding function that implements a cut.
          *
          * @tparam I
          *   The input type constructor
          * @tparam O
          *   The output type constructor
          * @tparam S
          *   The effect type
          */
        type Binding[I[_], O[_], S] = [C] => (I[C], I[C] => O[C] < S) => O[C] < S

        /** Creates a cut from a binding function.
          *
          * @param binding
          *   The binding function to implement the cut
          */
        def apply[I[_], O[_], S](binding: Binding[I, O, S]): Cut[I, O, S] =
            new Cut[I, O, S]:
                def apply[A](input: I[A])(cont: I[A] => O[A] < S): O[A] < S =
                    binding(input, cont)
    end Cut

    /** Initializes a new aspect with default pass-through behavior.
      *
      * @tparam Input
      *   The input type constructor
      * @tparam Output
      *   The output type constructor
      * @tparam S
      *   The effect type
      */
    def init[I[_], O[_], S](using Frame): Aspect[I, O, S] =
        initCut(new Cut[I, O, S]:
            def apply[A](input: I[A])(cont: I[A] => O[A] < S): O[A] < S =
                cont(input)
        )

    /** Initializes a new aspect with a custom default behavior.
      *
      * @param default
      *   The default cut to use when no other cut is active
      */
    def initCut[I[_], O[_], S](default: Cut[I, O, S])(using Frame): Aspect[I, O, S] =
        new Aspect[I, O, S](default)

    /** Initializes a new aspect with a custom default behavior.
      *
      * @param binding
      *   The binding function to implement the default cut
      */
    def init[I[_], O[_], S](binding: Cut.Binding[I, O, S])(using Frame): Aspect[I, O, S] =
        initCut(Cut(binding))

    /** Chains multiple cuts together into a single composite cut.
      *
      * @param head
      *   The first cut to apply
      * @param tail
      *   Additional cuts to apply in sequence
      */
    def chain[I[_], O[_], S](head: Cut.Binding[I, O, S], tail: Cut.Binding[I, O, S]*)(using Frame) =
        chainCut(Cut(head), tail.map(Cut(_))*)

    /** Chains multiple cuts together into a single composite cut.
      *
      * @param head
      *   The first cut to apply
      * @param tail
      *   Additional cuts to apply in sequence
      */
    def chainCut[I[_], O[_], S](head: Cut[I, O, S], tail: Cut[I, O, S]*)(using Frame) =
        tail.foldLeft(head)(_.andThen(_))

end Aspect
