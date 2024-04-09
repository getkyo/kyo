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
            val handler =
                new ResultHandler[Int, Const[V], Streams[V], Id, Streams[V]]:
                    def done[T](st: Int, v: T) = v
                    def resume[T, U: Flat, S2](
                        st: Int,
                        command: V,
                        k: T => U < (Streams[V] & S2)
                    ) =
                        if st == 0 then
                            Resume(0, k(().asInstanceOf[T]))
                        else
                            Streams.emitValue(command).andThen(
                                Resume(st - 1, k(().asInstanceOf[T]))
                            )
            Streams[V].handle(handler)(n, s)
        end take

        def drop(n: Int): Stream[T, V, S] =
            val handler =
                new ResultHandler[Int, Const[V], Streams[V], Id, Streams[V]]:
                    def done[T](st: Int, v: T) = v
                    def resume[T, U: Flat, S2](
                        st: Int,
                        command: V,
                        k: T => U < (Streams[V] & S2)
                    ) =
                        if st == 0 then
                            Streams.emitValue(command).andThen(
                                Resume(0, k(().asInstanceOf[T]))
                            )
                        else
                            Resume(st - 1, k(().asInstanceOf[T]))
            Streams[V].handle(handler)(n, s)
        end drop

        def takeWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            val handler =
                new ResultHandler[Boolean, Const[V], Streams[V], Id, Streams[V] & S & S2]:
                    def done[T](st: Boolean, v: T) = v
                    def resume[T, U: Flat, S3](
                        st: Boolean,
                        command: V,
                        k: T => U < (Streams[V] & S3)
                    ) =
                        if st then
                            f(command).map {
                                case true =>
                                    Streams.emitValue(command).andThen(
                                        Resume(true, k(().asInstanceOf[T]))
                                    )
                                case false =>
                                    Resume(false, k(().asInstanceOf[T]))
                            }
                        else
                            Resume(false, k(().asInstanceOf[T]))
            Streams[V].handle(handler)(true, s)
        end takeWhile

        def dropWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            val handler =
                new ResultHandler[Boolean, Const[V], Streams[V], Id, Streams[V] & S & S2]:
                    def done[T](st: Boolean, v: T) = v
                    def resume[T, U: Flat, S3](
                        st: Boolean,
                        command: V,
                        k: T => U < (Streams[V] & S3)
                    ) =
                        if st then
                            f(command).map {
                                case true =>
                                    Resume(true, k(().asInstanceOf[T]))
                                case false =>
                                    Streams.emitValue(command).andThen(
                                        Resume(false, k(().asInstanceOf[T]))
                                    )
                            }
                        else
                            Streams.emitValue(command).andThen(
                                Resume(false, k(().asInstanceOf[T]))
                            )
            Streams[V].handle(handler)(true, s)
        end dropWhile

        def filter[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            val handler = new Handler[Const[V], Streams[V], Streams[V] & S & S2]:
                def resume[T, U: Flat, S3](
                    command: V,
                    k: T => U < (Streams[V] & S3)
                ) =
                    f(command).map {
                        case true =>
                            Streams.emitValue(command).andThen(Resume(k(().asInstanceOf[T])))
                        case false =>
                            Resume(k(().asInstanceOf[T]))
                    }
            Streams[V].handle(handler)((), s)
        end filter

        def changes: Stream[T, V, S] =
            val handler =
                new ResultHandler[V | Null, Const[V], Streams[V], Id, Streams[V] & S]:
                    def done[T](st: V | Null, v: T) = v
                    def resume[T, U: Flat, S3](
                        st: V | Null,
                        command: V,
                        k: T => U < (Streams[V] & S3)
                    ) =
                        if !command.equals(st) then
                            Streams.emitValue(command).andThen(
                                Resume(command, k(().asInstanceOf[T]))
                            )
                        else
                            Resume(command, k(().asInstanceOf[T]))
                        end if
                    end resume
            Streams[V].handle(handler)(null, s)
        end changes

        def collect[S2, V2: Flat](
            f: PartialFunction[V, Unit < (Streams[V2] & S2)]
        ): Stream[T, V2, S & S2] =
            val handler =
                new Handler[Const[V], Streams[V], Streams[V2] & S & S2]:
                    def resume[T, U: Flat, S3](
                        command: V,
                        k: T => U < (Streams[V] & S3)
                    ) =
                        if f.isDefinedAt(command) then
                            f(command).andThen(
                                Resume(k(().asInstanceOf[T]))
                            )
                        else
                            Resume(k(().asInstanceOf[T]))
            Streams[V].handle(handler)((), s)
        end collect

        def transform[V2: Flat, S2](f: V => V2 < S2)(
            using tag2: Tag[Streams[V2]]
        ): Stream[T, V2, S & S2] =
            val handler =
                new Handler[Const[V], Streams[V], Streams[V2] & S & S2]:
                    def resume[T, U: Flat, S3](
                        command: V,
                        k: T => U < (Streams[V] & S3)
                    ) =
                        f(command).map(v =>
                            Streams.emitValue(v)(using tag2).andThen(
                                Resume(k(().asInstanceOf[T]))
                            )
                        )
            Streams[V].handle(handler)((), s)
        end transform

        def concat[T2: Flat, S2](
            s2: Stream[T2, V, S2]
        ): Stream[(T, T2), V, S & S2] =
            s.map(t => s2.map((t, _)))

        def accumulate[S2, V2: Flat](init: V2)(f: (V2, V) => V2 < S2)(
            using tag2: Tag[Streams[V2]]
        ): Stream[T, V2, S & S2] =
            val handler =
                new ResultHandler[V2, Const[V], Streams[V], Id, Streams[V2] & S & S2]:
                    def done[T](st: V2, v: T) = v
                    def resume[T, U: Flat, S3](
                        st: V2,
                        command: V,
                        k: T => U < (Streams[V] & S3)
                    ) =
                        f(st, command).map { newAcc =>
                            Streams.emitValue(newAcc)(using tag2).andThen(
                                Resume(newAcc, k(().asInstanceOf[T]))
                            )
                        }
            Streams.emitValue(init)(using tag2).andThen(
                Streams[V].handle(handler)(init, s)
            )
        end accumulate

        def reemit[S2, V2: Flat](
            f: V => Unit < (Streams[V2] & S2)
        ): Stream[T, V2, S & S2] =
            val handler = new Handler[Const[V], Streams[V], Streams[V2] & S2]:
                def resume[T3, U: Flat, S3](command: V, k: T3 => U < (Streams[V] & S3)) =
                    f(command).andThen(Resume(k(().asInstanceOf[T3])))
            Streams[V].handle(handler)((), s)(using tag, Flat.derive)
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

        def runFold[A, S2](acc: A)(f: (A, V) => A < S2): (A, T) < (S & S2) =
            val handler =
                new ResultHandler[A, Const[V], Streams[V], [T] =>> (A, T), S & S2]:
                    def done[T](st: A, v: T) = (st, v)
                    def resume[T, U: Flat, S2](
                        st: A,
                        command: V,
                        k: T => U < (Streams[V] & S2)
                    ) =
                        f(st, command).map(Resume(_, k(().asInstanceOf[T])))
            Streams[V].handle(handler)(acc, s)
        end runFold

        def runDiscard: T < S =
            Streams[V].handle(discardHandler[V])((), s)

        def runSeq: (Seq[V], T) < S =
            val handler =
                new ResultHandler[List[V], Const[V], Streams[V], [T] =>> (Seq[V], T), S]:
                    def done[T](st: List[V], v: T) = (st.reverse, v)
                    def resume[T, U: Flat, S2](
                        st: List[V],
                        command: V,
                        k: T => U < (Streams[V] & S2)
                    ) =
                        Resume(command :: st, k(().asInstanceOf[T]))
            Streams[V].handle(handler)(Nil, s)
        end runSeq

        def runChannel(ch: Channel[V | Done]): T < (Fibers & S) =
            val handler: Handler[Const[V], Streams[V], Fibers] =
                new Handler[Const[V], Streams[V], Fibers]:
                    override def done[T](v: T) = ch.put(Done).andThen(v)
                    def resume[T, U: Flat, S](command: V, k: T => U < (Streams[V] & S)) =
                        ch.put(command).map(_ => Resume(k(().asInstanceOf[T])))
            Streams[V].handle(handler)((), s)
        end runChannel

    end extension

    private[kyo] inline def source[T, V, S](s: T < (Streams[V] & S)): Stream[T, V, S] =
        s

    private object internal:
        class Handlers[V]:
            val discard = new Handler[Const[V], Streams[V], Any]:
                def resume[T, U: Flat, S2](command: Const[V][T], k: T => U < (Streams[V] & S2)) =
                    Resume(k(().asInstanceOf[T]))

        end Handlers

        private val discard = new Handler[Const[Any], Streams[Any], Any]:
            def resume[T, U: Flat, S](command: Any, k: T => U < (Streams[Any] & S)) =
                Resume(k(().asInstanceOf[T]))

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

    inline def emitValue[V](v: V)(using inline tag: Tag[Streams[V]]): Unit < Streams[V] =
        Streams[V].suspend[Unit](v)

    def emitValue[V](v: V, tail: V*)(using Tag[Streams[V]]): Unit < Streams[V] =
        emitSeq(v +: tail)

    def emitSeq[V](v: Seq[V])(using Tag[Streams[V]]): Unit < Streams[V] =
        if v.isEmpty then ()
        else emitValue(v.head).andThen(emitSeq(v.tail))

    def emitChannel[V](ch: Channel[V | Stream.Done])(using
        Tag[Streams[V]]
    ): Unit < (Streams[V] & Fibers) =
        ch.take.map {
            case e if e.equals(Stream.Done) =>
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
