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
  * Arbitrary byte sources need no dedicated entry point:
  * {{{
  * Stream.init(bytes).into(Jsonl.pipe[Event]())
  * }}}
  *
  * @see
  *   [[kyo.JsonLines]] for the pure framing and whole-input surface these pipes are built on
  */
object Jsonl:

    /** A pipe from JSONL bytes to decoded values, aborting on the first undecodable record.
      *
      * Records framed before a record-size breach are emitted before the abort, so a consumer that has already seen them keeps them: a
      * breach ends framing but never retracts a record that was already complete.
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
                        val framed = framer.feed(Span.from(chunk))
                        Abort.get(foldResults(decodeRecords[A](framed.records, maxDepth, maxCollectionSize))).map { values =>
                            framed.error match
                                case Absent         => emitNonEmpty(values)(Loop.continue(framed.framer))
                                case Present(error) =>
                                    // No record boundary was found, so `framed.framer` must not be used again.
                                    // The records completed before the breach still stand and are emitted first.
                                    emitNonEmpty(values)(Abort.fail(error))
                        }
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

    /** Folds a chunk of per-record results into a single result, short-circuiting on the first failure or panic.
      *
      * `JsonLines` keeps an equivalent fold private to its own file, so the strict pipe carries this local copy rather than widening that
      * one into public API for a combinator neither module wants to publish.
      */
    private def foldResults[E, A](results: Chunk[Result[E, A]]): Result[E, Chunk[A]] =
        @tailrec
        def loop(i: Int, acc: Chunk[A]): Result[E, Chunk[A]] =
            if i >= results.size then Result.succeed(acc)
            else
                results(i) match
                    case Result.Success(a) => loop(i + 1, acc :+ a)
                    case Result.Failure(e) => Result.fail(e)
                    case Result.Panic(ex)  => Result.panic(ex)
        loop(0, Chunk.empty[A])
    end foldResults

end Jsonl
