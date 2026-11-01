package kyo.lang.internal

import kyo.<
import scala.annotation.targetName
import scala.quoted.*

/** The identity overload of the marker, selected by typing for a pure expression. */
trait PureMarker:
    inline def force[A](inline value: A): A = value

/** The expression markers the `kyo-lang` plugin wraps around every expression of a `.kyo` body.
  *
  * A marker is an overloaded `force`. Normal Scala typing selects the overload: an expression of an ordinary type `A` resolves to the
  * identity overload on [[PureMarker]], while an expression of a pending type `A < S` resolves to the overload declared here, whose
  * result type is the exposed `A` and whose type arguments record the effect row `S`. The direct body therefore types as if each
  * operation returned its result, and the pending markers in the typed tree tell [[Body]] where to bind a computation and which effects
  * the enclosing definition requires.
  *
  * [[Body]] removes every pending marker while lowering. The pending overload is a macro that reports an error, so a marker that
  * survives lowering (a pending expression in a position no rule covers) is a compile error at the marker rather than a silently
  * discarded or re-executed effect.
  */
object Markers extends PureMarker:

    @targetName("forcePending")
    inline def force[A, S](inline value: A < S): A = ${ missing[A] }

    private def missing[A: Type](using Quotes): Expr[A] =
        quotes.reflect.report.errorAndAbort("Effect marker survived .kyo lowering")
end Markers
