package kyo

trait Joins[E]:

    def race[T](l: Seq[T < E])(using f: Flat[T < E]): T < E

    def parallel[T](l: Seq[T < E])(using f: Flat[T < E]): Seq[T] < E

    def parallelTraverse[T, U](v: Seq[T] < E)(f: T => U < E)(using flat: Flat[U < E]): Seq[U] < E =
        v.map(_.map(f)).map(parallel[U](_))

    def race[T](
        v1: => T < E,
        v2: => T < E
    )(using f: Flat[T < E]): T < E =
        race(List(v1, v2))

    def race[T](
        v1: => T < E,
        v2: => T < E,
        v3: => T < E
    )(using f: Flat[T < E]): T < E =
        race(List(v1, v2, v3))

    def race[T](
        v1: => T < E,
        v2: => T < E,
        v3: => T < E,
        v4: => T < E
    )(using f: Flat[T < E]): T < E =
        race(List(v1, v2, v3, v4))

    def parallel[T1, T2](
        v1: => T1 < E,
        v2: => T2 < E
    )(
        implicit
        f1: Flat[T1 < E],
        f2: Flat[T2 < E]
    ): (T1, T2) < E =
        parallel(List(v1, v2))(using Flat.unsafe.bypass).map(s =>
            (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2])
        )

    def parallel[T1, T2, T3](
        v1: => T1 < E,
        v2: => T2 < E,
        v3: => T3 < E
    )(
        implicit
        f1: Flat[T1 < E],
        f2: Flat[T2 < E],
        f3: Flat[T3 < E]
    ): (T1, T2, T3) < E =
        parallel(List(v1, v2, v3))(using Flat.unsafe.bypass).map(s =>
            (s(0).asInstanceOf[T1], s(1).asInstanceOf[T2], s(2).asInstanceOf[T3])
        )

    def parallel[T1, T2, T3, T4](
        v1: => T1 < E,
        v2: => T2 < E,
        v3: => T3 < E,
        v4: => T4 < E
    )(
        implicit
        f1: Flat[T1 < E],
        f2: Flat[T2 < E],
        f3: Flat[T3 < E],
        f4: Flat[T4 < E]
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
