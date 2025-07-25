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
  * state through a `Local` map of active implementations keyed by their (tag, frame) identity, allowing them to be dynamically activated
  * and deactivated through operations like `let` and `sandbox`.
  *
  * The identity of an aspect is determined by its type signature (captured via Tag) and its allocation site (captured via Frame). This
  * design enables generic aspects where different type instantiations maintain separate cuts. For example, when defining a generic aspect
  * like `def myAspect[A: Tag] = Aspect.init[Const[A], Const[A], Any]`, each call with different type parameters creates a distinct aspect
  * with its own state, preventing interference between `myAspect[Int]` and `myAspect[String]`.
  *
  * @tparam Input
  *   The input type constructor - what values the aspect can process
  * @tparam Output
  *   The output type constructor - what results the aspect can produce
  * @tparam S
  *   The effect type - what effects the aspect can perform
  */
final class Aspect[Input[_], Output[_], S](
    using
    tag: Tag[(Input[Any], Output[Any])],
    frame: Frame
) extends Serializable:

    private val key = (tag.erased, frame)

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
            map.get(key) match
                case Some(cut) =>
                    local.let(map - key) {
                        cut.asInstanceOf[Cut[Input, Output, S]](input, cont)
                    }
                case _ =>
                    cont(input)
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
            map.get(key) match
                case Some(_) =>
                    local.let(map - key) {
                        v
                    }
                case _ =>
                    v
        }

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
    def let[A, S2](a: Cut[Input, Output, S])(v: A < S2)(using Frame): A < (S & S2) =
        local.use { map =>
            val cut =
                map.get(key) match
                    case Some(b: Cut[Input, Output, S] @unchecked) =>
                        Cut.andThen[Input, Output, S](b, a)
                    case _ =>
                        a
            local.let(map + (key -> cut.asInstanceOf[Cut[Const[Any], Const[Any], Any]]))(v)
        }

    /** Converts this aspect into a Cut.
      *
      * This method allows using an aspect directly as a cut, which can be useful when composing aspects or when a Cut type is explicitly
      * required. The resulting cut will have the same behavior as calling the aspect directly.
      *
      * @return
      *   A Cut that delegates to this aspect's implementation
      */
    def asCut: Cut[Input, Output, S] =
        [C] => (input, cont) => this(input)(cont)

end Aspect

object Aspect:

    private[kyo] val local = Local.init(Map.empty[(Tag[Any], Frame), Cut[Const[Any], Const[Any], Any]])

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
    type Cut[I[_], O[_], S] = [C] => (I[C], I[C] => O[C] < S) => O[C] < S

    object Cut:

        /** Creates a cut from a function.
          *
          * @param cut
          *   The function to implement the cut
          */
        def apply[I[_], O[_], S](cut: Cut[I, O, S]): Cut[I, O, S] = cut

        /** Chains multiple cuts together into a single composite cut.
          *
          * @param head
          *   The first cut to apply
          * @param tail
          *   Additional cuts to apply in sequence
          */
        def andThen[I[_], O[_], S](a: Cut[I, O, S], b: Cut[I, O, S])(using Frame): Cut[I, O, S] =
            Cut[I, O, S](
                [C] => (input, cont) => a(input, b(_, cont))
            )
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
    inline def init[I[_], O[_], S](using Frame): Aspect[I, O, S] =
        new Aspect[I, O, S](using Tag.dynamic[(I[Any], O[Any])])

end Aspect
