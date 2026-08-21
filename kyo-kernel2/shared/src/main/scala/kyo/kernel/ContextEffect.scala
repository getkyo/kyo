package kyo.kernel

/** Represents the requirement for a value that a binding higher up provides.
  *
  * Where an ArrowEffect is an operation awaiting an answer, this is a value awaiting provision: a read asks
  * for the value bound at the point the computation runs, and a binding provides it for the extent of a
  * computation, dynamically rather than by position.
  *
  * The kernel's side of it is `Kyo.Binding`, which is both node kinds this needs, and the whole mechanism:
  * reading, binding, the extent a binding applies to, and what a captured continuation carries with it.
  *
  * @tparam A
  *   The type of value a binding provides
  */
abstract class ContextEffect[+A] extends Effect

object ContextEffect:

    /** A marker for context effects that do not cross an asynchronous boundary.
      *
      * A child computation starts without the values of effects marked this way, which is what makes them
      * behave like non-inheritable thread locals rather than inherited ones.
      */
    trait Noninheritable:
        self: ContextEffect[?] =>

    // The surface lands next. `Kyo.Binding` carries the semantics each of these needs: a read is a node with
    // no bound value, a binding is a node whose bound value is a function of what is bound outside it, and
    // the layered form is that function rather than a composition of two nodes.
    //
    // inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E])(using inline frame: Frame): A < E
    //
    // inline def suspendWith[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E])(
    //     inline f: A => B < S
    // )(using inline frame: Frame): B < (E & S)
    //
    // inline def suspend[A, E <: ContextEffect[A]](inline effectTag: Tag[E], inline default: => A)(
    //     using inline frame: Frame
    // ): A < Any
    //
    // inline def suspendWith[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E], inline default: => A)(
    //     inline f: A => B < S
    // )(using inline frame: Frame): B < S
    //
    // inline def handle[A, E <: ContextEffect[A], B, S](inline effectTag: Tag[E], inline value: A)(
    //     v: B < (E & S)
    // )(using inline frame: Frame): B < S
    //
    // inline def handle[A, E <: ContextEffect[A], B, S](
    //     inline effectTag: Tag[E],
    //     inline ifUndefined: A,
    //     inline ifDefined: A => A
    // )(v: B < (E & S))(using inline frame: Frame): B < S

end ContextEffect
