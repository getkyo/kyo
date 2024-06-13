package kyo

inline def discard[T](v: T): Unit =
    val _ = v
    ()
