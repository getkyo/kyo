package kyo

import kyo.Stream

extension [V, S, S2](stream: Stream[V, S] < S2)
    /** Takes a Stream[V, S] in the context of S2 (i.e. Stream[V, S] < S2) and returns a Stream that fuses together both effect contexts S
      * and S2 into a single Stream[V, S & S2].
      */
    inline def unwrapStream(using Frame): Stream[V, S & S2] = Stream.unwrap(stream)
end extension
