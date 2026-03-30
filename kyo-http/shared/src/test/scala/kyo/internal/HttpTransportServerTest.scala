package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class HttpTransportServerTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    private def withServer(handlers: HttpHandler[?, ?, ?]*)(
        f: (TestTransport, HttpBackend.Binding) => Assertion < (Async & Abort[HttpException])
    )(using Frame): Assertion < (Async & Abort[HttpException] & Scope) =
        val transport = new TestTransport
        val server    = new HttpTransportServer(transport, Http1Protocol)
        server.bind(handlers, HttpServerConfig.default).map { binding =>
            f(transport, binding)
        }
    end withServer

    private def sendRaw(
        transport: TestTransport,
        port: Int,
        method: HttpMethod,
        path: String,
        extraHeaders: HttpHeaders = HttpHeaders.empty,
        body: String = ""
    )(using Frame): (HttpStatus, HttpHeaders, HttpBody) < (Async & Abort[HttpException]) =
        transport.connect("127.0.0.1", port, tls = false).map { conn =>
            transport.stream(conn).map { stream =>
                val hdrs = extraHeaders
                    .add("Host", "localhost")
                    .add("Content-Length", body.length.toString)
                Http1Protocol.writeRequestHead(stream, method, path, hdrs).andThen {
                    (if body.nonEmpty then Http1Protocol.writeBody(stream, Span.fromUnsafe(body.getBytes(Utf8)))
                     else Kyo.unit).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, method)
                    }
                }
            }
        }

    // ── Routing ─────────────────────────────────────────────────

    "GET /existing → 200" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.GET, "/hello").map((s, _, _) => assert(s.code == 200)) }
    }

    "GET /nonexistent → 404" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.GET, "/notfound").map((s, _, _) => assert(s.code == 404)) }
    }

    "POST to GET-only → 405" in run {
        val route   = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.POST, "/hello").map((s, _, _) => assert(s.code == 405)) }
    }

    "path parameters extracted" in run {
        val route   = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("id")).response(_.bodyText)
        val handler = route.handler(req => HttpResponse.ok.addField("body", s"user-${req.fields.id}"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/users/42").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("user-42"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "nested path segments" in run {
        val route   = HttpRoute.getRaw("api" / "v1" / "users").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "nested"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/api/v1/users").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("nested"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "trailing slash routing" in run {
        val route   = HttpRoute.getRaw("trail").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "ok"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/trail").map((s, _, _) => assert(s.code == 200))
        }
    }

    // ── Keep-alive ──────────────────────────────────────────────

    "multiple requests on same connection" in run {
        val route   = HttpRoute.getRaw("ping").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "pong"))
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    val hdrs = HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ping", hdrs).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s1, _, _) =>
                            assert(s1.code == 200)
                            Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ping", hdrs).andThen {
                                Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s2, _, _) =>
                                    assert(s2.code == 200)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "three requests on same connection" in run {
        val route   = HttpRoute.getRaw("ka").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "alive"))
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    val hdrs = HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ka", hdrs).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s1, _, _) =>
                            assert(s1.code == 200)
                            Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ka", hdrs).andThen {
                                Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s2, _, _) =>
                                    assert(s2.code == 200)
                                    Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/ka", hdrs).andThen {
                                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s3, _, _) =>
                                            assert(s3.code == 200)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    "Connection: close stops keep-alive" in run {
        val route   = HttpRoute.getRaw("once").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "done"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/once", HttpHeaders.empty.add("Connection", "close")).map { (s, _, _) =>
                assert(s.code == 200)
            }
        }
    }

    // ── Error handling ──────────────────────────────────────────

    "typed error → mapped status code" in run {
        val route   = HttpRoute.getRaw("fail").response(_.bodyText).error[String](HttpStatus(422))
        val handler = route.handler(_ => Abort.fail("bad input"))
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.GET, "/fail").map((s, _, _) => assert(s.code == 422)) }
    }

    "typed error body contains error data" in run {
        val route   = HttpRoute.getRaw("err").response(_.bodyText).error[String](HttpStatus(422))
        val handler = route.handler(_ => Abort.fail("validation failed"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/err").map { (s, _, body) =>
                assert(s.code == 422)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("validation failed"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "unhandled error → 500" in run {
        val route   = HttpRoute.getRaw("crash").response(_.bodyText)
        val handler = route.handler(_ => Abort.fail("unexpected"))
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.GET, "/crash").map((s, _, _) => assert(s.code == 500)) }
    }

    "typed error with different status codes" in run {
        val route   = HttpRoute.getRaw("conflict").response(_.bodyText).error[String](HttpStatus(409))
        val handler = route.handler(_ => Abort.fail("conflict"))
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.GET, "/conflict").map((s, _, _) => assert(s.code == 409)) }
    }

    // ── Response body types ─────────────────────────────────────

    "text body" in run {
        val route   = HttpRoute.getRaw("text").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "hello world"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/text").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("hello world"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "empty body" in run {
        val route   = HttpRoute.getRaw("empty")
        val handler = route.handler(_ => HttpResponse.ok)
        withServer(handler) { (t, b) => sendRaw(t, b.port, HttpMethod.GET, "/empty").map((s, _, _) => assert(s.code == 200)) }
    }

    "JSON body" in run {
        case class Data(value: Int) derives Json, CanEqual
        val route   = HttpRoute.getRaw("json").response(_.bodyJson[Data])
        val handler = route.handler(_ => HttpResponse.ok.addField("body", Data(42)))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/json").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("42"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "large text body (1KB)" in run {
        val largeText = "x" * 1024
        val route     = HttpRoute.getRaw("large").response(_.bodyText)
        val handler   = route.handler(_ => HttpResponse.ok.addField("body", largeText))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/large").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) =>
                        val text = new String(d.toArrayUnsafe, Utf8)
                        assert(text.contains(largeText))
                    case _ => fail("Expected Buffered")
                end match
            }
        }
    }

    "unicode in response body" in run {
        val unicode = "café-日本語-🎉"
        val route   = HttpRoute.getRaw("unicode").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", unicode))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/unicode").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) =>
                        val text = new String(d.toArrayUnsafe, Utf8)
                        assert(text.contains(unicode))
                    case _ => fail("Expected Buffered")
                end match
            }
        }
    }

    // ── Request body ────────────────────────────────────────────

    "POST with text body" in run {
        val route   = HttpRoute.postText("echo")
        val handler = route.handler(req => HttpResponse.ok(req.fields.body))
        withServer(handler) { (t, b) =>
            sendRaw(
                t,
                b.port,
                HttpMethod.POST,
                "/echo",
                HttpHeaders.empty.add("Content-Type", "text/plain; charset=utf-8"),
                "hello post"
            ).map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("hello post"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "POST with JSON body" in run {
        case class Input(name: String) derives Json, CanEqual
        case class Output(greeting: String) derives Json, CanEqual
        val route   = HttpRoute.postJson[Output, Input]("greet")
        val handler = route.handler(req => HttpResponse.ok.addField("body", Output(s"Hello ${req.fields.body.name}")))
        withServer(handler) { (t, b) =>
            sendRaw(
                t,
                b.port,
                HttpMethod.POST,
                "/greet",
                HttpHeaders.empty.add("Content-Type", "application/json"),
                """{"name":"World"}"""
            ).map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) =>
                        val text = new String(d.toArrayUnsafe, Utf8)
                        assert(text.contains("Hello World"))
                    case _ => fail("Expected Buffered")
                end match
            }
        }
    }

    "PUT with text body" in run {
        val route   = HttpRoute.putText("update")
        val handler = route.handler(req => HttpResponse.ok(s"updated:${req.fields.body}"))
        withServer(handler) { (t, b) =>
            sendRaw(
                t,
                b.port,
                HttpMethod.PUT,
                "/update",
                HttpHeaders.empty.add("Content-Type", "text/plain; charset=utf-8"),
                "new value"
            ).map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("updated:new value"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    // ── Streaming response ──────────────────────────────────────

    "streaming response" in run {
        val route = HttpRoute.getRaw("stream").response(_.bodyStream)
        val handler = route.handler { _ =>
            HttpResponse.ok.addField("body", Stream.init(Seq(Span.fromUnsafe("hello".getBytes(Utf8)))))
        }
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    Http1Protocol.writeRequestHead(
                        stream,
                        HttpMethod.GET,
                        "/stream",
                        HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    ).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s, _, body) =>
                            assert(s.code == 200)
                            body match
                                case HttpBody.Streamed(chunks) =>
                                    chunks.run.map { spans =>
                                        assert(spans.map(s => new String(s.toArrayUnsafe, Utf8)).mkString.contains("hello"))
                                    }
                                case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("hello"))
                                case _                    => fail("Expected streaming body")
                            end match
                        }
                    }
                }
            }
        }
    }

    "streaming response with multiple chunks" in run {
        val route = HttpRoute.getRaw("multi-stream").response(_.bodyStream)
        val handler = route.handler { _ =>
            HttpResponse.ok.addField(
                "body",
                Stream.init(Seq(
                    Span.fromUnsafe("chunk1-".getBytes(Utf8)),
                    Span.fromUnsafe("chunk2-".getBytes(Utf8)),
                    Span.fromUnsafe("chunk3".getBytes(Utf8))
                ))
            )
        }
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    Http1Protocol.writeRequestHead(
                        stream,
                        HttpMethod.GET,
                        "/multi-stream",
                        HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    ).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s, _, body) =>
                            assert(s.code == 200)
                            body match
                                case HttpBody.Streamed(chunks) =>
                                    chunks.run.map { spans =>
                                        val text = spans.map(s => new String(s.toArrayUnsafe, Utf8)).mkString
                                        assert(text.contains("chunk1-"))
                                        assert(text.contains("chunk2-"))
                                        assert(text.contains("chunk3"))
                                    }
                                case HttpBody.Buffered(d) =>
                                    val text = new String(d.toArrayUnsafe, Utf8)
                                    assert(text.contains("chunk1-"))
                                    assert(text.contains("chunk3"))
                                case _ => fail("Expected body")
                            end match
                        }
                    }
                }
            }
        }
    }

    // ── OPTIONS ─────────────────────────────────────────────────

    "OPTIONS returns 204 with Allow header" in run {
        val route   = HttpRoute.getRaw("resource").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "data"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.OPTIONS, "/resource").map { (s, headers, _) =>
                assert(s.code == 204)
                assert(headers.get("Allow").isDefined)
            }
        }
    }

    "OPTIONS on keep-alive connection" in run {
        val route   = HttpRoute.getRaw("opt-ka").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "data"))
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    val hdrs = HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    // OPTIONS first
                    Http1Protocol.writeRequestHead(stream, HttpMethod.OPTIONS, "/opt-ka", hdrs).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.OPTIONS).map { (s1, _, _) =>
                            assert(s1.code == 204)
                            // Then GET on same connection
                            Http1Protocol.writeRequestHead(stream, HttpMethod.GET, "/opt-ka", hdrs).andThen {
                                Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s2, _, _) =>
                                    assert(s2.code == 200)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Multiple handlers ───────────────────────────────────────

    "multiple handlers on different paths" in run {
        val r1 = HttpRoute.getRaw("a").response(_.bodyText)
        val r2 = HttpRoute.getRaw("b").response(_.bodyText)
        val h1 = r1.handler(_ => HttpResponse.ok.addField("body", "alpha"))
        val h2 = r2.handler(_ => HttpResponse.ok.addField("body", "beta"))
        withServer(h1, h2) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/a").map { (s1, _, _) =>
                assert(s1.code == 200)
                sendRaw(t, b.port, HttpMethod.GET, "/b").map((s2, _, _) => assert(s2.code == 200))
            }
        }
    }

    "multiple handlers different methods" in run {
        val r1 = HttpRoute.getRaw("resource").response(_.bodyText)
        val r2 = HttpRoute.postRaw("resource").response(_.bodyText)
        val h1 = r1.handler(_ => HttpResponse.ok.addField("body", "got"))
        val h2 = r2.handler(_ => HttpResponse.created.addField("body", "posted"))
        withServer(h1, h2) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/resource").map { (s1, _, _) =>
                assert(s1.code == 200)
                sendRaw(t, b.port, HttpMethod.POST, "/resource").map { (s2, _, _) =>
                    assert(s2.code == 201)
                }
            }
        }
    }

    // ── Query parameters ────────────────────────────────────────

    "query parameters passed to handler" in run {
        val route   = HttpRoute.getRaw("search").request(_.query[String]("q")).response(_.bodyText)
        val handler = route.handler(req => HttpResponse.ok.addField("body", s"found:${req.fields.q}"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/search?q=hello").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("found:hello"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    "multiple query parameters" in run {
        val route = HttpRoute.getRaw("multi")
            .request(_.query[String]("a"))
            .request(_.query[String]("b"))
            .response(_.bodyText)
        val handler = route.handler(req => HttpResponse.ok.addField("body", s"${req.fields.a}-${req.fields.b}"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/multi?a=foo&b=bar").map { (s, _, body) =>
                assert(s.code == 200)
                body match
                    case HttpBody.Buffered(d) => assert(new String(d.toArrayUnsafe, Utf8).contains("foo-bar"))
                    case _                    => fail("Expected Buffered")
            }
        }
    }

    // ── HEAD ────────────────────────────────────────────────────

    "HEAD returns headers but no body" in run {
        val route   = HttpRoute.getRaw("head-test").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "invisible"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.HEAD, "/head-test").map { (s, _, body) =>
                assert(s.code == 200)
                assert(body == HttpBody.Empty)
            }
        }
    }

    "HEAD response includes Content-Length" in run {
        val route   = HttpRoute.getRaw("head-len").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.ok.addField("body", "12345"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.HEAD, "/head-len").map { (s, headers, body) =>
                assert(s.code == 200)
                assert(body == HttpBody.Empty)
                assert(headers.get("Content-Length").isDefined)
            }
        }
    }

    // ── Response headers ────────────────────────────────────────

    "custom response headers" in run {
        val route = HttpRoute.getRaw("custom-hdr").response(_.bodyText)
        val handler = route.handler(_ =>
            HttpResponse.ok
                .addField("body", "ok")
                .addHeader("X-Custom", "test-value")
        )
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.GET, "/custom-hdr").map { (s, headers, _) =>
                assert(s.code == 200)
                assert(headers.get("X-Custom").contains("test-value"))
            }
        }
    }

    // ── Status codes ────────────────────────────────────────────

    "201 Created status" in run {
        val route   = HttpRoute.postRaw("items").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.created.addField("body", "created"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.POST, "/items").map((s, _, _) => assert(s.code == 201))
        }
    }

    "202 Accepted status" in run {
        val route   = HttpRoute.postRaw("async").response(_.bodyText)
        val handler = route.handler(_ => HttpResponse.accepted.addField("body", "queued"))
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.POST, "/async").map((s, _, _) => assert(s.code == 202))
        }
    }

    "204 No Content status" in run {
        val route   = HttpRoute.deleteRaw("item")
        val handler = route.handler(_ => HttpResponse.noContent)
        withServer(handler) { (t, b) =>
            sendRaw(t, b.port, HttpMethod.DELETE, "/item").map((s, _, _) => assert(s.code == 204))
        }
    }

    "streaming response with delayed finite chunks" in run {
        val route = HttpRoute.getRaw("delayed-stream").response(_.bodyStream)
        val handler = route.handler { _ =>
            val delayedStream = Stream[Span[Byte], Async] {
                Emit.valueWith(Chunk(Span.fromUnsafe("chunk1\n".getBytes(Utf8)))) {
                    Async.sleep(100.millis).andThen {
                        Emit.valueWith(Chunk(Span.fromUnsafe("chunk2\n".getBytes(Utf8)))) {
                            Async.sleep(100.millis).andThen {
                                Emit.valueWith(Chunk(Span.fromUnsafe("chunk3\n".getBytes(Utf8))))(())
                            }
                        }
                    }
                }
            }
            HttpResponse.ok.addField("body", delayedStream)
        }
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    Http1Protocol.writeRequestHead(
                        stream,
                        HttpMethod.GET,
                        "/delayed-stream",
                        HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    ).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s, _, body) =>
                            assert(s.code == 200)
                            body match
                                case HttpBody.Streamed(chunks) =>
                                    chunks.take(2).run.map { spans =>
                                        val text = spans.map(s => new String(s.toArrayUnsafe, Utf8)).mkString
                                        assert(text.contains("chunk1"))
                                        assert(text.contains("chunk2"))
                                    }
                                case _ => fail("Expected streaming body")
                            end match
                        }
                    }
                }
            }
        }
    }

    "streaming response with delayed infinite chunks and take(2)" in run {
        var counter = 0
        val route   = HttpRoute.getRaw("infinite-delayed").response(_.bodyStream)
        val handler = route.handler { _ =>
            counter = 0
            val infiniteStream = Stream[Span[Byte], Async] {
                Loop.foreach {
                    Async.sleep(50.millis).andThen {
                        counter += 1
                        Emit.valueWith(Chunk(Span.fromUnsafe(s"event-$counter\n".getBytes(Utf8))))(Loop.continue[Unit])
                    }
                }
            }
            HttpResponse.ok.addField("body", infiniteStream)
        }
        withServer(handler) { (transport, binding) =>
            transport.connect("127.0.0.1", binding.port, tls = false).map { conn =>
                transport.stream(conn).map { stream =>
                    Http1Protocol.writeRequestHead(
                        stream,
                        HttpMethod.GET,
                        "/infinite-delayed",
                        HttpHeaders.empty.add("Host", "localhost").add("Content-Length", "0")
                    ).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (s, _, body) =>
                            assert(s.code == 200)
                            body match
                                case HttpBody.Streamed(chunks) =>
                                    chunks.take(2).run.map { spans =>
                                        assert(spans.size == 2)
                                    }
                                case _ => fail("Expected streaming body")
                            end match
                        }
                    }
                }
            }
        }
    }

end HttpTransportServerTest
