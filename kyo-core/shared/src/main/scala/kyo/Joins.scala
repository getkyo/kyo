package kyo

trait Joins[E]:

    def race[T: Flat](l: Seq[T < E]): T < E

    def parallel[T: Flat](l: Seq[T < E]): Seq[T] < E

    def parallelTraverse[T, U: Flat](v: Seq[T] < E)(f: T => U < E): Seq[U] < E =
        v.map(_.map(f)).map(parallel[U](_))

    def race[T: Flat](
        v1: => T < E,
        v2: => T < E
    ): T < E =
        race(List(v1, v2))

    def race[T: Flat](
        v1: => T < E,
        v2: => T < E,
        v3: => T < E
    ): T < E =
        race(List(v1, v2, v3))

    def race[T: Flat](
        v1: => T < E,
        v2: => T < E,
        v3: => T < E,
        v4: => T < E
    ): T < E =
        race(List(v1, v2, v3, v4))

    def parallel[T1: Flat, T2: Flat](
        v1: => T1 < E,
        v2: => T2 < E
    ): (T1, T2) < E =
        parallel(List(v1, v2))(using Flat.unsafe.bypass).map(s =>
            (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2])
        )

    def parallel[T1: Flat, T2: Flat, T3: Flat](
        v1: => T1 < E,
        v2: => T2 < E,
        v3: => T3 < E
    ): (T1, T2, T3) < E =
        parallel(List(v1, v2, v3))(using Flat.unsafe.bypass).map(s =>
            (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
        )

    def parallel[T1: Flat, T2: Flat, T3: Flat, T4: Flat](
        v1: => T1 < E,
        v2: => T2 < E,
        v3: => T3 < E,
        v4: => T4 < E
    ): (T1, T2, T3, T4) < E =
        parallel(List(v1, v2, v3, v4))(using Flat.unsafe.bypass).map(s =>
            (
                s(0).asInstanceOf[T1],
                s(1).asInstanceOf[T2],
                s(2).asInstanceOf[T3],
                s(3).asInstanceOf[T4]
            )
        )
end Joins
