package kyo.internal.jsenv

import kyo.*

class ComFrameTest extends kyo.test.Test[Any]:

    private def bytes(values: Int*): Span[Byte] = Span.fromUnsafe(values.map(_.toByte).toArray)

    private def decodeAll(chunks: Seq[Span[Byte]]): Result[String, (Chunk[String], ComFrame.Decoder)] =
        chunks.foldLeft(Result.succeed((Chunk.empty[String], ComFrame.Decoder.empty)): Result[String, (Chunk[String], ComFrame.Decoder)]) {
            (acc, chunk) =>
                acc.flatMap { (messages, decoder) =>
                    decoder.feed(chunk).map((more, next) => (messages.concat(more), next))
                }
        }

    "encode writes a big-endian count of UTF-16 code units, then the units big-endian" in {
        assert(ComFrame.encode("hé").toArray.toSeq == bytes(0, 0, 0, 2, 0, 'h', 0, 0xe9).toArray.toSeq)
    }

    "encode of the empty message is a zero count" in {
        assert(ComFrame.encode("").toArray.toSeq == bytes(0, 0, 0, 0).toArray.toSeq)
    }

    "a frame split at every byte decodes to its message" in {
        val frame  = ComFrame.encode("split across chunks")
        val chunks = frame.toArray.toSeq.map(b => Span.fromUnsafe(Array(b)))
        assert(decodeAll(chunks).map(_._1) == Result.succeed(Chunk("split across chunks")))
    }

    "several frames in one chunk decode in order, keeping a partial frame for the next chunk" in {
        val first    = ComFrame.encode("one").toArray
        val second   = ComFrame.encode("two").toArray
        val third    = ComFrame.encode("three").toArray
        val combined = first ++ second ++ third.take(5)
        ComFrame.Decoder.empty.feed(Span.fromUnsafe(combined)) match
            case Result.Success((messages, decoder)) =>
                assert(messages == Chunk("one", "two"))
                assert(decoder.feed(Span.fromUnsafe(third.drop(5))).map(_._1) == Result.succeed(Chunk("three")))
            case other => fail(s"expected two messages and a partial frame, got $other")
        end match
    }

    "code units round-trip, unpaired surrogates and characters beyond the BMP included" in {
        val message = "a" + 0xd83d.toChar + 0xde00.toChar + "b" + 0xd800.toChar + "c" + 0.toChar + 0xffff.toChar
        assert(ComFrame.Decoder.empty.feed(ComFrame.encode(message)).map(_._1) == Result.succeed(Chunk(message)))
    }

    "a large message round-trips" in {
        val message = "x" * 200000 + "end"
        assert(ComFrame.Decoder.empty.feed(ComFrame.encode(message)).map(_._1) == Result.succeed(Chunk(message)))
    }

    "a negative length is a decode failure" in {
        assert(ComFrame.Decoder.empty.feed(bytes(0xff, 0xff, 0xff, 0xff)).isFailure)
    }

    "a length whose units have not arrived yet waits for them" in {
        ComFrame.Decoder.empty.feed(bytes(0x7f, 0xff, 0xff, 0xff, 0, 'a')) match
            case Result.Success((messages, decoder)) =>
                assert(messages.isEmpty)
                assert(decoder.pending.size == 6)
            case other => fail(s"expected no message yet, got $other")
    }

end ComFrameTest
