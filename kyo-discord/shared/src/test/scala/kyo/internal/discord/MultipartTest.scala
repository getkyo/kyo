package kyo.internal.discord

import java.nio.charset.StandardCharsets.UTF_8
import kyo.*

class MultipartTest extends kyo.test.Test[Any]:

    private def text(bytes: Span[Byte]): String = new String(bytes.toArray, UTF_8)

    private def file(name: String, content: String, contentType: Maybe[String] = Absent)(using Frame): Discord.File =
        Discord.File.init(name, Span.from(content.getBytes(UTF_8)), contentType.map(t => kyo.mime.MediaType.parse(t).getOrThrow))
            .getOrThrow

    "payload_json comes first, then files[n] in order, each part with its disposition and type" in {
        val body = Multipart.encode("""{"content":"hi"}""", Chunk(file("a.png", "PNG", Present("image/png")), file("b.txt", "B")))
        assert(body.contentType == "multipart/form-data; boundary=kyo-discord-0")
        assert(text(body.bytes) ==
            "--kyo-discord-0\r\n" +
            "Content-Disposition: form-data; name=\"payload_json\"\r\n" +
            "Content-Type: application/json\r\n\r\n" +
            "{\"content\":\"hi\"}\r\n" +
            "--kyo-discord-0\r\n" +
            "Content-Disposition: form-data; name=\"files[0]\"; filename=\"a.png\"\r\n" +
            "Content-Type: image/png\r\n\r\n" +
            "PNG\r\n" +
            "--kyo-discord-0\r\n" +
            "Content-Disposition: form-data; name=\"files[1]\"; filename=\"b.txt\"\r\n\r\n" +
            "B\r\n" +
            "--kyo-discord-0--\r\n")
    }

    "a part holding a line that starts with the boundary's delimiter moves the boundary past it" in {
        val body = Multipart.encode("{}", Chunk(file("c.txt", "x\r\n--kyo-discord-0\r\ny")))
        assert(body.contentType == "multipart/form-data; boundary=kyo-discord-1")
        assert(text(body.bytes).endsWith("x\r\n--kyo-discord-0\r\ny\r\n--kyo-discord-1--\r\n"))
    }

    "a file name outside ASCII is written as UTF-8 in the quoted filename" in {
        val body = Multipart.encode("{}", Chunk(file("résumé.pdf", "%PDF")))
        assert(text(body.bytes).contains("Content-Disposition: form-data; name=\"files[0]\"; filename=\"résumé.pdf\"\r\n"))
    }

end MultipartTest
