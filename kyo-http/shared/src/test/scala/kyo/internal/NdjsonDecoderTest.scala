package kyo.internal

import kyo.*

class NdjsonDecoderTest extends Test:
    import AllowUnsafe.embrace.danger

    "single complete line" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val result  = decoder.decode(Span.from("42\n".getBytes("UTF-8")))
        assert(result == Result.succeed(Seq(42)))
    }

    "multiple lines in one chunk" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val result  = decoder.decode(Span.from("1\n2\n3\n".getBytes("UTF-8")))
        assert(result == Result.succeed(Seq(1, 2, 3)))
    }

    "line split across two chunks" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val r1      = decoder.decode(Span.from("4".getBytes("UTF-8")))
        assert(r1 == Result.succeed(Seq.empty[Int]))
        val r2 = decoder.decode(Span.from("2\n".getBytes("UTF-8")))
        assert(r2 == Result.succeed(Seq(42)))
    }

    "empty lines are ignored" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val result  = decoder.decode(Span.from("1\n\n\n2\n".getBytes("UTF-8")))
        assert(result == Result.succeed(Seq(1, 2)))
    }

    "no newline yet buffers data" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val result  = decoder.decode(Span.from("42".getBytes("UTF-8")))
        assert(result == Result.succeed(Seq.empty[Int]))
    }

    "trailing content without newline is buffered" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val r1      = decoder.decode(Span.from("1\n2".getBytes("UTF-8")))
        assert(r1 == Result.succeed(Seq(1)))
        val r2 = decoder.decode(Span.from("3\n".getBytes("UTF-8")))
        assert(r2 == Result.succeed(Seq(23)))
    }

    "string values" in {
        val decoder = NdjsonDecoder.init[String](Schema[String])
        val result  = decoder.decode(Span.from("\"hello\"\n\"world\"\n".getBytes("UTF-8")))
        assert(result == Result.succeed(Seq("hello", "world")))
    }

    "case class values" in {
        case class Item(id: Int, name: String) derives Schema, CanEqual
        val decoder = NdjsonDecoder.init[Item](Schema[Item])
        val result  = decoder.decode(Span.from("{\"id\":1,\"name\":\"a\"}\n{\"id\":2,\"name\":\"b\"}\n".getBytes("UTF-8")))
        assert(result == Result.succeed(Seq(Item(1, "a"), Item(2, "b"))))
    }

    "successive chunks accumulate correctly" in {
        val decoder = NdjsonDecoder.init[Int](Schema[Int])
        val r1      = decoder.decode(Span.from("10\n20\n".getBytes("UTF-8")))
        assert(r1 == Result.succeed(Seq(10, 20)))
        val r2 = decoder.decode(Span.from("30\n".getBytes("UTF-8")))
        assert(r2 == Result.succeed(Seq(30)))
    }

end NdjsonDecoderTest
