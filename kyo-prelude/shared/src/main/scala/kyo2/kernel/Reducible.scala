package kyo2.kernel

sealed trait Reducible[S]:
    type SReduced
    def apply[A, S1](value: A < (S1 & S)): A < (S1 & SReduced)

sealed trait LowPriorityReducibles:
    given irreducible[S]: Reducible[S] with
        type SReduced = S
        override def apply[A, S1](value: A < (S1 & S)): A < (S1 & S) = value
end LowPriorityReducibles

object Reducible extends LowPriorityReducibles:
    trait Eliminable[S]

    given eliminate[S](using Eliminable[S]): Reducible[S] with
        type SReduced = Any
        def apply[A, S1](value: A < (S1 & S)): A < S1 = value.asInstanceOf[A < S1]
end Reducible
