package kyo

import kyo.*
import kyo.core.*
import kyo.core.internal.*
import scala.annotation.tailrec

opaque type Stream[+T, V, -S] = T < (Streams[V] & S)

object Stream:

    import internal.*

    class Done
    object Done extends Done

    extension [T: Flat, V: Flat, S](s: Stream[T, V, S])(using tag: Tag[Streams[V]])
        def get: T < (Streams[V] & S) =
            s

        def take(n: Int): Stream[T, V, S] =
            if n <= 0 then
                runDiscard
            else
                val handler =
                    new ResultHandler[Int, Const[Chunk[V]], Streams[V], Id, Streams[V]]:
                        def done[T](st: Int, v: T) = v
                        def resume[T, U: Flat, S2](
                            st: Int,
                            command: Chunk[V],
                            k: T => U < (Streams[V] & S2)
                        ) =
                            if st == 0 then
                                Resume(0, k(().asInstanceOf[T]))
                            else
                                val t: Chunk[V] = command.take(st)
                                Streams.emitChunkAndThen(t) {
                                    Resume(st - t.size, k(().asInstanceOf[T]))
                                }
                Streams[V].handle(handler)(n, s)
        end take

        def drop(n: Int): Stream[T, V, S] =
            if n <= 0 then
                s
            else
                val handler =
                    new ResultHandler[Int, Const[Chunk[V]], Streams[V], Id, Streams[V]]:
                        def done[T](st: Int, v: T) = v
                        def resume[T, U: Flat, S2](
                            st: Int,
                            command: Chunk[V],
                            k: T => U < (Streams[V] & S2)
                        ) =
                            if st == 0 then
                                Streams.emitChunkAndThen(command) {
                                    k(().asInstanceOf[T])
                                }
                            else
                                Streams.emitChunkAndThen(command.dropLeft(st)) {
                                    Resume(st - Math.min(command.size, st), k(().asInstanceOf[T]))
                                }
                Streams[V].handle(handler)(n, s)
        end drop

        def takeWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            val handler =
                new ResultHandler[Boolean, Const[Chunk[V]], Streams[V], Id, Streams[V] & S & S2]:
                    def done[T](st: Boolean, v: T) = v
                    def resume[T, U: Flat, S3](
                        st: Boolean,
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S3)
                    ) =
                        if st then
                            command.takeWhile(f).map { c =>
                                Streams.emitChunkAndThen(c) {
                                    Resume(command.size == c.size, k(().asInstanceOf[T]))
                                }
                            }
                        else
                            Resume(false, k(().asInstanceOf[T]))
            Streams[V].handle(handler)(true, s)
        end takeWhile

        def dropWhile[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            val handler =
                new Handler[Const[Chunk[V]], Streams[V], Streams[V] & S & S2]:
                    def resume[T, U: Flat, S3](
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S3)
                    ) =
                        command.dropWhile(f).map { c =>
                            Streams.emitChunkAndThen(c) {
                                if c.isEmpty || command.isEmpty then
                                    Resume((), k(().asInstanceOf[T]))
                                else
                                    k(().asInstanceOf[T])
                            }
                        }
            Streams[V].handle(handler)((), s)
        end dropWhile

        def filter[S2](f: V => Boolean < S2): Stream[T, V, S & S2] =
            val handler = new Handler[Const[Chunk[V]], Streams[V], Streams[V] & S & S2]:
                def resume[T, U: Flat, S3](
                    command: Chunk[V],
                    k: T => U < (Streams[V] & S3)
                ) =
                    command.filter(f).map { c =>
                        Streams.emitChunkAndThen(c) {
                            Resume((), k(().asInstanceOf[T]))
                        }
                    }
                end resume
            Streams[V].handle(handler)((), s)
        end filter

        def changes: Stream[T, V, S] =
            val handler =
                new ResultHandler[V | Null, Const[Chunk[V]], Streams[V], Id, Streams[V] & S]:
                    def done[T](st: V | Null, v: T) = v
                    def resume[T, U: Flat, S3](
                        st: V | Null,
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S3)
                    ): (U | Resume[U, S3]) < (Streams[V] & S & S3) =
                        val c = command.changes(st)
                        Streams.emitChunkAndThen(c) {
                            val nst = if c.isEmpty then st else c.last
                            Resume(nst, k(().asInstanceOf[T]))
                        }
                    end resume
            Streams[V].handle(handler)(null, s)
        end changes

        def collect[S2, V2: Flat](
            pf: PartialFunction[V, Unit < (Streams[V2] & S2)]
        )(using tag2: Tag[Streams[V2]]): Stream[T, V2, S & S2] =
            val handler =
                new Handler[Const[Chunk[V]], Streams[V], Streams[V2] & S & S2]:
                    def resume[T, U: Flat, S3](
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S3)
                    ) =
                        command.collectUnit(pf).andThen {
                            Streams.emitChunkAndThen(Chunks.init[V2]) {
                                Resume((), k(().asInstanceOf[T]))
                            }
                        }
            Streams[V].handle(handler)((), s)
        end collect

        def transform[V2: Flat, S2](f: V => V2 < S2)(
            using tag2: Tag[Streams[V2]]
        ): Stream[T, V2, S & S2] =
            val handler =
                new Handler[Const[Chunk[V]], Streams[V], Streams[V2] & S & S2]:
                    def resume[T, U: Flat, S3](
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S3)
                    ): (U | Resume[U, S3]) < (Streams[V2] & S & S2 & S3) =
                        command.map(f).map { c =>
                            Streams.emitChunkAndThen(c) {
                                Resume((), k(().asInstanceOf[T]))
                            }
                        }
                    end resume
            Streams[V].handle(handler)((), s)
        end transform

        def concat[T2: Flat, S2](
            s2: Stream[T2, V, S2]
        ): Stream[(T, T2), V, S & S2] =
            s.map(t => s2.map((t, _)))

        def runFold[A: Flat, S2](acc: A)(f: (A, V) => A < S2): (A, T) < (S & S2) =
            val handler =
                new ResultHandler[A, Const[Chunk[V]], Streams[V], [T] =>> (A, T), S & S2]:
                    def done[T](st: A, v: T) = (st, v)
                    def resume[T, U: Flat, S2](
                        st: A,
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S2)
                    ) =
                        command.foldLeft(st)(f).map { r =>
                            Resume(r, k(().asInstanceOf[T]))
                        }
                    end resume
            Streams[V].handle(handler)(acc, s)
        end runFold

        def runDiscard: T < S =
            Streams[V].handle(discardHandler[V])((), s)

        def runChunk: (Chunk[V], T) < S =
            val handler =
                new ResultHandler[Chunk[Chunk[V]], Const[Chunk[V]], Streams[V], [T] =>> (Chunk[V], T), S]:
                    def done[T](st: Chunk[Chunk[V]], v: T) = (st.flatten, v)
                    def resume[T, U: Flat, S2](
                        st: Chunk[Chunk[V]],
                        command: Chunk[V],
                        k: T => U < (Streams[V] & S2)
                    ) =
                        Resume(st.append(command), k(().asInstanceOf[T]))
            Streams[V].handle(handler)(Chunks.init, s)
        end runChunk

        def runSeq: (IndexedSeq[V], T) < S =
            runChunk.map((c, v) => (c.toSeq, v))

    end extension

    private[kyo] inline def source[T, V, S](s: T < (Streams[V] & S)): Stream[T, V, S] =
        s

    private object internal:
        private val discard = new Handler[Const[Chunk[Any]], Streams[Any], Any]:
            def resume[T, U: Flat, S](command: Chunk[Any], k: T => U < (Streams[Any] & S)) =
                Resume((), k(().asInstanceOf[T]))

        def discardHandler[V]: Handler[Const[Chunk[V]], Streams[V], Any] =
            discard.asInstanceOf[Handler[Const[Chunk[V]], Streams[V], Any]]
    end internal

end Stream

class Streams[V] extends Effect[Streams[V]]:
    type Command[T] = Chunk[V]

object Streams:

    import internal.*

    def initSource[V]: InitSourceDsl[V] = new InitSourceDsl[V]

    def initSeq[V](c: Seq[V])(using Tag[Streams[V]]): Stream[Unit, V, Any] =
        initSource(emitSeq(c))

    def initChunk[V](c: Chunk[V])(using Tag[Streams[V]]): Stream[Unit, V, Any] =
        initSource(emitChunk(c))

    def emitSeq[V](s: Seq[V])(using Tag[Streams[V]]): Unit < Streams[V] =
        if s.isEmpty then
            ()
        else
            emitChunk(Chunks.initSeq(s))

    inline def emitChunk[V](c: Chunk[V])(using inline tag: Tag[Streams[V]]): Unit < Streams[V] =
        if c.isEmpty then
            ()
        else
            Streams[V].suspend[Unit](c)

    inline def emitChunkAndThen[V, U, S](c: Chunk[V])(inline f: => U < S)(using
        inline tag: Tag[Streams[V]]
    ): U < (S & Streams[V]) =
        Streams[V].suspend[Unit, U, S](c, _ => f)

    object internal:
        class InitSourceDsl[V]:
            def apply[T, S](v: T < (Streams[V] & S)): Stream[T, V, S] =
                Stream.source(v)
    end internal
end Streams
