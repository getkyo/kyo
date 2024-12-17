package kyo

/** Represents Kyo's mechanism for safely eliding effects in specific situations.
  *
  * Reducible provides compile-time evidence that certain effects can be safely eliminated or transformed into simpler forms. This is used
  * internally by Kyo to optimize effect handling and maintain type safety when effects no longer have an impact on the computation.
  *
  * @tparam S
  *   The type of effect that can potentially be reduced
  */
sealed trait Reducible[S]:
    /** The effect type that remains after reduction. */
    type SReduced

    private[kyo] def apply[A, S1](value: A < (S1 & S)): A < (S1 & SReduced)
end Reducible

sealed trait LowPriorityReducibles:
    /** Default instance for effects that cannot be reduced. */
    inline given irreducible[S]: Reducible.Aux[S, S] =
        Reducible.cached.asInstanceOf[Reducible.Aux[S, S]]
end LowPriorityReducibles

object Reducible extends LowPriorityReducibles:
    /** Marker trait indicating an effect can be safely eliminated. */
    trait Eliminable[S]

    private[kyo] val cached =
        new Reducible[Any]:
            type SReduced = Any
            def apply[A, S1](value: A < (S1 & Any)) = value.asInstanceOf[A < (S1 & SReduced)]

    /** Type alias for Reducible with explicit source and target effect types. */
    type Aux[S, R] = Reducible[S] { type SReduced = R }

    /** Creates a Reducible that eliminates the effect entirely. */
    inline given eliminate[S](using Eliminable[S]): Aux[S, Any] =
        cached.asInstanceOf[Aux[S, Any]]
end Reducible
