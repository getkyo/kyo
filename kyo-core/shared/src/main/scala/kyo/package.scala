import scala.util.NotGiven

package object kyo:

    export core.<

    extension [T, S](v: T < S)(using NotGiven[Any => S])

        inline def flatMap[U, S2](inline f: T => U < S2): U < (S & S2) =
            if isNull(v) then
                throw new NullPointerException
            kyo.core.transform(v)(f)
        end flatMap

        inline def map[U, S2](inline f: T => U < S2): U < (S & S2) =
            flatMap(f)

        def unit: Unit < S =
            map(_ => ())

        def withFilter(p: T => Boolean): T < S =
            map(v => if !p(v) then throw new MatchError(v) else v)

        def flatten[U, S2](using ev: T => U < S2): U < (S & S2) =
            flatMap(ev)

        inline def andThen[U, S2](inline f: => U < S2)(using ev: T => Unit): U < (S & S2) =
            flatMap(_ => f)

        def repeat(i: Int)(using ev: T => Unit): Unit < S =
            if i <= 0 then () else andThen(repeat(i - 1))

        private[kyo] def isPure: Boolean =
            !v.isInstanceOf[core.internal.Kyo[?, ?]]

    end extension

    extension [T: Flat](v: T < Any)
        def pure: T =
            v match
                case kyo: kyo.core.internal.Suspend[?, ?, ?, ?] =>
                    bug.failTag(kyo.tag)
                case v =>
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

    inline def discard[T](v: T): Unit =
        val _ = v
        ()

    private[kyo] inline def isNull[T](v: T): Boolean =
        v.asInstanceOf[AnyRef] eq null

    private[kyo] object bug:

        case class KyoBugException(msg: String) extends Exception(msg)

        inline def failTag[T, U](
            inline actual: Tag[U]
        ): Nothing =
            bug(s"Unexpected effect '${actual.show}' found in 'pure'.")

        inline def failTag(
            inline actual: Tag[?],
            inline expected: Tag[?]*
        ): Nothing =
            bug(s"Unexpected effect '${actual.show}' found while handling '${expected.map(_.show).mkString(" & ")}'.")

        inline def checkTag[T, U](
            inline actual: Tag[U],
            inline expected: Tag[T]
        ): Unit =
            if actual =!= expected then
                failTag(actual, expected)

        def when(cond: Boolean)(msg: String): Unit =
            if cond then bug(msg)

        def apply(msg: String): Nothing =
            throw KyoBugException(msg + " Please file a ticket.")
    end bug
end kyo
