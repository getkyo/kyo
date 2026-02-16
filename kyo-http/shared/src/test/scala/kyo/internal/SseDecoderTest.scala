package kyo.internal

import kyo.*

class SseDecoderTest extends Test:

    case class Msg(text: String) derives Schema, CanEqual

    "SseDecoder" - {

        "parses LF-delimited events" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = "data: {\"text\":\"hello\"}\n\ndata: {\"text\":\"world\"}\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 2)
            assert(events(0).data == Msg("hello"))
            assert(events(1).data == Msg("world"))
        }

        "parses CRLF-delimited events" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = "data: {\"text\":\"hello\"}\r\n\r\ndata: {\"text\":\"world\"}\r\n\r\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 2, s"Expected 2 events but got ${events.size}")
            assert(events(0).data == Msg("hello"))
            assert(events(1).data == Msg("world"))
        }

        "parses CR-delimited events" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = "data: {\"text\":\"hello\"}\r\rdata: {\"text\":\"world\"}\r\r"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 2, s"Expected 2 events but got ${events.size}")
            assert(events(0).data == Msg("hello"))
            assert(events(1).data == Msg("world"))
        }

        "parses event with event and id fields" in {
            val decoder = new SseDecoder[String](Schema[String])
            val input   = "event: greeting\nid: 1\ndata: \"hello\"\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).data == "hello")
            assert(events(0).event == Present("greeting"))
            assert(events(0).id == Present("1"))
        }

        "parses event with retry field" in {
            val decoder = new SseDecoder[String](Schema[String])
            val input   = "retry: 5000\ndata: \"test\"\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).retry == Present(Duration.fromUnits(5000, Duration.Units.Millis)))
        }

        "handles partial events across chunks" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val part1   = "data: {\"text\":\""
            val part2   = "hello\"}\n\n"
            val events1 = decoder.decode(Span.fromUnsafe(part1.getBytes("UTF-8")))
            assert(events1.isEmpty)
            val events2 = decoder.decode(Span.fromUnsafe(part2.getBytes("UTF-8")))
            assert(events2.size == 1)
            assert(events2(0).data == Msg("hello"))
        }

        "handles CRLF split across chunks" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val part1   = "data: {\"text\":\"hello\"}\r\n\r"
            val part2   = "\ndata: {\"text\":\"world\"}\r\n\r\n"
            val events1 = decoder.decode(Span.fromUnsafe(part1.getBytes("UTF-8")))
            val events2 = decoder.decode(Span.fromUnsafe(part2.getBytes("UTF-8")))
            val all     = events1 ++ events2
            assert(all.size == 2, s"Expected 2 events but got ${all.size}")
            assert(all(0).data == Msg("hello"))
            assert(all(1).data == Msg("world"))
        }

        "empty chunk returns no events" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val events  = decoder.decode(Span.fromUnsafe(Array.empty[Byte]))
            assert(events.isEmpty)
        }

        "multi-byte UTF-8 characters in data" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = "data: {\"text\":\"café 中文 🌍\"}\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).data == Msg("café 中文 🌍"))
        }

        "multi-byte UTF-8 split across chunk boundary" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            // é is 0xC3 0xA9 in UTF-8 — split between those two bytes
            val fullInput = "data: {\"text\":\"caf\u00e9\"}\n\n"
            val fullBytes = fullInput.getBytes("UTF-8")
            // Find the é bytes (0xC3 0xA9) and split between them
            val cafeIdx    = fullInput.indexOf("café")
            val utf8Prefix = "data: {\"text\":\"caf".getBytes("UTF-8")
            // Split after first byte of é (0xC3)
            val splitAt = utf8Prefix.length + 1
            val chunk1  = java.util.Arrays.copyOfRange(fullBytes, 0, splitAt)
            val chunk2  = java.util.Arrays.copyOfRange(fullBytes, splitAt, fullBytes.length)
            val events1 = decoder.decode(Span.fromUnsafe(chunk1))
            assert(events1.isEmpty)
            val events2 = decoder.decode(Span.fromUnsafe(chunk2))
            assert(events2.size == 1)
            assert(events2(0).data == Msg("café"))
        }

        "comment lines are ignored" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = ": this is a comment\ndata: {\"text\":\"hello\"}\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).data == Msg("hello"))
        }

        "multi-line data field joins with newline" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            // SSE spec: multiple data: lines in one event are joined with \n
            // Split a JSON object across two data: lines
            val input  = "data: {\"text\":\ndata: \"hello\"}\n\n"
            val events = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).data == Msg("hello"))
        }

        "unknown field names are ignored" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = "custom: something\ndata: {\"text\":\"hello\"}\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).data == Msg("hello"))
        }

        "many small events in a single chunk" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val sb      = new StringBuilder
            (1 to 100).foreach { i =>
                sb.append(s"data: {\"text\":\"msg$i\"}\n\n")
            }
            val events = decoder.decode(Span.fromUnsafe(sb.toString.getBytes("UTF-8")))
            assert(events.size == 100)
            assert(events(0).data == Msg("msg1"))
            assert(events(99).data == Msg("msg100"))
        }

        "large event data" in {
            val decoder   = new SseDecoder[Msg](Schema[Msg])
            val largeText = "x" * 100000
            val input     = s"data: {\"text\":\"$largeText\"}\n\n"
            val events    = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.size == 1)
            assert(events(0).data == Msg(largeText))
        }

        "event block with no data lines produces no event" in {
            val decoder = new SseDecoder[Msg](Schema[Msg])
            val input   = "event: keepalive\nid: 5\n\n"
            val events  = decoder.decode(Span.fromUnsafe(input.getBytes("UTF-8")))
            assert(events.isEmpty)
        }
    }

end SseDecoderTest
