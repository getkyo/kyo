package kyo.internal

import kyo.*

class NdjsonDecoderTest extends Test:

    "single complete line" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val result  = decoder.decode(Span.from("42\n".getBytes("UTF-8")))
        assert(result == Seq(42))
    }

    "multiple lines in one chunk" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val result  = decoder.decode(Span.from("1\n2\n3\n".getBytes("UTF-8")))
        assert(result == Seq(1, 2, 3))
    }

    "line split across two chunks" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val r1      = decoder.decode(Span.from("4".getBytes("UTF-8")))
        assert(r1.isEmpty)
        val r2 = decoder.decode(Span.from("2\n".getBytes("UTF-8")))
        assert(r2 == Seq(42))
    }

    "empty lines are ignored" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val result  = decoder.decode(Span.from("1\n\n\n2\n".getBytes("UTF-8")))
        assert(result == Seq(1, 2))
    }

    "no newline yet buffers data" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val result  = decoder.decode(Span.from("42".getBytes("UTF-8")))
        assert(result.isEmpty)
    }

    "trailing content without newline is buffered" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val r1      = decoder.decode(Span.from("1\n2".getBytes("UTF-8")))
        assert(r1 == Seq(1))
        val r2 = decoder.decode(Span.from("3\n".getBytes("UTF-8")))
        assert(r2 == Seq(23))
    }

    "string values" in {
        val decoder = NdjsonDecoder[String](Schema[String])
        val result  = decoder.decode(Span.from("\"hello\"\n\"world\"\n".getBytes("UTF-8")))
        assert(result == Seq("hello", "world"))
    }

    "case class values" in {
        case class Item(id: Int, name: String) derives Schema, CanEqual
        val decoder = NdjsonDecoder[Item](Schema[Item])
        val result  = decoder.decode(Span.from("{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n".getBytes("UTF-8")))
        assert(result == Seq(Item(1, "a"), Item(2, "b")))
    }

    "successive chunks accumulate correctly" in {
        val decoder = NdjsonDecoder[Int](Schema[Int])
        val r1      = decoder.decode(Span.from("10\n20\n".getBytes("UTF-8")))
        assert(r1 == Seq(10, 20))
        val r2 = decoder.decode(Span.from("30\n".getBytes("UTF-8")))
        assert(r2 == Seq(30))
    }

end NdjsonDecoderTest
