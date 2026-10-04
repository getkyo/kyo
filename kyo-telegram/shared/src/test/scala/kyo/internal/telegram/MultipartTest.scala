package kyo.internal.telegram

import kyo.*
import kyo.Structure.Value
import kyo.internal.charset.Utf8

class MultipartTest extends kyo.test.Test[Any]:

    private def text(body: Multipart.Body): String = Utf8.decode(body.bytes)

    private def boundaryOf(body: Multipart.Body): String = body.contentType.stripPrefix("multipart/form-data; boundary=")

    "each parameter is a part, a JSON object is its JSON text, and a file part carries its name and type" in {
        Multipart.encode(
            Value.Record(Chunk(
                "chat_id"      -> Value.Integer(5L),
                "caption"      -> Value.Str("hi"),
                "reply_markup" -> Value.Record(Chunk("remove_keyboard" -> Value.Bool(true)))
            )),
            Chunk("photo" -> Telegram.InputFile.Upload("p.png", Utf8.encode("PNG"), Present("image/png")))
        ).map { body =>
            val b = boundaryOf(body)
            assert(text(body) ==
                s"--$b\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n5\r\n" +
                s"--$b\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\nhi\r\n" +
                s"--$b\r\nContent-Disposition: form-data; name=\"reply_markup\"\r\n\r\n{\"remove_keyboard\":true}\r\n" +
                s"--$b\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"p.png\"\r\nContent-Type: image/png\r\n\r\nPNG\r\n" +
                s"--$b--\r\n")
        }
    }

    "the boundary is kyo-telegram- and 32 random ASCII letters and digits, drawn anew for each body" in {
        val upload = Chunk("f" -> Telegram.InputFile.Upload("n", Span.from(Array[Byte](1))))
        Kyo.fill(2)(Multipart.encode(Value.Record(Chunk.empty), upload)).map { bodies =>
            val boundaries = bodies.map(boundaryOf)
            assert(boundaries.map(b =>
                b.startsWith("kyo-telegram-") && b.length == 45 &&
                    b.drop(13).forall(c =>
                        (c >= 'a' && c <= 'z') ||
                            (c >= 'A' && c <= 'Z') ||
                            (c >= '0' && c <= '9')
                    )
            ) == Chunk(true, true))
            assert(boundaries.distinct.size == 2)
        }
    }

    "a quote, CR or LF in a file name is percent-encoded so it cannot end the header" in {
        Multipart.encode(
            Value.Record(Chunk.empty),
            Chunk("document" -> Telegram.InputFile.Upload("a\"b\r\nc", Span.from(Array[Byte](1))))
        ).map {
            body =>
                assert(text(body).contains("filename=\"a%22b%0D%0Ac\"\r\n"))
        }
    }

    "a lone surrogate in a parameter or a file name is written as U+FFFD, not as '?'" in {
        Multipart.encode(
            Value.Record(Chunk("caption" -> Value.Str("a\uD800b"))),
            Chunk("f" -> Telegram.InputFile.Upload("n\uDC00.txt", Span.from(Array[Byte](1))))
        ).map { body =>
            assert(text(body).contains("\r\n\r\na�b\r\n"))
            assert(text(body).contains("filename=\"n�.txt\""))
        }
    }

    "the body's length is exactly the sum of its pieces, and the upload's bytes are carried whole" in {
        val content = Span.from(Array.tabulate[Byte](1000)(i => (i % 251).toByte))
        Multipart.encode(Value.Record(Chunk("a" -> Value.Str("b"))), Chunk("f" -> Telegram.InputFile.Upload("n", content))).map { body =>
            val b    = boundaryOf(body)
            val head =
                s"--$b\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\nb\r\n--$b\r\nContent-Disposition: form-data; name=\"f\"; filename=\"n\"\r\n\r\n"
            assert(body.bytes.size == head.length + 1000 + s"\r\n--$b--\r\n".length)
            assert(body.bytes.slice(head.length, head.length + 1000).is(content))
        }
    }

end MultipartTest
