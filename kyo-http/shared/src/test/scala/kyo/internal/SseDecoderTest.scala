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
    }

end SseDecoderTest
