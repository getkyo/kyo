package kyo

import kyo.Emit.Ack
import kyo.Emit.Ack.*
import kyo.Tag
import kyo.kernel.Effect
import scala.annotation.targetName

case class Stream[V, -S](v: Ack < (Emit[Chunk[V]] & S)):

    def emit: Ack < (Emit[Chunk[V]] & S) = v

    private def continue[S2](f: Int => Ack < (Emit[Chunk[V]] & S & S2))(using Frame): Stream[V, S & S2] =
        Stream(v.map {
            case Stop        => Stop
            case Continue(n) => f(n)
        })

    def concat[S2](other: Stream[V, S2])(using Frame): Stream[V, S & S2] =
        continue(_ => other.emit)

    def map[V2, S2](f: V => V2 < S2)(using Tag[Emit[Chunk[V]]], Tag[Emit[Chunk[V2]]], Frame): Stream[V2, S & S2] =
        mapChunk(c => Kyo.foreach(c)(f))

    def mapChunk[V2, S2](f: Chunk[V] => Seq[V2] < S2)(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2] =
        Stream[V2, S & S2](Effect.handle.state(tagV, (), v)(
            [C] =>
                (input, _, cont) =>
                    if input.isEmpty then
                        Emit.andMap(Chunk.empty[V2])(ack => ((), cont(ack)))
                    else
                        f(input).map(c => Emit.andMap(Chunk.from(c))(ack => ((), cont(ack))))
        ))

    def flatMap[S2, V2, S3](f: V => Stream[V2, S2] < S3)(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2 & S3] =
        Stream[V2, S & S2 & S3](Effect.handle.state(tagV, (), v)(
            [C] =>
                (input, _, cont) =>
                    Kyo.foldLeft(input)(Continue(): Ack) { (ack, v) =>
                        ack match
                            case Stop        => Stop
                            case Continue(_) => f(v).map(_.emit)
                    }.map(ack => ((), cont(ack)))
        ))

    def flatMapChunk[S2, V2, S3](f: Chunk[V] => Stream[V2, S2] < S3)(
        using
        tagV: Tag[Emit[Chunk[V]]],
        tagV2: Tag[Emit[Chunk[V2]]],
        frame: Frame
    ): Stream[V2, S & S2 & S3] =
        Stream[V2, S & S2 & S3](Effect.handle.state(tagV, (), v)(
            [C] =>
                (input, _, cont) =>
                    if input.isEmpty then
                        Emit.andMap(Chunk.empty[V2])(ack => ((), cont(ack)))
                    else
                        f(input).map(_.emit).map(ack => ((), cont(ack)))
        ))

    private def discard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream(Effect.handle(tag, v)(
            [C] => (input, cont) => cont(Stop)
        ))

    def take(n: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        if n <= 0 then discard
        else
            Stream[V, S](Effect.handle.state(tag, n, v)(
                [C] =>
                    (input, state, cont) =>
                        if state == 0 then
                            (0, cont(Stop))
                        else
                            val c   = input.take(state)
                            val nst = state - c.size
                            Emit.andMap(c)(ack => (nst, cont(ack.maxItems(nst))))
            ))

    def drop(n: Int)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        if n <= 0 then this
        else
            Stream[V, S](Effect.handle.state(tag, n, v)(
                [C] =>
                    (input, state, cont) =>
                        if state == 0 then
                            Emit.andMap(input)(ack => (0, cont(ack)))
                        else
                            val c = input.dropLeft(state)
                            if c.isEmpty then (state - c.size, cont(Continue()))
                            else Emit.andMap(c)(ack => (0, cont(ack)))
            ))

    def takeWhile[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](Effect.handle.state(tag, true, v)(
            [C] =>
                (input, state, cont) =>
                    if !state then (false, cont(Stop))
                    else
                        Kyo.takeWhile(input)(f).map { c =>
                            Emit.andMap(c)(ack => (c.size == input.size, cont(ack)))
                    }
        ))
    end takeWhile

    def dropWhile[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](Effect.handle.state(tag, true, v)(
            [C] =>
                (input, state, cont) =>
                    if state then
                        Kyo.dropWhile(input)(f).map { c =>
                            if c.isEmpty then (true, cont(Continue()))
                            else Emit.andMap(c)(ack => (false, cont(ack)))
                        }
                    else
                        Emit.andMap(input)(ack => (false, cont(ack)))
        ))

    def filter[S2](f: V => Boolean < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S & S2] =
        Stream[V, S & S2](Effect.handle.state(tag, (), v)(
            [C] =>
                (input, _, cont) =>
                    Kyo.filter(input)(f).map { c =>
                        if c.isEmpty then ((), cont(Continue()))
                        else Emit.andMap(c)(ack => ((), cont(ack)))
                }
        ))

    def changes(using Tag[Emit[Chunk[V]]], Frame, CanEqual[V, V]): Stream[V, S] =
        changes(Maybe.empty)

    def changes(first: V)(using Tag[Emit[Chunk[V]]], Frame, CanEqual[V, V]): Stream[V, S] =
        changes(Maybe(first))

    @targetName("changesMaybe")
    def changes(first: Maybe[V])(using tag: Tag[Emit[Chunk[V]]], frame: Frame, ce: CanEqual[V, V]): Stream[V, S] =
        Stream[V, S](Effect.handle.state(tag, first, v)(
            [C] =>
                (input, state, cont) =>
                    val c        = input.changes(state)
                    val newState = if c.isEmpty then state else Maybe(c.last)
                    Emit.andMap(c) { ack =>
                        (newState, cont(ack))
                }
        ))
    end changes

    def runDiscard(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < S =
        Effect.handle(tag, v.unit)(
            [C] => (input, cont) => cont(Stop)
        )

    def runForeach[S2](f: V => Unit < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < (S & S2) =
        runForeachChunk(c => Kyo.foreachDiscard(c)(f))

    def runForeachChunk[S2](f: Chunk[V] => Unit < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Unit < (S & S2) =
        Effect.handle(tag, v.unit)(
            [C] =>
                (input, cont) =>
                    if !input.isEmpty then
                        f(input).andThen(cont(Continue()))
                    else
                        cont(Continue())
        )

    def runFold[A, S2](acc: A)(f: (A, V) => A < S2)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): A < (S & S2) =
        Effect.handle.state(tag, acc, v)(
            handle = [C] =>
                (input, state, cont) =>
                    Kyo.foldLeft(input)(state)(f).map((_, cont(Continue()))),
            done = (state, _) => state
        )

    def run(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Chunk[V] < S =
        Effect.handle.state(tag, Chunk.empty[Chunk[V]], v)(
            handle = [C] =>
                (input, state, cont) =>
                    (state.append(input), cont(Continue())),
            done = (state, _) => state.flatten
        )

end Stream

object Stream:

    private val _empty           = Stream(Stop)
    def empty[V]: Stream[Any, V] = _empty.asInstanceOf[Stream[Any, V]]

    def init[V, S](seq: Seq[V] < S)(using tag: Tag[Emit[Chunk[V]]], frame: Frame): Stream[V, S] =
        Stream[V, S](
            seq.map { seq =>
                val chunk: Chunk[V] = Chunk.from(seq)
                Emit.andMap(Chunk.empty[V]) { ack =>
                    Loop(chunk, ack) { (c, ack) =>
                        ack match
                            case Stop =>
                                Loop.done(Stop)
                            case Continue(n) =>
                                if c.isEmpty then Loop.done(Ack.Continue())
                                else Emit.andMap(c.take(n))(ack => Loop.continue(c.dropLeft(n), ack))
                    }
                }
            }
        )

end Stream
