package kyo

import Stream.*
import kyo.*
import kyo.core.*
import kyo.core.internal.*
import kyo.internal.Trace
import scala.annotation.implicitNotFound
import scala.annotation.tailrec

case class Stream[+T, V, -S](s: T < (Streams[V] & S)):

    def get(using Trace, Tag[Streams[V]]): T < (Streams[V] & S) =
        s

    def take(n: Int)(using trace: Trace, tag: Tag[Streams[V]]): Stream[T, V, S] =
        Stream {
            if n <= 0 then
                runDiscard
            else
                handle.state(tag, n, s) {
                    [C] =>
                        (input, state, cont) =>
                            if state == 0 then
                                (state, cont(()))
                            else
                                val t: Chunk[V] = input.take(state)
                                (state - t.size, Streams.emitChunkAndThen(t)(cont(())))
                }
        }

    def drop(n: Int)(using trace: Trace, tag: Tag[Streams[V]]): Stream[T, V, S] =
        Stream {
            if n <= 0 then
                s
            else
                handle.state(tag, n, s) {
                    [C] =>
                        (input, state, cont) =>
                            if state == 0 then
                                (state, Streams.emitChunkAndThen(input)(cont(())))
                            else
                                val t: Chunk[V] = input.dropLeft(state)
                                (state - Math.min(input.size, state), Streams.emitChunkAndThen(t)(cont(())))
                }
        }

    def takeWhile[S2](f: V => Boolean < S2)(using trace: Trace, tag: Tag[Streams[V]]): Stream[T, V, S & S2] =
        Stream {
            handle.state(tag, true, s) {
                [C] =>
                    (input, state, cont) =>
                        if state then
                            input.takeWhile(f).map { c =>
                                (input.size == c.size, Streams.emitChunkAndThen(c)(cont(())))
                            }
                        else
                            (state, cont(()))
            }
        }

    def dropWhile[S2](f: V => Boolean < S2)(using trace: Trace, tag: Tag[Streams[V]]): Stream[T, V, S & S2] =
        Stream {
            handle.state(tag, true, s) {
                [C] =>
                    (input, state, cont) =>
                        if state then
                            input.dropWhile(f).map { c =>
                                (c.isEmpty, Streams.emitChunkAndThen(c)(cont(())))
                            }
                        else
                            (state, cont(()))
            }
        }

    def filter[S2](f: V => Boolean < S2)(using trace: Trace, tag: Tag[Streams[V]]): Stream[T, V, S & S2] =
        Stream {
            handle(tag, s) {
                [C] =>
                    (input, cont) =>
                        input.filter(f).map(c => Streams.emitChunkAndThen(c)(cont(())))
            }
        }

    def changes(using trace: Trace, tag: Tag[Streams[V]]): Stream[T, V, S] =
        Stream {
            handle.state(tag, null: Null | V, s) {
                [C] =>
                    (input, state, cont) =>
                        val c   = input.changes(state)
                        val nst = if c.isEmpty then state else c.last
                        (nst, Streams.emitChunkAndThen(c)(cont(())))
            }
        }

    def collect[S2, V2](
        pf: PartialFunction[V, Unit < (Streams[V2] & S2)]
    )(using tag: Tag[Streams[V]], tag2: Tag[Streams[V2]], trace: Trace): Stream[T, V2, S & S2] =
        Stream[T, V2, S & S2] {
            handle(tag, s) {
                [C] =>
                    (input, cont) =>
                        input.collectUnit(pf).andThen(Streams.emitChunkAndThen(Chunks.init[V2])(cont(())))
            }
        }

    def transform[V2, S2](f: V => V2 < S2)(
        using
        tag: Tag[Streams[V]],
        tag2: Tag[Streams[V2]],
        trace: Trace
    ): Stream[T, V2, S & S2] =
        Stream[T, V2, S & S2] {
            handle(tag, s) {
                [C] =>
                    (input, cont) =>
                        input.map(f).map(c => Streams.emitChunkAndThen(c)(cont(())))
            }
        }

    def transformChunks[V2, S2](f: Chunk[V] => Chunk[V2] < S2)(
        using
        tag: Tag[Streams[V]],
        tag2: Tag[Streams[V2]],
        trace: Trace
    ): Stream[T, V2, S & S2] =
        Stream[T, V2, S & S2] {
            handle(tag, s) {
                [C] =>
                    (input, cont) =>
                        f(input).map(c => Streams.emitChunkAndThen(c)(cont(())))
            }
        }

    def concat[T2, S2](
        s2: Stream[T2, V, S2]
    )(using Trace, Tag[Streams[V]]): Stream[(T, T2), V, S & S2] =
        Stream(s.map(t => s2.s.map((t, _))))

    def buffer[S2 <: S](size: Int)(
        using
        @implicitNotFound(
            "Can't buffer a stream with pending effects other than 'Fibers' and 'IOs'. Found: ${S}"
        ) ev: S2 => Fibers | IOs,
        trace: Trace,
        tag: Tag[Streams[V]]
    ): Stream[T, V, Fibers & S2] =
        Stream {
            val run =
                Channels.init[Chunk[V] | Stream.Done](size).map { ch =>
                    val s2 = this.asInstanceOf[Stream[T, V, Fibers]]
                    Fibers.init(s2.runChannel(ch)).map { f =>
                        Streams.emitChannel[V](ch).andThen(f.get)
                    }
                }
            run
        }

    def runFold[A, S2](acc: A)(f: (A, V) => A < S2)(using trace: Trace, tag: Tag[Streams[V]]): (A, T) < (S & S2) =
        handle.state(tag, acc, s)(
            handle = [C] =>
                (input, state, cont) =>
                    input.foldLeft(state)(f).map((_, cont(()))),
            done = (state, r) => (state, r)
        )

    def runDiscard(using trace: Trace, tag: Tag[Streams[V]]): T < S =
        handle(tag, s)([C] => (_, cont) => cont(()))

    def runChunk(using trace: Trace, tag: Tag[Streams[V]]): (Chunk[V], T) < S =
        handle.state(tag, Chunks.init[Chunk[V]], s)(
            handle = [C] =>
                (input, state, cont) =>
                    (state.append(input), cont(())),
            done = (state, r) => (state.flatten, r)
        )

    def runSeq(using Trace, Tag[Streams[V]]): (IndexedSeq[V], T) < S =
        runChunk.map((c, v) => (c.toSeq, v))

    def runChannel(ch: Channel[Chunk[V] | Done])(using trace: Trace, tag: Tag[Streams[V]]): T < (Fibers & S) =
        handle(tag, s) {
            [C] =>
                (input, cont) =>
                    ch.put(input).andThen(cont(()))
        }

end Stream

object Stream:

    class Done
    object Done extends Done

end Stream

class Streams[V] extends Effect[Const[Chunk[V]], Const[Unit]]

object Streams:

    import internal.*

    def initSource[V]: InitSourceDsl[V] = new InitSourceDsl[V]

    def initSeq[V](c: Seq[V])(using Tag[Streams[V]], Trace): Stream[Unit, V, Any] =
        initSource(emitSeq(c))

    def initChunk[V](c: Chunk[V])(using Tag[Streams[V]], Trace): Stream[Unit, V, Any] =
        initSource(emitChunk(c))

    def initChannel[V](ch: Channel[Chunk[V] | Stream.Done])(
        using
        Tag[Streams[V]],
        Trace
    ): Stream[Unit, V, Fibers] =
        initSource(emitChannel(ch))

    def emitSeq[V](s: Seq[V])(using Tag[Streams[V]])(using Trace): Unit < Streams[V] =
        if s.isEmpty then
            ()
        else
            emitChunk(Chunks.initSeq(s))

    inline def emitChunk[V](c: Chunk[V])(using inline tag: Tag[Streams[V]], inline trace: Trace): Unit < Streams[V] =
        if c.isEmpty then
            ()
        else
            suspend[Any](tag, c)

    inline def emitChunkAndThen[V, U, S](c: Chunk[V])(inline f: => U < S)(using
        inline tag: Tag[Streams[V]]
    )(using Trace): U < (S & Streams[V]) =
        suspend[Any](tag, c, _ => f)

    def emitChannel[V](ch: Channel[Chunk[V] | Stream.Done])(using
        Tag[Streams[V]]
    )(using Trace): Unit < (Streams[V] & Fibers) =
        ch.take.map {
            case e if e.equals(Stream.Done) =>
                ()
            case v: Chunk[V] @unchecked =>
                emitChunkAndThen(v)(emitChannel(ch))
        }

    object internal:

        class InitSourceDsl[V]:
            def apply[T, S](v: T < (Streams[V] & S))(using Tag[Streams[V]]): Stream[T, V, S] =
                Stream(v)
    end internal
end Streams
