package kyo

import kyo.core.*

class Seqs extends Effect[Seqs]:
    type Command[T] = Seq[T]

object Seqs extends Seqs:

    def run[T, S](v: T < (Seqs & S))(using f: Flat[T < (Seqs & S)]): Seq[T] < S =
        handle(handler, v)

    def repeat(n: Int): Unit < Seqs =
        get(Seq.fill(n)(()))

    def get[T, S](v: Seq[T] < S): T < (Seqs & S) =
        v.map {
            case Seq(head) => head
            case v         => suspend(this)(v)
        }

    def filter[S](v: Boolean < S): Unit < (Seqs & S) =
        v.map {
            case true =>
                ()
            case false =>
                drop
        }

    val drop: Nothing < Seqs =
        suspend(this)(Seq.empty)

    def traverse[T, U, S, S2](v: Seq[T] < S)(f: T => U < S2): Seq[U] < (S & S2) =
        v.map { v =>
            collect(v.map(f))
        }

    def traverseUnit[T, U, S](v: Seq[T])(f: T => Unit < S): Unit < S =
        def loop(l: Seq[T]): Unit < S =
            if l.isEmpty then ()
            else f(l.head).andThen(loop(l.tail))
        loop(v)
    end traverseUnit

    def collect[T, S](v: Seq[T < S]): Seq[T] < S =
        val b = Seq.newBuilder[T]
        def loop(v: Seq[T < S]): Seq[T] < S =
            v match
                case Seq() =>
                    b.result()
                case h +: t =>
                    h.map { t1 =>
                        b += t1
                        loop(t)
                    }
        loop(v)
    end collect

    def collectUnit[T, S](v: Seq[Unit < S]): Unit < S =
        def loop(l: Seq[Unit < S]): Unit < S =
            if l.isEmpty then ()
            else l.head.andThen(loop(l.tail))
        loop(v)
    end collectUnit

    def fill[T, S](n: Int)(v: => T < S): Seq[T] < S =
        def loop(n: Int, acc: Seq[T]): Seq[T] < S =
            n match
                case 0 => acc.reverse
                case n => v.map(v => loop(n - 1, v +: acc))
        loop(n, Seq())
    end fill

    private val handler: ResultHandler[Seq, Seq, Seqs, Any] =
        new ResultHandler[Seq, Seq, Seqs, Any]:
            def pure[T](v: T) = Seq(v)
            def resume[T, U: Flat, S](v: Seq[T], f: T => U < (Seqs & S)) =
                Seqs.collect(v.map(e => Seqs.run(f(e)))).map(_.flatten)
end Seqs
