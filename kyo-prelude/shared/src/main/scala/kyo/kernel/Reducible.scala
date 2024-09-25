package kyo.kernel

sealed trait Reducible[S]:
    type SReduced
    private[kyo] def apply[A, S1](value: A < (S1 & S)): A < (S1 & SReduced)

sealed trait LowPriorityReducibles:
    inline given irreducible[S]: Reducible.Aux[S, S] =
        Reducible.cached.asInstanceOf[Reducible.Aux[S, S]]

object Reducible extends LowPriorityReducibles:
    trait Eliminable[S]

    private[kernel] val cached =
        new Reducible[Any]:
            type SReduced = Any
            def apply[A, S1](value: A < (S1 & Any)) = value.asInstanceOf[A < (S1 & SReduced)]

    type Aux[S, R] = Reducible[S] { type SReduced = R }

    inline given eliminate[S](using Eliminable[S]): Aux[S, Any] =
        cached.asInstanceOf[Aux[S, Any]]

end Reducible
