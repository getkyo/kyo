package object kyo:

    type NotGiven[T] = scala.util.NotGiven[T]

    type <[+T, -S] >: T // = T | Kyo[_, _, _, T, S]

    extension [T, S](v: T < S)(using NotGiven[Any => S])

        def flatMap[U, S2](f: T => U < S2): U < (S & S2) =
            kyo.core.transform(v)(f)

        def map[U, S2](f: T => U < S2): U < (S & S2) =
            flatMap(f)

        def unit: Unit < S =
            map(_ => ())

        def withFilter(p: T => Boolean): T < S =
            map(v => if !p(v) then throw new MatchError(v) else v)

        def flatten[U, S2](using ev: T => U < S2): U < (S & S2) =
            flatMap(ev)

        def andThen[U, S2](f: => U < S2)(using ev: T => Unit): U < (S & S2) =
            flatMap(_ => f)

        def repeat(i: Int)(using ev: T => Unit): Unit < S =
            if i <= 0 then () else andThen(repeat(i - 1))
    end extension

    extension [T, S](v: T < Any)
        def pure(using ev: Any => S): T =
            v.asInstanceOf[T]
    end extension

    def zip[T1, T2, S](v1: T1 < S, v2: T2 < S): (T1, T2) < S =
        v1.map(t1 => v2.map(t2 => (t1, t2)))

    def zip[T1, T2, T3, S](v1: T1 < S, v2: T2 < S, v3: T3 < S): (T1, T2, T3) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => (t1, t2, t3))))

    def zip[T1, T2, T3, T4, S](
        v1: T1 < S,
        v2: T2 < S,
        v3: T3 < S,
        v4: T4 < S
    ): (T1, T2, T3, T4) < S =
        v1.map(t1 => v2.map(t2 => v3.map(t3 => v4.map(t4 => (t1, t2, t3, t4)))))
end kyo
