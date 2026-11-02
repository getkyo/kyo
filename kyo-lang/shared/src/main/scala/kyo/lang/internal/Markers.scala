package kyo.lang.internal

import kyo.<
import kyo.internal.Boundary
import kyo.internal.NowMarker
import scala.annotation.targetName
import scala.language.implicitConversions

/** The identity overload of the marker, selected by typing for an expression of an ordinary type. */
trait PureMarker:
    transparent inline def force[A](inline value: A): A = value

/** The expression marker the `kyo-lang` plugin wraps around every expression of a `.kyo` body, and the conversion that turns a value
  * into a computation where one is expected.
  *
  * A marker is an overloaded `force`. Normal Scala typing selects the overload: an expression of an ordinary type `A` resolves to the
  * identity overload on [[PureMarker]], while an expression of a pending type `A < S` resolves to the overload declared here, which
  * expands to [[sequence]], the application kyo-direct's lowering reads as `.now` through [[kyo.internal.NowMarker]]. The plugin cannot
  * know an expression's type when it plants the marker; the overloads are how a marker lands only where a computation is sequenced.
  * Every marker is inside a body [[Body]] lowers or rejects, so `sequence` is never expanded in compiled code; its expansion is a
  * sentinel that fails if that invariant were broken, and it is inert when the `-Xcheck-macros` re-typing of a macro result expands it.
  *
  * The pending overload takes the whole pending type as its type parameter `P` and declares its result as [[Exposed]] of it, the match
  * type that reduces to the exposed value type once `P` is known. Scala constrains the declared result of a polymorphic call from the
  * expected type before it types the argument; a result declared as the value type `A` would then require `A` to be a subtype of an
  * expected pending type, and the computation's exposed value cannot be. `Exposed[P]` does not reduce while `P` is unknown, so the
  * expected type constrains nothing, and it is the exposed type everywhere the declared type is read once `P` is the argument's type.
  *
  * [[boundary]] is the other half of the typing: it is an implicit conversion the plugin imports into every `.kyo` unit, so it is in
  * lexical scope and found before Kyo's own lift. The typer applies it exactly where an expression of an ordinary type meets an expected
  * pending type: a handler's argument, the body of a lambda given to an effect-aware API, a branch of a conditional in such a position,
  * a binding with an annotated pending type. The conversion is a transparent macro ([[Body.boundary]]) that lowers the expression's
  * markers into a computation with the precise row; a pure expression is lifted. This is how a computation boundary acquires its type
  * before the receiving call finalizes its type arguments: the typer requests it, and the typer sees the result.
  */
object Markers extends PureMarker:

    /** The exposed value type of a pending type. */
    type Exposed[P] = P match
        case a < s => a

    @targetName("forcePending")
    transparent inline def force[P <: Any < Nothing](inline value: P): Exposed[P] = ${ Body.sequenced[P]('value) }

    /** The `.now` of a `.kyo` body: the application kyo-direct's lowering sequences. */
    @NowMarker
    inline def sequence[A, S](inline value: A < S): A =
        throw new IllegalStateException("A kyo-lang marker was not lowered; this is a bug in kyo-lang")

    implicit transparent inline def boundary[A, S](inline value: A): A < S = ${ Body.boundary[A, S]('value) }

    /** The wrapper of every computation a boundary produced, which kyo-direct's validation reads as a computation whose markers were
      * its own; it is the identity once inlined.
      */
    @Boundary
    inline def lowered[A](inline value: A): A = value
end Markers
