package kyo.internal

import kyo.*

/** Pure-functional line stitcher for newline-delimited text streams.
  *
  * Provides two [[Pipe]] variants:
  *
  *   - [[pipe]] — single-source: takes `Stream[String]`, emits complete `\n`-terminated lines, retaining trailing partial text in a
  *     residual `String` threaded through `Loop`.
  *   - [[partitionedPipe]] — multi-source: takes `Stream[(String, K)]`, threads one residual per key (e.g. one per `LogEntry.Source` for
  *     interleaved stdout/stderr demux output).
  *
  * Stream end discards any partial trailing text — matches the prior `LineAssembler` callers' behaviour of dropping the residual.
  */
object LineAssembler:

    def pipe(using
        Tag[Poll[Chunk[String]]],
        Tag[Emit[Chunk[String]]],
        Frame
    ): Pipe[String, String, Any] =
        Pipe:
            Loop("") { residual =>
                Poll.andMap[Chunk[String]] {
                    case Absent => Loop.done
                    case Present(strings) =>
                        val (newResidual, lines) = strings.foldLeft((residual, Chunk.empty[String])) {
                            case ((r, acc), s) =>
                                val (newLines, rest) = splitLines(r + s)
                                (rest, acc ++ newLines)
                        }
                        Emit.valueWith(lines)(Loop.continue(newResidual))
                }
            }

    def partitionedPipe[K](using
        Tag[Poll[Chunk[(String, K)]]],
        Tag[Emit[Chunk[(String, K)]]],
        Frame
    ): Pipe[(String, K), (String, K), Any] =
        Pipe:
            Loop(Map.empty[K, String]) { state =>
                Poll.andMap[Chunk[(String, K)]] {
                    case Absent => Loop.done
                    case Present(pairs) =>
                        val (newState, emitted) = pairs.foldLeft((state, Chunk.empty[(String, K)])) {
                            case ((st, acc), (content, key)) =>
                                val combined          = st.getOrElse(key, "") + content
                                val (lines, residual) = splitLines(combined)
                                (st.updated(key, residual), acc ++ lines.map(l => (l, key)))
                        }
                        Emit.valueWith(emitted)(Loop.continue(newState))
                }
            }

    /** Split `text` on '\n', returning (complete lines, trailing residual). Empty lines (consecutive `\n`s) are preserved as empty strings;
      * callers that want to drop them filter downstream.
      */
    private def splitLines(text: String): (Chunk[String], String) =
        if text.isEmpty then (Chunk.empty, "")
        else
            val parts    = text.split("\n", -1) // -1 keeps trailing empties so split-after-final-\n yields ""
            val lines    = Chunk.from(parts.init.toIndexedSeq)
            val residual = parts.last
            (lines, residual)
    end splitLines

end LineAssembler
