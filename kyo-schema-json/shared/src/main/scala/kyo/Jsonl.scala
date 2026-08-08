package kyo

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
  * `encode`, `write`, and `append` go the other way, turning a stream of values into JSONL bytes or into a file. They are bounded in the
  * same way the reading side is: a chunk of values becomes a chunk of bytes and is written, so nothing accumulates across the stream.
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
      * undecodable record and a record-size breach, so a consumer that has already seen a record keeps it. A record-size breach aborts
      * whether or not framing could have skipped past it: recovery is what [[pipeResults]] is for, and a strict pipe stops at the first
      * failure of any kind.
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
      * @param maxLineSize
      *   the largest record the framer will accept (default `Json.Lines.DefaultMaxLineSize`)
      * @return
      *   a pipe emitting one value per record, aborting with the first decode or framing failure
      */
    def pipe[A](
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineSize: ByteSize = Json.Lines.DefaultMaxLineSize
    )(using Json, Schema[A], Tag[Emit[Chunk[A]]], Frame): Pipe[Byte, A, Abort[DecodeException]] =
        Pipe:
            Loop(Json.Lines.Framer.init(maxLineSize)) { framer =>
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
                                // No record boundary was found for the pending bytes, so framing is over either
                                // way. The records completed before the breach still stand and are emitted first.
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
      * A record exceeding `maxLineSize` is one failure element too when its terminator arrived: the boundary is known, so framing skips
      * the record and the records after it are emitted normally. The one failure this cannot recover from is an oversized trailing
      * residual, which found no record boundary and has nothing to skip to. It surfaces as a final failure element after every record
      * framed before it, and then the stream ends.
      *
      * @param maxDepth
      *   maximum nesting depth for objects/arrays (default `Json.DefaultMaxDepth`)
      * @param maxCollectionSize
      *   maximum number of entries in maps, sets, or arrays (default `Json.DefaultMaxCollectionSize`)
      * @param maxLineSize
      *   the largest record the framer will accept (default `Json.Lines.DefaultMaxLineSize`)
      * @return
      *   a pipe emitting one result per record
      */
    def pipeResults[A](
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineSize: ByteSize = Json.Lines.DefaultMaxLineSize
    )(using Json, Schema[A], Tag[Emit[Chunk[Result[DecodeException, A]]]], Frame): Pipe[Byte, Result[DecodeException, A], Any] =
        Pipe:
            Loop(Json.Lines.Framer.init(maxLineSize)) { framer =>
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
                                // No record boundary was found for the pending bytes and no rewind can arrive on a
                                // finite input, so there is no point resuming: emit what was framed, report the
                                // breach, and stop. A breach with a boundary never reaches here: it arrives inside
                                // `results` as one failure element, and framing carried on past it.
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
      * @param maxLineSize
      *   the largest record the framer will accept (default `Json.Lines.DefaultMaxLineSize`)
      * @return
      *   a stream of decoded values, aborting with the first decode or framing failure
      */
    def read[A](
        path: Path,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineSize: ByteSize = Json.Lines.DefaultMaxLineSize
    )(using Json, Schema[A], Tag[Emit[Chunk[A]]], Frame): Stream[A, Scope & Sync & Abort[FileReadException | DecodeException]] =
        path.readBytesStream.into(pipe[A](maxDepth, maxCollectionSize, maxLineSize))

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
      * @param maxLineSize
      *   the largest record the framer will accept (default `Json.Lines.DefaultMaxLineSize`)
      * @return
      *   a stream of per-record results
      */
    def readResults[A](
        path: Path,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineSize: ByteSize = Json.Lines.DefaultMaxLineSize
    )(using
        Json,
        Schema[A],
        Tag[Emit[Chunk[Result[DecodeException, A]]]],
        Frame
    ): Stream[Result[DecodeException, A], Scope & Sync & Abort[FileReadException]] =
        path.readBytesStream.into(pipeResults[A](maxDepth, maxCollectionSize, maxLineSize))

    /** Streams a JSONL file's records, continuing to emit as records are appended.
      *
      * Reading is one continuous pass that switches from draining to polling once it reaches the end of the file, not a read concatenated
      * with a tail, so a record appended while the existing content is still being replayed is never missed. The file handle is registered
      * with the enclosing `Scope` and closed when that scope ends.
      *
      * Two things end the stream on their own, and neither is the file running out of records: the first undecodable record aborts it, and
      * so does the first record-size breach, including the oversized trailing residual that ends [[followResults]]. Short of those it runs
      * until the scope ends, so a consumer reading a well-formed log bounds it with `take`, `takeWhile`, or an interrupt.
      *
      * An unterminated trailing record is where this differs from [[read]], and it is the only place the two differ. [[pipe]] runs on a
      * finite input, so it finishes its framer at end of input and emits a final record that carries no newline. A follow stream never
      * reaches an end of input, so it holds those bytes as a pending partial and emits the record only once its newline arrives.
      *
      * `from` defaults to [[Path.Origin.Start]], which replays the file and then follows, because reading a live agent transcript wants
      * every record rather than only those written after the reader attached. [[Path.tailBytes]] defaults to [[Path.Origin.End]] instead,
      * because it is the byte-level view of a followed file and follow-only is what a byte-level tail means. The two are sibling drivers
      * over one polling loop, so neither default constrains the other.
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
      * @param maxLineSize
      *   the largest record the framer will accept (default `Json.Lines.DefaultMaxLineSize`)
      * @return
      *   a stream of decoded values that runs until interrupted, aborting with the first decode or framing failure
      */
    def follow[A](
        path: Path,
        from: Path.Origin = Path.Origin.Start,
        pollDelay: Duration = 100.millis,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineSize: ByteSize = Json.Lines.DefaultMaxLineSize
    )(using
        Json,
        Schema[A],
        Tag[Emit[Chunk[Result[DecodeException, A]]]],
        Tag[Emit[Chunk[A]]],
        Frame
    ): Stream[A, Scope & Async & Abort[FileReadException | DecodeException]] =
        followResults[A](path, from, pollDelay, maxDepth, maxCollectionSize, maxLineSize).flatMapChunk { results =>
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
      * A record exceeding `maxLineSize` yields one failure element and the stream carries on, as long as its terminator arrived: the
      * boundary is known, so framing skips the record and keeps following. That matters most here, because ending instead would let one
      * over-long line stop a follower on a live log permanently.
      *
      * The one framing failure with nothing to skip to is an oversized trailing residual, where no record boundary was found for the
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
      * @param maxLineSize
      *   the largest record the framer will accept (default `Json.Lines.DefaultMaxLineSize`)
      * @return
      *   a stream of per-record results that runs until interrupted, or until the pending bytes outgrow `maxLineSize`
      */
    def followResults[A](
        path: Path,
        from: Path.Origin = Path.Origin.Start,
        pollDelay: Duration = 100.millis,
        maxDepth: Int = Json.DefaultMaxDepth,
        maxCollectionSize: Int = Json.DefaultMaxCollectionSize,
        maxLineSize: ByteSize = Json.Lines.DefaultMaxLineSize
    )(using
        Json,
        Schema[A],
        Tag[Emit[Chunk[Result[DecodeException, A]]]],
        Frame
    ): Stream[Result[DecodeException, A], Scope & Async & Abort[FileReadException]] =
        // The framer is the follow loop's carried state rather than a pipe downstream of it, which is what
        // ties its lifetime to the file's: a rewind restores this initial state, so bytes framed before a
        // truncation cannot reach a record replayed after one.
        Path.follow[Result[DecodeException, A], Json.Lines.Framer](
            path,
            from,
            pollDelay,
            FollowBufferSize,
            Json.Lines.Framer.init(maxLineSize)
        ) { (framer, buffer, bytesRead) =>
            // The follow loop reuses `buffer` across reads, so the framer, which retains what it
            // cannot yet frame, is handed an array nothing else holds.
            val chunk = Span.fromUnsafe(java.util.Arrays.copyOf(buffer, bytesRead))
            frameChunk[A](framer, chunk, maxDepth, maxCollectionSize) match
                case Framing.Continued(results, advanced) => Path.Step.Continue(results, advanced)
                case Framing.Halted(results, breach)      =>
                    // Nothing can be framed from this file again, so the loop stops rather than polling
                    // bytes no framer can consume. `Path.Step.Stop` carries no state, which is what keeps
                    // the unusable post-breach framer out of reach: there is nowhere to put it.
                    Path.Step.Stop(results :+ Result.fail[DecodeException, A](breach))
            end match
        }
    end followResults

    /** Encodes a stream of values as JSONL bytes, one newline-terminated record per value.
      *
      * The inverse of [[pipe]]: `Jsonl.encode(values).into(Jsonl.pipe[A]())` yields back `values`. Encoding happens per chunk, so a chunk
      * of values becomes a chunk of bytes and nothing accumulates across chunks. That is what keeps a stream of any length bounded in
      * memory, here and in [[write]] and [[append]], which encode a chunk at a time in the same way.
      *
      * The newline terminates each record rather than each chunk, so the bytes are a function of the values alone and never of how the
      * upstream source chunked them. [[pipe]] holds the same property on the way in.
      *
      * @param values
      *   the values to encode, in order
      * @return
      *   a stream of UTF-8 JSONL bytes, empty for an empty input
      */
    def encode[A, S](values: Stream[A, S])(using Json, Schema[A], Tag[Emit[Chunk[A]]], Frame): Stream[Byte, S] =
        values.mapChunkPure { chunk =>
            // `Json.Lines.encodeAllBytes` sizes its output array once and copies each encoded value and its
            // newline into it exactly once, so a chunk costs one allocation of exactly the bytes it produces.
            // The array it returns is fresh and unshared, which is what makes handing it over without a copy safe.
            Chunk.fromNoCopy(Json.Lines.encodeAllBytes(chunk).toArrayUnsafe)
        }

    /** Writes a stream of values to a JSONL file, replacing whatever the file held.
      *
      * The file is emptied first, then each chunk of values is encoded and written as it arrives. Nothing buffers the whole stream, so a
      * file far larger than memory is written in the same space as a file of two records. The emptying does not wait for a chunk, so
      * writing an empty stream leaves an empty file rather than the old content.
      *
      * A failure part way through leaves the records already written on disk rather than removing the file. A JSONL file is a record log
      * and a prefix of one is still a valid log, so a consumer reads what completed instead of losing all of it to the record that did
      * not.
      *
      * @param path
      *   the file to write
      * @param values
      *   the values to write, in order
      * @param createFolders
      *   whether to create missing parent directories (default `true`)
      * @return
      *   unit once every value has been written
      */
    def write[A, S](path: Path, values: Stream[A, S], createFolders: Boolean = true)(
        using
        Json,
        Schema[A],
        Tag[Emit[Chunk[A]]],
        Frame
    ): Unit < (S & Sync & Abort[FileWriteException]) =
        writeAll(path, values, appending = false, createFolders)

    /** Appends a stream of values to a JSONL file, preserving whatever the file already held.
      *
      * [[write]] in every respect except that nothing is emptied first, which is what an append-only record log wants: each call adds its
      * records after the ones already there, and the file is created when it does not yet exist, including for an empty stream.
      *
      * Appending starts at the file's current end and writes no separator of its own, so a file whose last record has no trailing newline
      * gets the first appended record spliced onto that last record, producing one corrupt line where there were two. [[pipe]] accepts an
      * unterminated final record by design, so [[read]] reads such a file without complaint and the splice is only visible afterwards.
      * Everything this module writes ends in a newline, so this reaches a file produced elsewhere or truncated mid-record; append to one of
      * those only after terminating its last line.
      *
      * @param path
      *   the file to append to
      * @param values
      *   the values to append, in order
      * @param createFolders
      *   whether to create missing parent directories (default `true`)
      * @return
      *   unit once every value has been appended
      */
    def append[A, S](path: Path, values: Stream[A, S], createFolders: Boolean = true)(
        using
        Json,
        Schema[A],
        Tag[Emit[Chunk[A]]],
        Frame
    ): Unit < (S & Sync & Abort[FileWriteException]) =
        writeAll(path, values, appending = true, createFolders)

    /** Writes `values` as JSONL to `path`, folding the stream chunk by chunk.
      *
      * The one write implementation behind [[write]] and [[append]], which differ in `appending` alone.
      *
      * The file is opened before the first chunk is pulled, so its existence, and for [[write]] its emptiness, is a fact about the call
      * rather than about whether the stream produced anything. Missing parent directories are created when `createFolders` is set. One
      * handle is held for the whole fold rather than one per chunk, and it is finished before it is closed, so a failure part way through
      * keeps the records already written rather than removing the file.
      *
      * Why the fold is bracketed as it is, and which failures that bracketing does and does not reach, is recorded under "Bounded memory on
      * both directions" in `kyo-schema-json/CONTRIBUTING.md`. That is an invariant for maintainers rather than part of this contract.
      */
    private def writeAll[A, S](path: Path, values: Stream[A, S], appending: Boolean, createFolders: Boolean)(
        using
        Json,
        Schema[A],
        Tag[Emit[Chunk[A]]],
        Frame
    ): Unit < (S & Sync & Abort[FileWriteException]) =
        Sync.acquireReleaseWith(
            // Unsafe: `openWrite` is the only surface that hands back a channel a fold can keep writing to.
            // The handle never leaves this method, and the bracket below owns its release.
            Sync.Unsafe.defer(Abort.get(path.unsafe.openWrite(appending, Path.WriteOptions(createFolders = createFolders))))
        ) { handle =>
            // Unsafe: finishes and closes the write handle. `close` runs even when `finish` throws,
            // so a failing fsync reports itself without also leaking the handle it was fsyncing.
            Sync.Unsafe.defer {
                try handle.finish()
                finally handle.close()
            }
        } { handle =>
            Abort.run[Any] {
                values.foreachChunk { chunk =>
                    // Unsafe: the same handle, bridged straight back into Sync and Abort on every chunk.
                    // The Span `encodeAllBytes` just built is shared with nothing, so the Chunk takes its
                    // backing array rather than copying bytes that are about to be written and dropped.
                    Sync.Unsafe.defer {
                        Abort.get(handle.writeBytes(Chunk.fromNoCopy(Json.Lines.encodeAllBytes(chunk).toArrayUnsafe)))
                    }
                }
            }
        }.map(outcome => Abort.get(outcome.asInstanceOf[Result[Nothing, Unit]]))
    end writeAll

    /** Read buffer size for the follow drivers.
      *
      * 8 KB, so a record of any ordinary size arrives whole in one read and the framer seldom carries a partial across polls. This is
      * passed to the shared follow loop explicitly rather than inherited from [[Path.tailBytes]]'s own default, so the two are free to
      * differ and neither has to track the other.
      */
    private inline def FollowBufferSize: ByteSize = 8.kib

    /** What framing and decoding one chunk of JSONL bytes produced.
      *
      * The two cases of `Json.Lines.Framed`, with each framed record decoded. Naming them keeps each driver's handling of them a visible
      * decision, and keeps the framer that a halt leaves behind out of reach: the case that carries a halting breach carries no framer, so
      * there is no value to feed again by mistake. A record skipped for exceeding `maxLineSize` is not a halt and does not appear as one:
      * it is one failure inside `results`, sitting where the record sat, and framing carried on past it.
      */
    private enum Framing[A]:
        /** Every record the chunk resolved, and the framer that frames the next chunk. */
        case Continued[A](results: Chunk[Result[DecodeException, A]], framer: Json.Lines.Framer) extends Framing[A]

        /** Every record resolved before the pending bytes outgrew `maxLineSize`, and that breach. */
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
      *   [[Framing.Continued]] with the chunk's results and the advanced framer, or [[Framing.Halted]] when the pending bytes outgrew
      *   `maxLineSize` with no boundary to skip to, carrying the records resolved before the breach
      */
    private def frameChunk[A](
        framer: Json.Lines.Framer,
        chunk: Span[Byte],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): Framing[A] =
        framer.feed(chunk) match
            case Json.Lines.Framed.Continued(advanced, lines) =>
                Framing.Continued(decodeLines[A](lines, maxDepth, maxCollectionSize), advanced)
            case Json.Lines.Framed.Halted(lines, breach) => Framing.Halted(decodeLines[A](lines, maxDepth, maxCollectionSize), breach)
    end frameChunk

    /** Decodes every kept line and turns every skipped one into the failure a consumer sees in its place.
      *
      * One pass over the lines in the order the framer resolved them, so a skipped record's failure lands between the results of the
      * records around it. That ordering is what makes "one failure element per bad record" true of a size breach as well as a decode
      * failure, and it is what the strict drivers' `splitAtFailure` reads to find the prefix before a failure of either kind.
      */
    private def decodeLines[A](
        lines: Chunk[Json.Lines.Line],
        maxDepth: Int,
        maxCollectionSize: Int
    )(using Json, Schema[A], Frame): Chunk[Result[DecodeException, A]] =
        lines.map {
            case Json.Lines.Line.Kept(record)    => Json.Lines.decodeRecord[A](record, maxDepth, maxCollectionSize)
            case Json.Lines.Line.Skipped(breach) => Result.fail(breach)
        }
    end decodeLines

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
