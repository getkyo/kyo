package kyo.internal

import kyo.*
import kyo.Container.LogEntry

class FrameAssemblerTest extends kyo.Test:

    private def frame(streamType: Int, payload: Array[Byte]): Array[Byte] =
        val header = new Array[Byte](8)
        header(0) = streamType.toByte
        val size = payload.length
        header(4) = ((size >> 24) & 0xff).toByte
        header(5) = ((size >> 16) & 0xff).toByte
        header(6) = ((size >> 8) & 0xff).toByte
        header(7) = (size & 0xff).toByte
        header ++ payload
    end frame

    private def stdoutFrame(payload: String): Array[Byte] =
        frame(1, payload.getBytes("UTF-8"))

    private def stderrFrame(payload: String): Array[Byte] =
        frame(2, payload.getBytes("UTF-8"))

    private def span(arr: Array[Byte]): Span[Byte] = Span.from(arr)

    private def runPipe(spans: Span[Byte]*)(using Frame): Chunk[(String, LogEntry.Source)] < Sync =
        Stream.init(spans).into(FrameAssembler.pipe).run

    "FrameAssembler" - {

        // --- single-chunk: complete frames ---

        "empty input emits nothing" in run {
            runPipe().map(r => assert(r.isEmpty))
        }

        "single complete stdout frame" in run {
            runPipe(span(stdoutFrame("hello"))).map(r => assert(r == Chunk(("hello", LogEntry.Source.Stdout))))
        }

        "single complete stderr frame" in run {
            runPipe(span(stderrFrame("oops"))).map(r => assert(r == Chunk(("oops", LogEntry.Source.Stderr))))
        }

        "frame with empty payload (size = 0) emits empty content" in run {
            runPipe(span(stdoutFrame(""))).map(r => assert(r == Chunk(("", LogEntry.Source.Stdout))))
        }

        "multiple complete frames in one chunk emit in order" in run {
            val arr = stdoutFrame("a") ++ stderrFrame("b") ++ stdoutFrame("c")
            runPipe(span(arr)).map { r =>
                assert(r == Chunk(
                    ("a", LogEntry.Source.Stdout),
                    ("b", LogEntry.Source.Stderr),
                    ("c", LogEntry.Source.Stdout)
                ))
            }
        }

        "UTF-8 multi-byte payload in single complete frame" in run {
            runPipe(span(stdoutFrame("héllo→世界"))).map(r => assert(r == Chunk(("héllo→世界", LogEntry.Source.Stdout))))
        }

        // Documents current behaviour: stream-type byte 0 (or any non-2 value) maps to stdout.
        "stream-type byte 0 maps to stdout" in run {
            runPipe(span(frame(0, "x".getBytes("UTF-8")))).map(r => assert(r == Chunk(("x", LogEntry.Source.Stdout))))
        }

        // --- cross-chunk: payload split ---

        "frame whose payload is split across two chunks emits exactly once after the second chunk" in run {
            val full     = stdoutFrame("0123456789" * 10) // 8 + 100 = 108 bytes total
            val (c1, c2) = full.splitAt(58)               // header + 50 payload | 50 payload
            runPipe(span(c1), span(c2)).map(r => assert(r == Chunk(("0123456789" * 10, LogEntry.Source.Stdout))))
        }

        // --- cross-chunk: header split ---

        "frame whose 8-byte header is split across two chunks emits exactly once after the second chunk" in run {
            val full     = stdoutFrame("hello")
            val (c1, c2) = full.splitAt(4) // 4 bytes of header | rest of header + payload
            runPipe(span(c1), span(c2)).map(r => assert(r == Chunk(("hello", LogEntry.Source.Stdout))))
        }

        // --- cross-chunk: 3-way split ---

        "frame split across three chunks emits exactly once after the third chunk" in run {
            val full = stdoutFrame("0123456789" * 10) // 108 bytes
            val c1   = full.slice(0, 40)              // header + 32 payload bytes
            val c2   = full.slice(40, 76)             // 36 payload bytes
            val c3   = full.slice(76, full.length)    // 32 payload bytes
            runPipe(span(c1), span(c2), span(c3)).map(r => assert(r == Chunk(("0123456789" * 10, LogEntry.Source.Stdout))))
        }

        // --- cross-chunk: multi-frame, last partial ---

        "multiple frames where the last is partial emit complete first, partial after rest arrives" in run {
            val a        = stdoutFrame("alpha")
            val b        = stdoutFrame("beta")
            val (b1, b2) = b.splitAt(8) // b's header | b's payload
            runPipe(span(a ++ b1), span(b2)).map { r =>
                assert(r == Chunk(("alpha", LogEntry.Source.Stdout), ("beta", LogEntry.Source.Stdout)))
            }
        }

        "two consecutive frames with partial header straddling chunks" in run {
            val a        = stdoutFrame("alpha")
            val b        = stdoutFrame("beta")
            val combined = a ++ b
            val (c1, c2) = combined.splitAt(a.length + 4) // full a + 4 bytes of b's header | rest
            runPipe(span(c1), span(c2)).map { r =>
                assert(r == Chunk(("alpha", LogEntry.Source.Stdout), ("beta", LogEntry.Source.Stdout)))
            }
        }

        "stderr frame split across chunks preserves source" in run {
            val full     = stderrFrame("error message that spans two chunks")
            val (c1, c2) = full.splitAt(20)
            runPipe(span(c1), span(c2)).map { r =>
                assert(r == Chunk(("error message that spans two chunks", LogEntry.Source.Stderr)))
            }
        }

        // --- cross-chunk: large payload split into many small chunks ---

        "16 KB payload fed one byte at a time emits exactly one frame at the end" in run {
            val payload = ("x" * 16384).getBytes("UTF-8")
            val full    = frame(1, payload)
            val spans   = full.map(b => span(Array(b))).toSeq
            Stream.init(spans).into(FrameAssembler.pipe).run.map { r =>
                assert(r == Chunk(("x" * 16384, LogEntry.Source.Stdout)))
            }
        }

        // --- cross-chunk: UTF-8 multi-byte char straddling boundary ---

        "UTF-8 multi-byte char split across chunks reassembles correctly" in run {
            val full     = stdoutFrame("→") // 8-byte header + 3-byte UTF-8 char
            val (c1, c2) = full.splitAt(9)  // header + 1 byte of '→'
            runPipe(span(c1), span(c2)).map(r => assert(r == Chunk(("→", LogEntry.Source.Stdout))))
        }
    }

end FrameAssemblerTest
