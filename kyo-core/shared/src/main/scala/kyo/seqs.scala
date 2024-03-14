package kyo

import kyo.core.*

sealed abstract class Seqs private[kyo] () extends Effect[Seq, Seqs]:

    def run[T, S](v: T < (Seqs & S))(using f: Flat[T < (Seqs & S)]): Seq[T] < S =
        this.handle[T, S, Any](v)

    def repeat(n: Int): Unit < Seqs =
        get(Seq.fill(n)(()))

    def get[T, S](v: Seq[T] < S): T < (Seqs & S) =
        v.map {
            case Seq(head) => head
            case _         => this.suspend(v)
        }

    def filter[S](v: Boolean < S): Unit < (Seqs & S) =
        v.map {
            case true =>
                ()
            case false =>
                drop
        }

    val drop: Nothing < Seqs =
        this.suspend(Seq.empty[Nothing])

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

    def fill[T, S](n: Int)(v: => T < S): Seq[T] < S =
        def loop(n: Int, acc: Seq[T]): Seq[T] < S =
            n match
                case 0 => acc.reverse
                case n => v.map(v => loop(n - 1, v +: acc))
        loop(n, Seq())
    end fill

    private given handler: Handler[Seq, Seqs, Any] =
        new Handler[Seq, Seqs, Any]:
            def pure[T: Flat](v: T) = Seq(v)
            def apply[T, U: Flat, S](v: Seq[T], f: T => U < (Seqs & S)): U < (Seqs & S) =
                def loop(l: Seq[T], acc: Seq[Seq[U]]): U < (Seqs & S) =
                    l match
                        case Seq() =>
                            Seqs.get(acc.reverse.flatten: Seq[U])
                        case t +: ts =>
                            Seqs.run[U, S](f(t)).map(l => loop(ts, l +: acc))
                loop(v, Seq.empty)
            end apply
end Seqs
object Seqs extends Seqs
