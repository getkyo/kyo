package kyo.internal

import kyo.*

/** Pure-functional line stitcher for newline-delimited byte streams.
  *
  * Lines are split on the `\n` byte and only complete lines are decoded as UTF-8. A `\n` byte never occurs inside a multi-byte UTF-8 sequence,
  * so a character whose bytes straddle a chunk (or a Docker frame) boundary is reassembled before it is decoded, never corrupted. The bytes
  * after the last `\n` are carried to the next chunk.
  *
  * Provides two [[Pipe]] variants:
  *
  *   - [[pipe]]: single-source, takes `Stream[Span[Byte]]` and emits lines.
  *   - [[partitionedPipe]]: multi-source, takes `Stream[(Span[Byte], K)]` and threads one carry per key (for example one per
  *     `LogEntry.Source` for interleaved stdout/stderr demux output), emitting `(line, key)` pairs.
  *
  * At the end of the stream a non-empty unterminated last line is emitted, so output that does not end in a newline is not lost. Empty lines
  * (consecutive `\n`s) are preserved as empty strings; callers that want to drop them filter downstream.
  */
object LineAssembler:

    private val Newline: Byte = '\n'.toByte

    def pipe(using
        Tag[Poll[Chunk[Span[Byte]]]],
        Tag[Emit[Chunk[String]]],
        Frame
    ): Pipe[Span[Byte], String, Any] =
        Pipe:
            Loop(Span.empty[Byte]) { carry =>
                Poll.andMap[Chunk[Span[Byte]]] {
                    case Absent =>
                        if carry.isEmpty then Loop.done
                        else Emit.valueWith(Chunk(decode(carry)))(Loop.done)
                    case Present(spans) =>
                        val (lines, rest) = splitLines(spans.foldLeft(carry)((acc, s) => acc ++ s))
                        Emit.valueWith(lines)(Loop.continue(rest))
                }
            }

    def partitionedPipe[K](using
        Tag[Poll[Chunk[(Span[Byte], K)]]],
        Tag[Emit[Chunk[(String, K)]]],
        Frame
    ): Pipe[(Span[Byte], K), (String, K), Any] =
        Pipe:
            // The keys in order of first appearance, so the end-of-stream flush is deterministic.
            Loop(Map.empty[K, Span[Byte]], Chunk.empty[K]) { (carries, order) =>
                Poll.andMap[Chunk[(Span[Byte], K)]] {
                    case Absent =>
                        val trailing = order.flatMap(key => carries.get(key).filter(_.nonEmpty).map(bytes => (decode(bytes), key)).toList)
                        Emit.valueWith(trailing)(Loop.done[Map[K, Span[Byte]], Chunk[K], Unit](()))
                    case Present(pairs) =>
                        val (nextCarries, nextOrder, emitted) =
                            pairs.foldLeft((carries, order, Chunk.empty[(String, K)])) {
                                case ((cs, ord, acc), (bytes, key)) =>
                                    val known         = cs.contains(key)
                                    val (lines, rest) = splitLines(cs.getOrElse(key, Span.empty[Byte]) ++ bytes)
                                    (cs.updated(key, rest), if known then ord else ord.append(key), acc ++ lines.map(line => (line, key)))
                            }
                        Emit.valueWith(emitted)(Loop.continue(nextCarries, nextOrder))
                }
            }

    /** Split `bytes` on the `\n` byte, returning the decoded complete lines and the bytes after the last `\n`. */
    private def splitLines(bytes: Span[Byte]): (Chunk[String], Span[Byte]) =
        val arr   = bytes.toArrayUnsafe
        val lines = Chunk.newBuilder[String]
        @scala.annotation.tailrec
        def loop(start: Int, i: Int): Int =
            if i >= arr.length then start
            else if arr(i) == Newline then
                lines.addOne(new String(arr, start, i - start, java.nio.charset.StandardCharsets.UTF_8))
                loop(i + 1, i + 1)
            else loop(start, i + 1)
        val restStart = loop(0, 0)
        (lines.result(), if restStart == 0 then bytes else bytes.slice(restStart, arr.length))
    end splitLines

    private def decode(bytes: Span[Byte]): String =
        new String(bytes.toArrayUnsafe, java.nio.charset.StandardCharsets.UTF_8)

end LineAssembler
