package kyo.internal

import scala.NamedTuple.NamedTuple

/** Type class for composing named tuple types. Replaces the `Inputs` match type which cannot work with named tuples due to the opaque
  * boundary preventing disjointness proofs in match types.
  *
  * Implicit resolution decomposes `NamedTuple[N, V]` type constructor arguments via unification, which match types cannot do.
  *
  * `EmptyTuple` is the sentinel for "no captures" (replaces `Unit`).
  */
sealed trait Combine[A, B]:
    type Out
end Combine

object Combine:

    given emptyEmpty: Combine[EmptyTuple, EmptyTuple] with
        type Out = EmptyTuple

    given emptyLeft[N <: Tuple, V <: Tuple]: Combine[EmptyTuple, NamedTuple[N, V]] with
        type Out = NamedTuple[N, V]

    given emptyRight[N <: Tuple, V <: Tuple]: Combine[NamedTuple[N, V], EmptyTuple] with
        type Out = NamedTuple[N, V]

    given concat[N1 <: Tuple, V1 <: Tuple, N2 <: Tuple, V2 <: Tuple]
        : Combine[NamedTuple[N1, V1], NamedTuple[N2, V2]] with
        type Out = NamedTuple[Tuple.Concat[N1, N2], Tuple.Concat[V1, V2]]

end Combine
