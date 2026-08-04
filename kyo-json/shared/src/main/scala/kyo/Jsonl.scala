package kyo

import scala.annotation.tailrec

/** Streaming JSONL (also called NDJSON): one JSON value per line, over effectful sources.
  *
  * The format is how append-only record logs are exchanged, including AI agent session transcripts and structured application logs. This
  * entry point reads them as `Stream` values, so a multi-gigabyte transcript is processed record by record rather than loaded whole.
  *
  * All framing and parsing live in `Json.Lines.Framer` in kyo-schema-json, which is pure. Every entry point here is a loop over that framer,
  * so the effectful and pure surfaces cannot drift: no newline scanning, no line splitting, and no JSON parsing is repeated here. Use
  * `Json.Lines` directly when the whole input is already in memory and no effects are wanted.
  *
  * Strict entry points abort on the first undecodable record. The `Results` variants emit one `Result` per record instead, which is what
  * heterogeneous or partially-written logs need.
  *
  * Arbitrary byte sources need no dedicated entry point, because any `Stream[Byte, S]` is already the input:
  * {{{
  * Stream.fromInputStream(is).into(Jsonl.pipe[Event]())
  * }}}
  *
  * @see
  *   [[kyo.JsonLines]] for the pure framing and whole-input surface these pipes are built on
  */
object Jsonl:

    /** A pipe from JSONL bytes to decoded values, aborting on the first undecodable record.
      *
      * Every record before the failing one is emitted, and only then does the abort happen. This holds for both ways a chunk can fail, an
      * undecodable record and a record-size breach, so a consumer that has already seen a record keeps it.
      *
      * Emitting the prefix is what makes the output before an abort a function of the data alone. Folding a chunk's records to one
      * `Result` and aborting before emitting would instead discard the records that preceded the bad one within the same chunk while
      * keeping those from earlier chunks, which makes how much a consumer receives depend on the upstream source's buffer size: a
      * 1000-record input failing at record 500 would deliver 499 records to a `foreach` when fed a byte at a time and none of them when
      * fed in one chunk. Chunking is an upstream detail and must not be observable here.
      *
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `Json.Lines.DefaultMaxLineBytes`)
      * @return
      *   a pipe emitting one value per record, aborting with the first decode or framing failure
      */
    def pipe[A](
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = Json.Lines.DefaultMaxLineBytes
    )(using Json, Schema[A], Tag[Emit[Chunk[A]]], Frame): Pipe[Byte, A, Abort[DecodeException]] =
        Pipe:
            Loop(Json.Lines.Framer.init(maxLineBytes)) { framer =>
                Poll.andMap[Chunk[Byte]] {
                    case Absent =>
                        framer.finish match
                            case Absent => Loop.done
                            case Present(record) =>
                                Abort.get(Json.Lines.decodeRecord[A](record, maxDepth, maxCollectionSize)).map { value =>
                                    Emit.valueWith(Chunk(value))(Loop.done)
                                }
                    case Present(chunk) =>
                        val framed            = framer.feed(Span.from(chunk))
                        val (values, failure) = decodePrefix[A](framed.records, maxDepth, maxCollectionSize)
                        failure match
                            case Present(error) =>
                                // A record inside this chunk failed to decode. Everything decoded before it is
                                // emitted first, so the prefix a consumer sees does not depend on the chunking.
                                emitNonEmpty(values)(Abort.error(error))
                            case Absent =>
                                framed.error match
                                    case Absent         => emitNonEmpty(values)(Loop.continue(framed.framer))
                                    case Present(error) =>
                                        // No record boundary was found, so `framed.framer` must not be used again.
                                        // The records completed before the breach still stand and are emitted first.
                                        emitNonEmpty(values)(Abort.fail(error))
                        end match
                }
            }

    /** A pipe from JSONL bytes to per-record `Result`s, surviving undecodable records.
      *
      * The variant for heterogeneous or partially-written logs: a truncated tail or an unrecognized record type yields one failure element
      * rather than ending the stream.
      *
      * A record exceeding `maxLineBytes` is the one failure this cannot recover from, because no record boundary was found and there is
      * nothing to skip to. It surfaces as a final failure element after every record framed before it, and then the stream ends.
      *
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `Json.Lines.DefaultMaxLineBytes`)
      * @return
      *   a pipe emitting one result per record
      */
    def pipeResults[A](
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = Json.Lines.DefaultMaxLineBytes
    )(using Json, Schema[A], Tag[Emit[Chunk[Result[DecodeException, A]]]], Frame): Pipe[Byte, Result[DecodeException, A], Any] =
        Pipe:
            Loop(Json.Lines.Framer.init(maxLineBytes)) { framer =>
                Poll.andMap[Chunk[Byte]] {
                    case Absent =>
                        framer.finish match
                            case Absent => Loop.done
                            case Present(record) =>
                                Emit.valueWith(Chunk(Json.Lines.decodeRecord[A](record, maxDepth, maxCollectionSize)))(Loop.done)
                    case Present(chunk) =>
                        val framed  = framer.feed(Span.from(chunk))
                        val decoded = decodeRecords[A](framed.records, maxDepth, maxCollectionSize)
                        framed.error match
                            case Absent         => emitNonEmpty(decoded)(Loop.continue(framed.framer))
                            case Present(error) =>
                                // No record boundary was found, so `framed.framer` must not be used again and
                                // there is no point resuming: emit what was framed, report the breach, and stop.
                                Emit.valueWith(decoded :+ Result.fail[DecodeException, A](error))(Loop.done)
                        end match
                }
            }

    /** Emits `values` and then continues with `next`, skipping the emission when `values` is empty.
      *
      * A fed chunk that completes no record is the common case whenever chunks are smaller than records, and a byte-at-a-time source hits
      * it on nearly every chunk. Emitting an empty chunk there costs one suspension per chunk and gives a consumer nothing to do.
      */
    private inline def emitNonEmpty[V, A, S](values: Chunk[V])(inline next: => A < S)(
        using
        inline tag: Tag[Emit[Chunk[V]]],
        inline frame: Frame
    ): A < (S & Emit[Chunk[V]]) =
        if values.isEmpty then next
        else Emit.valueWith(values)(next)

    /** Decodes every framed record of one chunk, one result per record. */
    private def decodeRecords[A](
        records: Chunk[Json.Lines.Record],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): Chunk[Result[DecodeException, A]] =
        records.map(record => Json.Lines.decodeRecord[A](record, maxDepth, maxCollectionSize))

    /** Decodes records up to the first failure, returning the values decoded before it and that failure.
      *
      * The strict pipe needs the prefix and the failure separately, because it emits the prefix and only then aborts. Folding to a single
      * `Result` instead, the shape `JsonLines.decodeAllBytes` uses for whole-input decoding, would throw the prefix away, and a pipe that
      * throws it away leaks the upstream chunk size into what a consumer receives.
      *
      * The failure is carried as a `Result.Error`, which covers `Failure` and `Panic`, and it reaches the caller's `Abort.error`
      * unchanged. A panic therefore stays a panic and is never rewritten into a decode failure.
      */
    private def decodePrefix[A](
        records: Chunk[Json.Lines.Record],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): (Chunk[A], Maybe[Result.Error[DecodeException]]) =
        @tailrec
        def loop(i: Int, acc: Chunk[A]): (Chunk[A], Maybe[Result.Error[DecodeException]]) =
            if i >= records.size then (acc, Absent)
            else
                Json.Lines.decodeRecord[A](records(i), maxDepth, maxCollectionSize) match
                    case Result.Success(value)                               => loop(i + 1, acc :+ value)
                    case failure: Result.Failure[DecodeException] @unchecked => (acc, Present(failure))
                    case panic: Result.Panic                                 => (acc, Present(panic))
        loop(0, Chunk.empty[A])
    end decodePrefix

end Jsonl
