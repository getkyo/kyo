package kyo.lang.internal

import kyo.<
import kyo.now
import scala.annotation.targetName

/** The identity overload of the marker, selected by typing for a pure expression. */
trait PureMarker:
    transparent inline def force[A](inline value: A): A = value

/** The expression marker the `kyo-lang` plugin wraps around every expression of a `.kyo` body.
  *
  * A marker is an overloaded `force`. Normal Scala typing selects the overload: an expression of an ordinary type `A` resolves to the
  * identity overload on [[PureMarker]], while an expression of a pending type `A < S` resolves to the overload declared here, which is
  * kyo-direct's `.now`. Both are transparent, so once the body is typed the pure marker has vanished and the pending marker is a `.now`
  * at the expression's position: the body types as if each operation returned its result, and it is the body of a `direct` block as
  * far as kyo-direct's lowering is concerned. The plugin cannot know an expression's type when it plants the marker; the overloads are
  * how `.now` lands only where a computation is sequenced.
  */
object Markers extends PureMarker:

    @targetName("forcePending")
    transparent inline def force[A, S](inline value: A < S): A = value.now
end Markers
