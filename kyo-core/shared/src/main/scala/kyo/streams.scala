package kyo

import kyo.*
import kyo.core.*
import kyo.core.internal.*

class Streams[V] extends Effect[Streams[V]]:
    type Command[T] = V

end Streams

object Streams:

    import internal.*

    class Done
    object Done extends Done

    private case object streams extends Streams[Any]
    def apply[V]: Streams[V] = streams.asInstanceOf[Streams[V]]

    extension [V](s: Streams[V])(using tag: Tag[Streams[V]], flat: Flat[V])

        def emit(v: V): Unit < Streams[V] =
            suspend(s)(v)

        def emit(v: V, tail: V*): Unit < Streams[V] =
            emit(v +: tail)

        def emit(v: Seq[V]): Unit < Streams[V] =
            if v.isEmpty then ()
            else emit(v.head).andThen(emit(v.tail))

        def emit(ch: Channel[V | Done]): Unit < (Streams[V] & Fibers) =
            ch.take.map {
                case Done =>
                    ()
                case v: V =>
                    emit(v).andThen(emit(ch))
            }

        def buffer[T: Flat](size: Int)(v: T < Streams[V]): T < (Streams[V] & Fibers) =
            Channels.init[V | Done](size).map { ch =>
                Fibers.init(runChannel(ch)(v)).map(f => emit(ch).andThen(f.get))
            }

        def take[T: Flat, S](n: Int)(v: T < (Streams[V] & S)): T < (Streams[V] & S) =
            Vars[TakeN].run(n) {
                reemit(v) { v =>
                    Vars[TakeN].get.map {
                        case 0 =>
                            ()
                        case n =>
                            Vars[TakeN].set(n - 1)
                                .andThen(emit(v))
                    }
                }
            }

        def drop[T: Flat, S](n: Int)(v: T < (Streams[V] & S)): T < (Streams[V] & S) =
            Vars[DropN].run(n) {
                reemit(v) { v =>
                    Vars[DropN].get.map {
                        case 0 =>
                            emit(v)
                        case n =>
                            Vars[DropN].set(n - 1)
                    }
                }
            }

        def filter[T: Flat, S](v: T < (Streams[V] & S))(
            f: V => Boolean < S
        ): T < (Streams[V] & S) =
            reemit(v) { v =>
                f(v).map {
                    case false => ()
                    case true  => emit(v)
                }
            }

        def transform[T: Flat, V2: Flat, S, S2](v: T < (Streams[V] & S))(
            f: V => V2 < (S & S2)
        )(using Tag[Streams[V2]]): T < (Streams[V2] & S & S2) =
            reemit(v) { v =>
                f(v).map(Streams[V2].emit)
            }

        def collect[T: Flat, S, S2, V2: Flat](v: T < (Streams[V] & S))(
            f: PartialFunction[V, Unit < (Streams[V2] & S2)]
        ): T < (Streams[V2] & S & S2) =
            reemit(v) { v =>
                if f.isDefinedAt(v) then f(v)
                else ()
            }
        end collect

        def runFold[T: Flat, S, A](v: T < (Streams[V] & S))(acc: A)(f: (A, V) => A)(
            using Flat[V]
        ): (A, T) < S =
            def handler(acc: A): ResultHandler[Const[V], [T] =>> (A, T), Streams[V], S] =
                new ResultHandler[Const[V], [T] =>> (A, T), Streams[V], S]:
                    def pure[T](v: T) = (acc, v)
                    def resume[T, U: Flat, S3](command: V, k: T => U < (Streams[V] & S3)) =
                        handle(handler(f(acc, command)), k(().asInstanceOf[T]))
            val a = handle(handler(acc), v)
            a
        end runFold

        def runSeq[T: Flat, S](v: T < (Streams[V] & S))(
            using Flat[V]
        ): (Seq[V], T) < S =
            def handler(acc: List[V])
                : ResultHandler[Const[V], [T] =>> (Seq[V], T), Streams[V], Any] =
                new ResultHandler[Const[V], [T] =>> (Seq[V], T), Streams[V], Any]:
                    def pure[T](v: T) = (acc.reverse, v)
                    def resume[T, U: Flat, S](command: V, k: T => U < (Streams[V] & S)) =
                        handle(handler(command :: acc), k(().asInstanceOf[T]))
            val a = handle(handler(Nil), v)
            a
        end runSeq

        def runChannel[T: Flat, S](ch: Channel[V | Done])(
            v: T < (Streams[V] & S)
        ): T < (S & Fibers) =
            val handler: Handler[Const[V], Streams[V], Fibers] =
                new Handler[Const[V], Streams[V], Fibers]:
                    override def pure[T](v: T) = ch.put(Done).andThen(v)
                    def resume[T, U: Flat, S](command: V, k: T => U < (Streams[V] & S)) =
                        handle(ch.put(command).map(_ => k(().asInstanceOf[T])))
            handle(handler, v)
        end runChannel

        private inline def reemit[T: Flat, S, S2, V2: Flat](v: T < (Streams[V] & S))(
            inline f: V => Unit < (Streams[V2] & S2)
        ): T < (Streams[V2] & S & S2) =
            def loop[T2: Flat, S](v: T2 < (Streams[V] & S)): T2 < (Streams[V2] & S & S2) =
                val handler = new Handler[Const[V], Streams[V], Streams[V2] & S2]:
                    def resume[T3, U: Flat, S3](command: V, k: T3 => U < (Streams[V] & S3)) =
                        f(command).andThen(loop(k(().asInstanceOf[T3])))
                handle(handler, v)(using tag, Flat.derive)
            end loop
            loop[T, S](v)
        end reemit
    end extension

    private object internal:
        // used to isolate Vars usage via a separate tag
        opaque type TakeN >: Int <: Int = Int
        opaque type DropN >: Int <: Int = Int
    end internal

end Streams
