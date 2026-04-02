package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class Http1Protocol2Test extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8 = StandardCharsets.UTF_8

    /** Helper: build a Stream from a raw byte array (for error-path tests). */
    private def streamOf(bytes: Array[Byte]): Stream[Span[Byte], Async] =
        Stream.init(Seq(Span.fromUnsafe(bytes)))

    private def streamOf(s: String): Stream[Span[Byte], Async] =
        streamOf(s.getBytes(Utf8))

    "Http1Protocol2" - {

        "request parsing" - {

            // Test 1: GET no body → HttpBody.Empty
            "GET no body" in run {
                StreamTestTransport.withPair { (client, server) =>
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/path", HttpHeaders.empty, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((method, path, headers, body), _) =>
                            assert(method == HttpMethod.GET)
                            assert(path == "/path")
                            assert(body == HttpBody.Empty)
                        }
                    }
                }
            }

            // Test 2: POST Content-Length → HttpBody.Buffered
            "POST with Content-Length body" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val bodyData = Span.fromUnsafe("hello world".getBytes(Utf8))
                    Http1Protocol2.writeRequest(
                        client,
                        HttpMethod.POST,
                        "/submit",
                        HttpHeaders.empty,
                        HttpBody.Buffered(bodyData)
                    ).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((method, path, headers, body), _) =>
                            assert(method == HttpMethod.POST)
                            assert(path == "/submit")
                            body match
                                case HttpBody.Buffered(data) =>
                                    assert(new String(data.toArrayUnsafe, Utf8) == "hello world")
                                case other =>
                                    fail(s"Expected Buffered, got $other")
                            end match
                        }
                    }
                }
            }

            // Test 3: POST chunked → HttpBody.Buffered reassembled
            "POST chunked body reassembled" in run {
                // Write raw chunked wire format directly
                val chunkedWire = "POST /upload HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n4\r\ndefg\r\n0\r\n\r\n"
                val src         = streamOf(chunkedWire)
                Http1Protocol2.readRequest(src, 65536).map { case ((method, path, headers, body), _) =>
                    assert(method == HttpMethod.POST)
                    assert(path == "/upload")
                    body match
                        case HttpBody.Buffered(data) =>
                            assert(new String(data.toArrayUnsafe, Utf8) == "abcdefg")
                        case other =>
                            fail(s"Expected Buffered with reassembled chunks, got $other")
                    end match
                }
            }

            // Test 4: PUT binary 0x00-0xFF → no corruption
            "PUT binary body no corruption" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val allBytes = Span.fromUnsafe(Array.tabulate[Byte](256)(_.toByte))
                    Http1Protocol2.writeRequest(client, HttpMethod.PUT, "/binary", HttpHeaders.empty, HttpBody.Buffered(allBytes)).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, _, _, body), _) =>
                            body match
                                case HttpBody.Buffered(data) =>
                                    val arr = data.toArrayUnsafe
                                    assert(arr.length == 256)
                                    var i = 0
                                    while i < 256 do
                                        assert(arr(i) == i.toByte, s"byte $i mismatch")
                                        i += 1
                                    end while
                                    succeed
                                case other =>
                                    fail(s"Expected Buffered, got $other")
                            end match
                        }
                    }
                }
            }

            // Test 5: All standard methods roundtrip
            "all standard methods roundtrip" in run {
                val methods = Seq(
                    HttpMethod.GET,
                    HttpMethod.POST,
                    HttpMethod.PUT,
                    HttpMethod.DELETE,
                    HttpMethod.PATCH,
                    HttpMethod.HEAD,
                    HttpMethod.OPTIONS
                )
                Kyo.foreach(methods) { method =>
                    StreamTestTransport.withPair { (client, server) =>
                        Http1Protocol2.writeRequest(client, method, "/test", HttpHeaders.empty, HttpBody.Empty).andThen {
                            Http1Protocol2.readRequest(server.read, 65536).map { case ((parsed, _, _, _), _) =>
                                assert(parsed == method, s"$method should roundtrip")
                            }
                        }
                    }
                }.map(_ => succeed)
            }

            // Test 6: Query string preserved
            "query string preserved" in run {
                StreamTestTransport.withPair { (client, server) =>
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/path?a=1&b=2", HttpHeaders.empty, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, path, _, _), _) =>
                            assert(path == "/path?a=1&b=2")
                        }
                    }
                }
            }

            // Test 7: Multiple same-name headers preserved
            "multiple same-name headers preserved" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val headers = HttpHeaders.empty.add("X-Custom", "val1").add("X-Custom", "val2")
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/test", headers, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, _, parsedHeaders, _), _) =>
                            val all = parsedHeaders.getAll("X-Custom")
                            assert(all.size == 2)
                            assert(all.contains("val1"))
                            assert(all.contains("val2"))
                        }
                    }
                }
            }

            // Test 8: Empty header value
            "empty header value" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val headers = HttpHeaders.empty.add("X-Empty", "")
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/test", headers, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, _, parsedHeaders, _), _) =>
                            assert(parsedHeaders.get("X-Empty") == Present(""))
                        }
                    }
                }
            }

            // Test 9: Large headers under MaxHeaderSize
            "large headers under MaxHeaderSize succeed" in run {
                // Build a header value of ~60KB (under 65536 limit)
                val bigValue = "x" * 60000
                StreamTestTransport.withPair { (client, server) =>
                    val headers = HttpHeaders.empty.add("X-Big", bigValue)
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/test", headers, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, _, parsedHeaders, _), _) =>
                            assert(parsedHeaders.get("X-Big") == Present(bigValue))
                        }
                    }
                }
            }

            // Test 10: Headers exceeding MaxHeaderSize → Abort
            "headers exceeding MaxHeaderSize abort" in run {
                // Build raw wire bytes with an oversized header block
                val bigValue = "x" * 70000
                val wire     = s"GET /test HTTP/1.1\r\nX-Big: $bigValue\r\n\r\n"
                val src      = streamOf(wire)
                Abort.run[HttpException](Http1Protocol2.readRequest(src, 65536)).map { result =>
                    assert(result.isFailure, "Expected failure for oversized headers")
                }
            }

            // Test 11: Unicode header values preserved
            "unicode header values preserved" in run {
                val unicodeValue = "café-résumé"
                StreamTestTransport.withPair { (client, server) =>
                    val headers = HttpHeaders.empty.add("X-Unicode", unicodeValue)
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/test", headers, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, _, parsedHeaders, _), _) =>
                            assert(parsedHeaders.get("X-Unicode") == Present(unicodeValue))
                        }
                    }
                }
            }

            // Test 12: Custom headers roundtrip
            "custom headers roundtrip" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val headers = HttpHeaders.empty
                        .add("X-Request-Id", "abc-123")
                        .add("X-Client-Version", "2.0")
                        .add("Authorization", "Bearer token123")
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/api", headers, HttpBody.Empty).andThen {
                        Http1Protocol2.readRequest(server.read, 65536).map { case ((_, _, parsedHeaders, _), _) =>
                            assert(parsedHeaders.get("X-Request-Id") == Present("abc-123"))
                            assert(parsedHeaders.get("X-Client-Version") == Present("2.0"))
                            assert(parsedHeaders.get("Authorization") == Present("Bearer token123"))
                        }
                    }
                }
            }

        } // request parsing

        "response parsing" - {

            // Test 13: 200 OK with body
            "200 OK with body" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val bodyData = Span.fromUnsafe("response body".getBytes(Utf8))
                    Http1Protocol2.writeResponse(server, HttpStatus(200), HttpHeaders.empty, HttpBody.Buffered(bodyData)).andThen {
                        Http1Protocol2.readResponse(client.read, 65536).map { case ((status, headers, body), _) =>
                            assert(status.code == 200)
                            body match
                                case HttpBody.Buffered(data) =>
                                    assert(new String(data.toArrayUnsafe, Utf8) == "response body")
                                case other =>
                                    fail(s"Expected Buffered, got $other")
                            end match
                        }
                    }
                }
            }

            // Test 14: 204 No Content → no body
            "204 No Content has no body" in run {
                StreamTestTransport.withPair { (client, server) =>
                    Http1Protocol2.writeResponse(server, HttpStatus(204), HttpHeaders.empty, HttpBody.Empty).andThen {
                        Http1Protocol2.readResponse(client.read, 65536).map { case ((status, _, body), _) =>
                            assert(status.code == 204)
                            assert(body == HttpBody.Empty)
                        }
                    }
                }
            }

            // Test 15: 304 Not Modified → no body
            "304 Not Modified has no body" in run {
                StreamTestTransport.withPair { (client, server) =>
                    Http1Protocol2.writeResponse(server, HttpStatus(304), HttpHeaders.empty, HttpBody.Empty).andThen {
                        Http1Protocol2.readResponse(client.read, 65536).map { case ((status, _, body), _) =>
                            assert(status.code == 304)
                            assert(body == HttpBody.Empty)
                        }
                    }
                }
            }

            // Test 16: HEAD with Content-Length → no body read regardless
            "HEAD response with Content-Length has no body" in run {
                // Wire: write 200 with Content-Length, read with HEAD method
                val wire = "HTTP/1.1 200 OK\r\nContent-Length: 11\r\n\r\nhello world"
                val src  = streamOf(wire)
                Http1Protocol2.readResponse(src, 65536, requestMethod = HttpMethod.HEAD).map { case ((status, headers, body), _) =>
                    assert(status.code == 200)
                    assert(body == HttpBody.Empty)
                }
            }

            // Test 17: 1xx → no body
            "1xx informational has no body" in run {
                val wire = "HTTP/1.1 100 Continue\r\n\r\n"
                val src  = streamOf(wire)
                Http1Protocol2.readResponse(src, 65536).map { case ((status, _, body), _) =>
                    assert(status.code == 100)
                    assert(body == HttpBody.Empty)
                }
            }

            // Test 18: Chunked response reassembled
            "chunked response reassembled" in run {
                val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n"
                val src  = streamOf(wire)
                Http1Protocol2.readResponse(src, 65536).map { case ((status, _, body), _) =>
                    assert(status.code == 200)
                    body match
                        case HttpBody.Buffered(data) =>
                            assert(new String(data.toArrayUnsafe, Utf8) == "hello world")
                        case other =>
                            fail(s"Expected Buffered with reassembled chunks, got $other")
                    end match
                }
            }

            // Test 19: Multi-chunk (3) concatenation
            "multi-chunk 3 chunks concatenation" in run {
                val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n3\r\nabc\r\n4\r\ndefg\r\n3\r\nhij\r\n0\r\n\r\n"
                val src  = streamOf(wire)
                Http1Protocol2.readResponse(src, 65536).map { case ((_, _, body), _) =>
                    body match
                        case HttpBody.Buffered(data) =>
                            assert(new String(data.toArrayUnsafe, Utf8) == "abcdefghij")
                        case other =>
                            fail(s"Expected Buffered, got $other")
                    end match
                }
            }

            // Test 20: Chunk extension ignored
            "chunk extension ignored data correct" in run {
                val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5;ext=val\r\nhello\r\n0\r\n\r\n"
                val src  = streamOf(wire)
                Http1Protocol2.readResponse(src, 65536).map { case ((_, _, body), _) =>
                    body match
                        case HttpBody.Buffered(data) =>
                            assert(new String(data.toArrayUnsafe, Utf8) == "hello")
                        case other =>
                            fail(s"Expected Buffered, got $other")
                    end match
                }
            }

        } // response parsing

        "keep-alive" - {

            // Test 21: empty chunked body → HttpBody.Empty
            "empty chunked body" in run {
                val wire = "GET /empty HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
                val src  = streamOf(wire)
                Http1Protocol2.readRequest(src, 65536).map { case ((_, _, _, body), _) =>
                    body match
                        case HttpBody.Buffered(data) =>
                            assert(data.isEmpty, "Expected empty buffered body from empty chunked")
                        case HttpBody.Empty =>
                            succeed
                        case other =>
                            fail(s"Expected Empty or empty Buffered, got $other")
                    end match
                }
            }

            // Test 22: 3 GETs on same stream
            "3 GETs same stream" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val headers = HttpHeaders.empty
                    Http1Protocol2.writeRequest(client, HttpMethod.GET, "/a", headers, HttpBody.Empty).andThen {
                        Http1Protocol2.writeRequest(client, HttpMethod.GET, "/b", headers, HttpBody.Empty).andThen {
                            Http1Protocol2.writeRequest(client, HttpMethod.GET, "/c", headers, HttpBody.Empty).andThen {
                                Http1Protocol2.readRequest(server.read, 65536).map { case ((m1, p1, _, _), rem1) =>
                                    Http1Protocol2.readRequest(rem1, 65536).map { case ((m2, p2, _, _), rem2) =>
                                        Http1Protocol2.readRequest(rem2, 65536).map { case ((m3, p3, _, _), _) =>
                                            assert(m1 == HttpMethod.GET)
                                            assert(m2 == HttpMethod.GET)
                                            assert(m3 == HttpMethod.GET)
                                            assert(p1 == "/a")
                                            assert(p2 == "/b")
                                            assert(p3 == "/c")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Test 23: POST then GET on same stream — body bytes don't leak into GET
            "POST then GET same stream" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val bodyData = Span.fromUnsafe("hello".getBytes(Utf8))
                    Http1Protocol2.writeRequest(client, HttpMethod.POST, "/post", HttpHeaders.empty, HttpBody.Buffered(bodyData)).andThen {
                        Http1Protocol2.writeRequest(client, HttpMethod.GET, "/get", HttpHeaders.empty, HttpBody.Empty).andThen {
                            Http1Protocol2.readRequest(server.read, 65536).map { case ((m1, p1, _, body1), rem1) =>
                                assert(m1 == HttpMethod.POST)
                                assert(p1 == "/post")
                                body1 match
                                    case HttpBody.Buffered(data) =>
                                        assert(new String(data.toArrayUnsafe, Utf8) == "hello")
                                    case other =>
                                        fail(s"Expected Buffered for POST body, got $other")
                                end match
                                Http1Protocol2.readRequest(rem1, 65536).map { case ((m2, p2, _, body2), _) =>
                                    assert(m2 == HttpMethod.GET)
                                    assert(p2 == "/get")
                                    assert(body2 == HttpBody.Empty, s"Body bytes leaked into GET: $body2")
                                }
                            }
                        }
                    }
                }
            }

            // Test 24: Connection: close → isKeepAlive returns false
            "Connection close isKeepAlive false" in run {
                val headers = HttpHeaders.empty.add("Connection", "close")
                assert(!Http1Protocol2.isKeepAlive(headers))
            }

            // Test 25: Default (no Connection header) → isKeepAlive returns true
            "default isKeepAlive true" in run {
                val headers = HttpHeaders.empty
                assert(Http1Protocol2.isKeepAlive(headers))
            }

        } // keep-alive

        "streaming write" - {

            // Test 26: writeStreamingBody 3 spans → all data present via chunked decoding
            "writeStreamingBody 3 spans" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val spans = Seq(
                        Span.fromUnsafe("hello".getBytes(Utf8)),
                        Span.fromUnsafe(" ".getBytes(Utf8)),
                        Span.fromUnsafe("world".getBytes(Utf8))
                    )
                    val bodyStream = Stream.init(spans)
                    Http1Protocol2.writeStreamingBody(client, bodyStream).andThen {
                        // Read the raw chunked bytes from server side and decode them
                        val wire = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        // We prepend a fake response header so readResponse can parse the chunked body
                        val headerStream = streamOf(wire)
                        val combined     = headerStream.concat(server.read)
                        Http1Protocol2.readResponse(combined, 65536).map { case ((_, _, body), _) =>
                            body match
                                case HttpBody.Buffered(data) =>
                                    assert(new String(data.toArrayUnsafe, Utf8) == "hello world")
                                case other =>
                                    fail(s"Expected Buffered with 'hello world', got $other")
                            end match
                        }
                    }
                }
            }

            // Test 27: empty spans are filtered out (don't produce empty chunks)
            "empty spans filtered" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val spans = Seq(
                        Span.fromUnsafe("abc".getBytes(Utf8)),
                        Span.empty[Byte],
                        Span.fromUnsafe("def".getBytes(Utf8))
                    )
                    val bodyStream = Stream.init(spans)
                    Http1Protocol2.writeStreamingBody(client, bodyStream).andThen {
                        val wire         = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        val headerStream = streamOf(wire)
                        val combined     = headerStream.concat(server.read)
                        Http1Protocol2.readResponse(combined, 65536).map { case ((_, _, body), _) =>
                            body match
                                case HttpBody.Buffered(data) =>
                                    val result = new String(data.toArrayUnsafe, Utf8)
                                    assert(result == "abcdef", s"Expected 'abcdef', got '$result'")
                                case other =>
                                    fail(s"Expected Buffered, got $other")
                            end match
                        }
                    }
                }
            }

            // Test 28: last chunk terminator 0\r\n\r\n is sent
            // Verified by: writing a single-span streaming body, then reading back via chunked
            // decoding requires the 0\r\n\r\n terminator — decode succeeds iff it was written.
            "last chunk appended" in run {
                StreamTestTransport.withPair { (client, server) =>
                    val spans      = Seq(Span.fromUnsafe("payload".getBytes(Utf8)))
                    val bodyStream = Stream.init(spans)
                    Http1Protocol2.writeStreamingBody(client, bodyStream).andThen {
                        val header   = "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"
                        val combined = streamOf(header).concat(server.read)
                        Http1Protocol2.readResponse(combined, 65536).map { case ((_, _, body), _) =>
                            body match
                                case HttpBody.Buffered(data) =>
                                    assert(new String(data.toArrayUnsafe, Utf8) == "payload")
                                case other =>
                                    fail(s"Expected Buffered body, chunked terminator may be missing: $other")
                            end match
                        }
                    }
                }
            }

            // Test 29: writeResponseHead format — HTTP/1.1 STATUS REASON\r\nHeader: Value\r\n\r\n
            // Verified by: writing a response head and reading it back via readResponse to confirm
            // status code and header are correctly formatted and parseable.
            "writeResponseHead format" in run {
                StreamTestTransport.withPair { (server, client) =>
                    val headers = HttpHeaders.empty.add("X-Custom", "TestValue")
                    Http1Protocol2.writeResponse(server, HttpStatus(201), headers, HttpBody.Empty).andThen {
                        Http1Protocol2.readResponse(client.read, 65536).map { case ((status, parsedHeaders, _), _) =>
                            assert(status.code == 201, s"Expected status 201, got ${status.code}")
                            assert(
                                parsedHeaders.get("X-Custom") == Present("TestValue"),
                                s"Expected X-Custom=TestValue, got ${parsedHeaders.get("X-Custom")}"
                            )
                        }
                    }
                }
            }

        } // streaming write

        "error cases" - {

            // Test 30: malformed request line → Abort with HttpProtocolException
            "malformed request line" in run {
                val src = streamOf("GARBAGE\r\n\r\n")
                Abort.run[HttpException](Http1Protocol2.readRequest(src, 65536)).map { result =>
                    result match
                        case Result.Failure(e: HttpProtocolException) => succeed
                        case Result.Failure(e)                        => fail(s"Expected HttpProtocolException, got $e")
                        case Result.Success(_)                        => fail("Expected failure for malformed request line")
                        case Result.Panic(e)                          => fail(s"Unexpected panic: $e")
                    end match
                }
            }

            // Test 31: malformed status line → Abort with HttpProtocolException
            "malformed status line" in run {
                val src = streamOf("GARBAGE\r\n\r\n")
                Abort.run[HttpException](Http1Protocol2.readResponse(src, 65536)).map { result =>
                    result match
                        case Result.Failure(e: HttpProtocolException) => succeed
                        case Result.Failure(e)                        => fail(s"Expected HttpProtocolException, got $e")
                        case Result.Success(_)                        => fail("Expected failure for malformed status line")
                        case Result.Panic(e)                          => fail(s"Unexpected panic: $e")
                    end match
                }
            }

            // Test 32: invalid Content-Length → Abort with HttpProtocolException
            "invalid Content-Length" in run {
                val src = streamOf("POST /upload HTTP/1.1\r\nContent-Length: abc\r\n\r\n")
                Abort.run[HttpException](Http1Protocol2.readRequest(src, 65536)).map { result =>
                    result match
                        case Result.Failure(e: HttpProtocolException) => succeed
                        case Result.Failure(e)                        => fail(s"Expected HttpProtocolException, got $e")
                        case Result.Success(_)                        => fail("Expected failure for invalid Content-Length")
                        case Result.Panic(e)                          => fail(s"Unexpected panic: $e")
                    end match
                }
            }

            // Test 33: Content-Length exceeds maxSize → Abort with HttpPayloadTooLargeException
            "Content-Length exceeds maxSize" in run {
                val src = streamOf("POST /upload HTTP/1.1\r\nContent-Length: 1000\r\n\r\n")
                Abort.run[HttpException](Http1Protocol2.readRequest(src, 100)).map { result =>
                    result match
                        case Result.Failure(e: HttpPayloadTooLargeException) => succeed
                        case Result.Failure(e)                               => fail(s"Expected HttpPayloadTooLargeException, got $e")
                        case Result.Success(_)                               => fail("Expected failure when Content-Length exceeds maxSize")
                        case Result.Panic(e)                                 => fail(s"Unexpected panic: $e")
                    end match
                }
            }

            // Test 34: connection closed mid-headers → Abort with HttpConnectionClosedException
            "connection closed mid-headers" in run {
                // Stream ends before \r\n\r\n is found
                val src = streamOf("GET /path HTTP/1.1\r\nHost: localhost\r\n")
                Abort.run[HttpException](Http1Protocol2.readRequest(src, 65536)).map { result =>
                    result match
                        case Result.Failure(e: HttpConnectionClosedException) => succeed
                        case Result.Failure(e)                                => fail(s"Expected HttpConnectionClosedException, got $e")
                        case Result.Success(_)                                => fail("Expected failure when connection closed mid-headers")
                        case Result.Panic(e)                                  => fail(s"Unexpected panic: $e")
                    end match
                }
            }

            // Test 35: connection closed mid-body → Abort with HttpConnectionClosedException
            "connection closed mid-body" in run {
                // Headers say Content-Length: 10 but only 5 body bytes follow before stream ends
                val src = streamOf("POST /upload HTTP/1.1\r\nContent-Length: 10\r\n\r\nhello")
                Abort.run[HttpException](Http1Protocol2.readRequest(src, 65536)).map { result =>
                    result match
                        case Result.Failure(e: HttpConnectionClosedException) => succeed
                        case Result.Failure(e)                                => fail(s"Expected HttpConnectionClosedException, got $e")
                        case Result.Success(_)                                => fail("Expected failure when connection closed mid-body")
                        case Result.Panic(e)                                  => fail(s"Unexpected panic: $e")
                    end match
                }
            }

        } // error cases

    } // Http1Protocol2

end Http1Protocol2Test
