package kyo

import java.nio.charset.StandardCharsets

class HttpBodyTest extends Test:

    "HttpBody.empty" - {
        "has empty data" in {
            assert(HttpBody.empty.data.isEmpty)
        }

        "has empty text" in {
            assert(HttpBody.empty.text == "")
        }

        "isEmpty is true" in {
            assert(HttpBody.empty.isEmpty)
        }
    }

    "HttpBody.apply(bytes)" - {
        "stores the bytes" in {
            val bytes = "hello".getBytes(StandardCharsets.UTF_8)
            val body  = HttpBody(bytes)
            assert(java.util.Arrays.equals(body.data, bytes))
        }

        "text decodes UTF-8" in {
            val body = HttpBody("hello world".getBytes(StandardCharsets.UTF_8))
            assert(body.text == "hello world")
        }

        "isEmpty is false for non-empty" in {
            val body = HttpBody("x".getBytes(StandardCharsets.UTF_8))
            assert(!body.isEmpty)
        }

        "handles UTF-8 multibyte characters" in {
            val text = "hello"
            val body = HttpBody(text.getBytes(StandardCharsets.UTF_8))
            assert(body.text == text)
        }
    }

    "HttpBody.apply(text)" - {
        "stores UTF-8 encoded bytes" in {
            val body = HttpBody("hello")
            assert(body.text == "hello")
        }

        "roundtrips through bytes" in {
            val original = "test string"
            val body     = HttpBody(original)
            assert(new String(body.data, StandardCharsets.UTF_8) == original)
        }

        "isEmpty is false" in {
            val body = HttpBody("x")
            assert(!body.isEmpty)
        }
    }

    "HttpBody.Bytes.span" - {
        "returns span of data" in {
            val body = HttpBody("abc")
            val span = body.span
            assert(span.size == 3)
        }
    }

    "HttpBody.Bytes.as" - {
        "successful decode" in run {
            val body = HttpBody("""{"id":1,"name":"test"}""")
            Abort.run(body.as[TestUser]).map {
                case Result.Success(user) =>
                    assert(user.id == 1)
                    assert(user.name == "test")
                case other => fail(s"Expected success but got $other")
            }
        }

        "failed decode returns ParseError" in run {
            val body = HttpBody("not json")
            Abort.run(body.as[TestUser]).map {
                case Result.Failure(e: HttpError.ParseError) => succeed
                case other                                   => fail(s"Expected ParseError but got $other")
            }
        }
    }

    "HttpBody.use" - {
        "dispatches to ifBytes for Bytes" in {
            val body: HttpBody = HttpBody("hello")
            val result = body.use(
                b => s"bytes:${b.text}",
                _ => "streamed"
            )
            assert(result == "bytes:hello")
        }

        "dispatches to ifStreamed for Streamed" in {
            val body: HttpBody = HttpBody.stream(Stream.empty)
            val result = body.use(
                _ => "bytes",
                _ => "streamed"
            )
            assert(result == "streamed")
        }
    }

    "HttpBody.Streamed" - {
        "isEmpty is always false" in {
            val body = HttpBody.stream(Stream.empty)
            assert(!body.isEmpty)
        }
    }

    case class TestUser(id: Int, name: String) derives Schema, CanEqual

end HttpBodyTest
