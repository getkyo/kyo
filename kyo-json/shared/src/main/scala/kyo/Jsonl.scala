package kyo

import kyo.kernel.ArrowEffect
import scala.annotation.tailrec

/** Streaming JSONL (also called NDJSON): one JSON value per line, over effectful sources.
  *
  * The format is how append-only record logs are exchanged, including AI agent session transcripts and structured application logs. This
  * entry point reads them as `Stream` values, so a multi-gigabyte transcript is processed record by record rather than loaded whole.
  *
  * All framing and parsing live in `Json.Lines.Framer` in kyo-schema-json, which is pure. Every entry point here is a loop over that
  * framer, so the effectful and pure surfaces cannot drift: no newline scanning, no line splitting, and no JSON parsing is repeated here.
  * Use `Json.Lines` directly when the whole input is already in memory and no effects are wanted.
  *
  * Strict entry points abort on the first undecodable record. The `Results` variants emit one `Result` per record instead, which is what
  * heterogeneous or partially-written logs need.
  *
  * `read` covers a file that is complete; `follow` covers one still being written, replaying what is already there and then emitting each
  * record as it is appended.
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
                        frameChunk[A](framer, Span.from(chunk), maxDepth, maxCollectionSize) match
                            case Framing.Continued(results, advanced) =>
                                val (values, failure) = splitAtFailure(results)
                                failure match
                                    case Absent         => emitNonEmpty(values)(Loop.continue(advanced))
                                    case Present(error) =>
                                        // A record inside this chunk failed to decode. Everything decoded before it is
                                        // emitted first, so the prefix a consumer sees does not depend on the chunking.
                                        emitNonEmpty(values)(Abort.error(error))
                                end match
                            case Framing.Halted(results, breach) =>
                                // No record boundary was found, so framing is over either way. The records
                                // completed before the breach still stand and are emitted first.
                                val (values, failure) = splitAtFailure(results)
                                failure match
                                    case Absent         => emitNonEmpty(values)(Abort.fail(breach))
                                    case Present(error) => emitNonEmpty(values)(Abort.error(error))
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
                        frameChunk[A](framer, Span.from(chunk), maxDepth, maxCollectionSize) match
                            case Framing.Continued(results, advanced) => emitNonEmpty(results)(Loop.continue(advanced))
                            case Framing.Halted(results, breach)      =>
                                // No record boundary was found and no rewind can arrive on a finite input, so
                                // there is no point resuming: emit what was framed, report the breach, and stop.
                                Emit.valueWith(results :+ Result.fail[DecodeException, A](breach))(Loop.done)
                        end match
                }
            }

    /** Reads a JSONL file as a stream of decoded values, aborting on the first undecodable record.
      *
      * The file handle is registered with the enclosing `Scope` and closed when that scope ends.
      *
      * @param path
      *   the file to read
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `Json.Lines.DefaultMaxLineBytes`)
      * @return
      *   a stream of decoded values, aborting with the first decode or framing failure
      */
    def read[A](
        path: Path,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = Json.Lines.DefaultMaxLineBytes
    )(using Json, Schema[A], Tag[Emit[Chunk[A]]], Frame): Stream[A, Scope & Sync & Abort[FileReadException | DecodeException]] =
        path.readBytesStream.into(pipe[A](maxDepth, maxCollectionSize, maxLineBytes))

    /** Reads a JSONL file as a stream of per-record `Result`s, surviving undecodable records.
      *
      * The file handle is registered with the enclosing `Scope` and closed when that scope ends.
      *
      * @param path
      *   the file to read
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `Json.Lines.DefaultMaxLineBytes`)
      * @return
      *   a stream of per-record results
      */
    def readResults[A](
        path: Path,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = Json.Lines.DefaultMaxLineBytes
    )(using
        Json,
        Schema[A],
        Tag[Emit[Chunk[Result[DecodeException, A]]]],
        Frame
    ): Stream[Result[DecodeException, A], Scope & Sync & Abort[FileReadException]] =
        path.readBytesStream.into(pipeResults[A](maxDepth, maxCollectionSize, maxLineBytes))

    /** Streams a JSONL file's records, continuing to emit as records are appended.
      *
      * Reading is one continuous pass that switches from draining to polling once it reaches the end of the file, not a read concatenated
      * with a tail, so a record appended while the existing content is still being replayed is never missed. The file handle is registered
      * with the enclosing `Scope` and closed when that scope ends; until then the stream never completes on its own, so a consumer
      * bounds it with `take`, `takeWhile`, or an interrupt.
      *
      * `from` defaults to [[Path.Origin.Start]], which replays the file and then follows, because reading a live agent transcript wants
      * every record rather than only those written after the reader attached. [[Path.tailBytes]] defaults to [[Path.Origin.End]] instead,
      * since it is the primitive under `Path.tail` and has to preserve that method's follow-only contract.
      *
      * A file truncated below the read position is replayed from its first byte, and the framer is rebuilt at that rewind. A record left
      * half written when the truncation happened is therefore dropped along with the rest of the old content, and never spliced onto the
      * first replayed record. That holds wherever the truncation fell, so a log rotated in place replays as a clean read of its new
      * content whether or not the cut landed on a record boundary.
      *
      * Following tracks the open file and not the name, which is what `tail -f` does and `tail -F` does not. The file is opened once, so
      * the name it was opened under can afterwards be renamed, replaced, or deleted with no effect on what this stream reads. Rotation by
      * rename therefore keeps the stream on the original file: it emits whatever is still appended through that file's new name, and
      * nothing written to the new file created under the old name. Deleting the file yields no further records and no failure, because an
      * unlinked file that is still open keeps its content and can still be measured. A consumer that needs to pick up a rotated-in
      * replacement closes this stream and opens a new one against the path. [[Path.tailBytes]] states the same contract in byte terms: it
      * is a sibling driver over the same polling loop rather than a layer beneath this one.
      *
      * @param path
      *   the file to follow
      * @param from
      *   where reading begins; [[Path.Origin.Start]] replays, [[Path.Origin.End]] emits only records appended afterwards, and
      *   [[Path.Origin.Offset]] resumes from a recorded byte position
      * @param pollDelay
      *   how long to sleep between polls once the end of the file is reached
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `Json.Lines.DefaultMaxLineBytes`)
      * @return
      *   an unbounded stream of decoded values, aborting with the first decode or framing failure
      */
    def follow[A](
        path: Path,
        from: Path.Origin = Path.Origin.Start,
        pollDelay: Duration = 100.millis,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = Json.Lines.DefaultMaxLineBytes
    )(using
        Json,
        Schema[A],
        Tag[Emit[Chunk[Result[DecodeException, A]]]],
        Tag[Emit[Chunk[A]]],
        Frame
    ): Stream[A, Scope & Async & Abort[FileReadException | DecodeException]] =
        followResults[A](path, from, pollDelay, maxDepth, maxCollectionSize, maxLineBytes).flatMapChunk { results =>
            val (values, failure) = splitAtFailure(results)
            failure match
                case Absent => Stream[A, Any](emitNonEmpty(values)(()))
                // Every value decoded before the failure is emitted first, exactly as [[pipe]] does, so the
                // prefix a consumer receives stays a function of the data rather than of the read size.
                case Present(error) => Stream[A, Abort[DecodeException]](emitNonEmpty(values)(Abort.error(error)))
            end match
        }

    /** Streams a JSONL file's records as per-record `Result`s, continuing to emit as records are appended.
      *
      * The variant for heterogeneous or partially written logs: an undecodable record yields one failure element and the stream carries
      * on. Every other property of [[follow]] holds unchanged, including the `Path.Origin.Start` default, the framer rebuilt across a
      * truncation, and the behavior under rotation by rename.
      *
      * A record exceeding `maxLineBytes` is the one framing failure with nothing to skip to, since no record boundary was found for the
      * pending bytes. It surfaces as a single failure element after every record framed before it, and then the stream ends, exactly as it
      * does in [[pipeResults]]. Framing is over at that point, so ending says so, where staying open would leave a consumer waiting on a
      * stream that can never emit again.
      *
      * @param path
      *   the file to follow
      * @param from
      *   where reading begins; [[Path.Origin.Start]] replays, [[Path.Origin.End]] emits only records appended afterwards, and
      *   [[Path.Origin.Offset]] resumes from a recorded byte position
      * @param pollDelay
      *   how long to sleep between polls once the end of the file is reached
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineBytes
      *   the largest record the framer will accept, in bytes (default `Json.Lines.DefaultMaxLineBytes`)
      * @return
      *   an unbounded stream of per-record results
      */
    def followResults[A](
        path: Path,
        from: Path.Origin = Path.Origin.Start,
        pollDelay: Duration = 100.millis,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineBytes: Int = Json.Lines.DefaultMaxLineBytes
    )(using
        Json,
        Schema[A],
        Tag[Emit[Chunk[Result[DecodeException, A]]]],
        Frame
    ): Stream[Result[DecodeException, A], Scope & Async & Abort[FileReadException]] =
        // The framer is the follow loop's carried state rather than a pipe downstream of it, which is what
        // ties its lifetime to the file's: a rewind restores this initial state, so bytes framed before a
        // truncation cannot reach a record replayed after one.
        val framed =
            Path.follow[Result[DecodeException, A], Json.Lines.Framer](
                path,
                from,
                pollDelay,
                FollowBufferSize,
                Json.Lines.Framer.init(maxLineBytes)
            ) { (framer, buffer, bytesRead) =>
                // The follow loop reuses `buffer` across reads, so the framer, which retains what it
                // cannot yet frame, is handed an array nothing else holds.
                val chunk = Span.fromUnsafe(java.util.Arrays.copyOf(buffer, bytesRead))
                frameChunk[A](framer, chunk, maxDepth, maxCollectionSize) match
                    case Framing.Continued(results, advanced) => (results, advanced)
                    case Framing.Halted(results, breach)      =>
                        // `endAtBreach` ends the stream on this very chunk, so the loop is never resumed and
                        // the state returned here is never fed. `framer` is returned only because the step
                        // owes one: the framer a breach leaves behind is unusable, which is why
                        // [[Framing.Halted]] does not carry it.
                        (results :+ Result.fail[DecodeException, A](breach), framer)
                end match
            }
        endAtBreach(framed)
    end followResults

    /** Ends `stream` at the first framing breach, keeping that breach as its final element.
      *
      * A breach found no record boundary, so there is nothing to skip to and framing is over. Ending there is what the lenient surfaces
      * promise and what [[pipeResults]] does on a finite input: it tells a consumer the records are finished, where staying open would
      * leave it waiting on a stream that can never emit again.
      *
      * The halt lives here, one layer out from `Path.follow`, because that loop's step is pure and a pure step cannot end a stream. The
      * composition can: truncating the emitted stream leaves the framer inside the loop untouched, so the rewind that rebuilds it stays
      * exactly as it is. Discarding the loop's continuation is also what puts the state the step returned at a breach out of reach.
      *
      * A `LimitExceededException` arriving here can only be the framer's, because [[JsonLines.decodeRecord]] wraps every decode failure in
      * a `RecordDecodeException`, so no record's own decoding can produce one.
      */
    private def endAtBreach[A, S](stream: Stream[Result[DecodeException, A], S])(
        using
        tag: Tag[Emit[Chunk[Result[DecodeException, A]]]],
        frame: Frame
    ): Stream[Result[DecodeException, A], S] =
        Stream(
            ArrowEffect.handleLoop(tag, stream.emit)(
                [C] =>
                    (input, cont) =>
                        val breach = breachIndex(input)
                        if breach < 0 then Emit.valueWith(input)(Loop.continue(cont(())))
                        // `Kyo.unit` in place of the continuation is how `Stream.take` stops: the upstream
                        // loop is dropped rather than resumed, so nothing polls the file again.
                        else Emit.valueWith(input.take(breach + 1))(Loop.continue(Kyo.unit))
                        end if
            )
        )
    end endAtBreach

    /** Index of the first framing breach in `results`, or `-1` when there is none.
      *
      * `frameChunk` appends a breach as the last element of the chunk it halts on, so a scan can only ever find it there. It scans anyway,
      * over one chunk: a cheap loop that keeps the halt correct rather than correct-given-where-the-element-sits.
      */
    private def breachIndex[A](results: Chunk[Result[DecodeException, A]]): Int =
        @tailrec
        def loop(i: Int): Int =
            if i >= results.size then -1
            else
                results(i) match
                    case Result.Failure(_: LimitExceededException) => i
                    case _                                         => loop(i + 1)
        loop(0)
    end breachIndex

    /** Read buffer size for the follow drivers.
      *
      * 8 KB, so a record of any ordinary size arrives whole in one read and the framer seldom carries a partial across polls. This is
      * passed to the shared follow loop explicitly rather than inherited from [[Path.tailBytes]]'s own default, so the two are free to
      * differ and neither has to track the other.
      */
    private inline def FollowBufferSize: Int = 8192

    /** What framing and decoding one chunk of JSONL bytes produced.
      *
      * Naming the two outcomes keeps each driver's handling of them a visible decision, and keeps the framer that a breach leaves behind
      * out of reach: the case that carries a breach carries no framer, so there is no value to feed again by mistake.
      */
    private enum Framing[A]:
        /** Every record the chunk completed, and the framer that frames the next chunk. */
        case Continued[A](results: Chunk[Result[DecodeException, A]], framer: Json.Lines.Framer) extends Framing[A]

        /** Every record completed before a record exceeded `maxLineBytes`, and that breach. */
        case Halted[A](results: Chunk[Result[DecodeException, A]], breach: LimitExceededException) extends Framing[A]
    end Framing

    /** Frames one chunk of bytes and decodes every record it completed.
      *
      * The one framing implementation the module has. [[pipe]] and [[pipeResults]] drive it from their `Stream[Byte]` poll loop, [[follow]]
      * and [[followResults]] drive it as `Path.follow`'s step, so neither driver can grow framing rules the other does not have. It stays
      * pure, which is what lets the follow drivers use it as that step at all: the framer becomes state the follow loop carries and
      * discards on a rewind, instead of state a downstream pipe holds and knows nothing about.
      *
      * @param framer
      *   the framer holding whatever the previous chunk left unterminated
      * @param chunk
      *   the next bytes of the input, of any size including empty
      * @return
      *   [[Framing.Continued]] with the chunk's results and the advanced framer, or [[Framing.Halted]] when a record exceeded
      *   `maxLineBytes`, carrying the records completed before the breach
      */
    private def frameChunk[A](
        framer: Json.Lines.Framer,
        chunk: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): Framing[A] =
        val framed  = framer.feed(chunk)
        val results = framed.records.map(record => Json.Lines.decodeRecord[A](record, maxDepth, maxCollectionSize))
        framed.error match
            case Absent         => Framing.Continued(results, framed.framer)
            case Present(error) => Framing.Halted(results, error)
        end match
    end frameChunk

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

    /** Splits results at the first failure, returning the values before it and that failure.
      *
      * The strict surfaces need the prefix and the failure separately, because they emit the prefix and only then abort. Folding to a
      * single `Result` instead, the shape `JsonLines.decodeAllBytes` uses for whole-input decoding, would throw the prefix away, and a
      * surface that throws it away leaks the upstream read size into what a consumer receives.
      *
      * The failure is carried as a `Result.Error`, which covers `Failure` and `Panic`, and it reaches the caller's `Abort.error`
      * unchanged. A panic therefore stays a panic and is never rewritten into a decode failure.
      */
    private def splitAtFailure[A](results: Chunk[Result[DecodeException, A]]): (Chunk[A], Maybe[Result.Error[DecodeException]]) =
        @tailrec
        def loop(i: Int, acc: Chunk[A]): (Chunk[A], Maybe[Result.Error[DecodeException]]) =
            if i >= results.size then (acc, Absent)
            else
                results(i) match
                    case Result.Success(value)                               => loop(i + 1, acc :+ value)
                    case failure: Result.Failure[DecodeException] @unchecked => (acc, Present(failure))
                    case panic: Result.Panic                                 => (acc, Present(panic))
        loop(0, Chunk.empty[A])
    end splitAtFailure

end Jsonl
