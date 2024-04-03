package kyo

import kyo.*
import kyo.core.*
import kyo.core.internal.*
import scala.annotation.implicitNotFound

opaque type Stream[+T, V, -S] = T < (Streams[V] & S)

object Stream:

    import internal.*

    class Done
    object Done extends Done

    extension [T: Flat, V: Flat, S](s: Stream[T, V, S])(using tag: Tag[Streams[V]])

        def get: T < (Streams[V] & S) =
            s

        def take(n: Int): Stream[T, V, S] =
            Vars[TakeN].run(n) {
                reemit { v =>
                    Vars[TakeN].use {
                        case 0 =>
                            ()
                        case n =>
                            Vars[TakeN].set(n - 1)
                                .andThen(Streams.emitValue(v))
                    }
                }
            }

        def drop(n: Int): Stream[T, V, S] =
            Vars[DropN].run(n) {
                reemit { v =>
                    Vars[DropN].use {
                        case 0 =>
                            Streams.emitValue(v)
                        case n =>
                            Vars[DropN].set(n - 1)
                    }
                }
            }

        def takeWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            Vars[Taking].run(true) {
                reemit { v =>
                    Vars[Taking].use {
                        case false =>
                        case true =>
                            f(v).map {
                                case true  => Streams.emitValue(v)
                                case false => Vars[Taking].set(false)
                            }
                    }
                }
            }

        def dropWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            Vars[Dropping].run(true) {
                reemit { v =>
                    Vars[Dropping].use {
                        case false => Streams.emitValue(v)
                        case true =>
                            f(v).map {
                                case false =>
                                    Vars[Dropping].set(false)
                                        .andThen(Streams.emitValue(v))
                                case true => ()
                            }
                    }
                }
            }

        def filter[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            reemit { v =>
                f(v).map {
                    case false => ()
                    case true  => Streams.emitValue(v)
                }
            }

        def changes: Stream[T, V, S] =
            Vars[Changes[V]].run(()) {
                reemit { v =>
                    Vars[Changes[V]].use {
                        case `v` =>
                        case _ =>
                            Vars[Changes[V]].set(v).andThen(Streams.emitValue(v))
                    }
                }
            }

        def collect[S2, V2: Flat](
            f: PartialFunction[V, Unit < (Streams[V2] & S2)]
        ): Stream[T, V2, S & S2] =
            reemit { v =>
                if f.isDefinedAt(v) then f(v)
                else ()
            }

        def transform[V2: Flat, S2](f: V => V2 < S2)(
            using tag2: Tag[Streams[V2]]
        ): Stream[T, V2, S & S2] =
            reemit { v =>
                f(v).map(Streams.emitValue(_)(using tag2))
            }

        def concat[T2: Flat, S2](
            s2: Stream[T2, V, S2]
        ): Stream[(T, T2), V, S & S2] =
            s.map(t => s2.map((t, _)))

        def accumulate[S2, V2: Flat](init: V2)(f: (V2, V) => V2 < S2)(
            using tag2: Tag[Streams[V2]]
        ): Stream[T, V2, S & S2] =
            Streams.emitValue(init)(using tag2).andThen {
                Vars[Accumulate[V2]].run(init) {
                    reemit { v =>
                        Vars[Accumulate[V2]].use { acc =>
                            f(acc, v).map { newAcc =>
                                Vars[Accumulate[V2]].set(newAcc)
                                    .andThen(Streams.emitValue(newAcc)(using tag2))
                            }
                        }
                    }
                }
            }

        inline def reemit[S2, V2: Flat](
            inline f: V => Unit < (Streams[V2] & S2)
        ): Stream[T, V2, S & S2] =
            def loop[T2: Flat, S](v: T2 < (Streams[V] & S)): T2 < (Streams[V2] & S & S2) =
                val handler = new Handler[Const[V], Streams[V], Streams[V2] & S2]:
                    def resume[T3, U: Flat, S3](command: V, k: T3 => U < (Streams[V] & S3)) =
                        f(command).andThen(loop(k(().asInstanceOf[T3])))
                handle(handler, v)(using tag, Flat.derive)
            end loop
            loop[T, S](s)
        end reemit

        def buffer(size: Int)(using
            @implicitNotFound(
                "Can't buffer a stream with pending effects other than 'Fibers' and 'IOs'. Found: ${S}"
            ) ev: S => Fibers | IOs
        ): Stream[T, V, Fibers & S] =
            Channels.init[V | Stream.Done](size).map { ch =>
                val s2 = s.asInstanceOf[Stream[T, V, Fibers & S]]
                Fibers.init(s2.runChannel(ch)).map { f =>
                    Streams.emitChannel[V](ch).andThen(f.get)
                }
            }

        def throttle(meter: Meter): Stream[T, V, S & Fibers] =
            reemit { v =>
                meter.run(Streams.emitValue(v))
            }

        def runFold[A](acc: A)(f: (A, V) => A): (A, T) < S =
            def handler(acc: A): ResultHandler[Const[V], [T] =>> (A, T), Streams[V], S] =
                new ResultHandler[Const[V], [T] =>> (A, T), Streams[V], S]:
                    def done[T](v: T) = (acc, v)
                    def resume[T, U: Flat, S3](command: V, k: T => U < (Streams[V] & S3)) =
                        handle(handler(f(acc, command)), k(().asInstanceOf[T]))
            val a = handle(handler(acc), s)
            a
        end runFold

        def runDiscard: T < S =
            handle(discardHandler[V], s)

        def runSeq: (Seq[V], T) < S =
            def handler(acc: List[V])
                : ResultHandler[Const[V], [T] =>> (Seq[V], T), Streams[V], Any] =
                new ResultHandler[Const[V], [T] =>> (Seq[V], T), Streams[V], Any]:
                    def done[T](v: T) = (acc.reverse, v)
                    def resume[T, U: Flat, S](command: V, k: T => U < (Streams[V] & S)) =
                        handle(handler(command :: acc), k(().asInstanceOf[T]))
            val a = handle(handler(Nil), s)
            a
        end runSeq

        def runChannel(ch: Channel[V | Done]): T < (Fibers & S) =
            val handler: Handler[Const[V], Streams[V], Fibers] =
                new Handler[Const[V], Streams[V], Fibers]:
                    override def done[T](v: T) = ch.put(Done).andThen(v)
                    def resume[T, U: Flat, S](command: V, k: T => U < (Streams[V] & S)) =
                        handle(ch.put(command).map(_ => k(().asInstanceOf[T])))
            handle(handler, s)
        end runChannel

    end extension

    private[kyo] inline def source[T, V, S](s: T < (Streams[V] & S)): Stream[T, V, S] =
        s

    private object internal:
        // used to isolate Vars usage via a separate tag
        opaque type TakeN >: Int <: Int                    = Int
        opaque type DropN >: Int <: Int                    = Int
        opaque type Taking >: Boolean <: Boolean           = Boolean
        opaque type Dropping >: Boolean <: Boolean         = Boolean
        opaque type Changes[V] >: (V | Unit) <: (V | Unit) = (V | Unit)
        opaque type Accumulate[V] >: V <: V                = V

        private val discard = new Handler[Const[Any], Streams[Any], Any]:
            def resume[T, U: Flat, S](command: Any, k: T => U < (Streams[Any] & S)) =
                handle(k(().asInstanceOf[T]))

        def discardHandler[V]: Handler[Const[V], Streams[V], Any] =
            discard.asInstanceOf[Handler[Const[V], Streams[V], Any]]
    end internal

end Stream

class Streams[V] extends Effect[Streams[V]]:
    type Command[T] = V

object Streams:

    import internal.*

    def initSource[V]: InitSourceDsl[V] = new InitSourceDsl[V]

    def initValue[V](v: V)(using Tag[Streams[V]]): Stream[Unit, V, Any] =
        initSource(emitValue(v))

    def initValue[V](v: V, tail: V*)(using Tag[Streams[V]]): Stream[Unit, V, Any] =
        initSeq(v +: tail)

    def initSeq[V](v: Seq[V])(using Tag[Streams[V]]): Stream[Unit, V, Any] =
        initSource(emitSeq(v))

    def initChannel[V](ch: Channel[V | Stream.Done])(
        using Tag[Streams[V]]
    ): Stream[Unit, V, Fibers] =
        initSource(emitChannel(ch))

    def emitValue[V](v: V)(using Tag[Streams[V]]): Unit < Streams[V] =
        suspend(Streams[V])(v)

    def emitValue[V](v: V, tail: V*)(using Tag[Streams[V]]): Unit < Streams[V] =
        emitSeq(v +: tail)

    def emitSeq[V](v: Seq[V])(using Tag[Streams[V]]): Unit < Streams[V] =
        if v.isEmpty then ()
        else emitValue(v.head).andThen(emitSeq(v.tail))

    def emitChannel[V](ch: Channel[V | Stream.Done])(using
        Tag[Streams[V]]
    ): Unit < (Streams[V] & Fibers) =
        ch.take.map {
            case Stream.Done =>
                ()
            case v: V @unchecked =>
                emitValue(v).andThen(emitChannel(ch))
        }

    object internal:
        class InitSourceDsl[V]:
            def apply[T, S](v: T < (Streams[V] & S)): Stream[T, V, S] =
                Stream.source(v)
    end internal
end Streams
