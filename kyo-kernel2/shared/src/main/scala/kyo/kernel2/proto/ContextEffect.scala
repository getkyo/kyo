package kyo.kernel2.proto

import kyo.Maybe
import kyo.Tag

/** An effect whose operations read an ambient value. In this design it is the degenerate arrow case (the gist's derivation of Env): a
  * binding is a [[Context.Binding]] entry in the one threaded environment, a read resolves it in place at the read's own execution point,
  * and a binding region rotates across suspensions exactly like a handler region. No interceptors, no re-arming, no separate parameter.
  */
abstract class ContextEffect[V]

object ContextEffect:

    def suspend[V, E <: ContextEffect[V]](tag: Tag[E]): V < E =
        Read(tag, Maybe.Absent)

    /** A defaulted read: resolves the binding if one is in scope (locally or inherited through the evaluation boundary), the default
      * otherwise.
      */
    def suspend[V, E <: ContextEffect[V]](tag: Tag[E], default: => V): V < E =
        Read(tag, Maybe(() => default))

    def handle[V, E <: ContextEffect[V], A, S](effectTag: Tag[E], value: V)(v: A < (E & S)): A < S =
        Bound[V, E, A, S](v, effectTag, value)

end ContextEffect
