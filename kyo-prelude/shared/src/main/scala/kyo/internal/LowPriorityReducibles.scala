package kyo.internal

private[kyo] trait LowPriorityReducibles:
    /** Default instance for effects that cannot be reduced. */
    inline given irreducible[S]: Reducible.Aux[S, S] =
        Reducible.cached.asInstanceOf[Reducible.Aux[S, S]]
end LowPriorityReducibles
