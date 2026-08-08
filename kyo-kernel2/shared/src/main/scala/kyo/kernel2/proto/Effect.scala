package kyo.kernel2.proto

object Effect:

    /** Guards `v`: a throw anywhere inside the region, including after a suspension resumes (the guard rotates with the region), lands in
      * `rescue`. A throw OUTSIDE the region does not: scope is the node, not a chain position.
      */
    def catching[A, S](v: => A < S)(rescue: Throwable => A < S): A < S =
        Catching[A, S, A, Any](Defer(() => v), rescue, a => Pure(a))

end Effect
