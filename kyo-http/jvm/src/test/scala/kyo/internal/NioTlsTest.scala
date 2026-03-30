package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*

class NioTlsTest extends kyo.Test:

    given CanEqual[Any, Any] = CanEqual.derived

    private val Utf8      = StandardCharsets.UTF_8
    private val transport = new NioTransport

    // ── Basic TLS connectivity ──────────────────────────

    "TLS connect to real HTTPS endpoint" in run {
        Scope.run {
            transport.connect("httpbin.org", 443, tls = true).map { conn =>
                transport.stream(conn).map { stream =>
                    // Send a minimal HTTP/1.1 GET request
                    val request = "GET /get HTTP/1.1\r\nHost: httpbin.org\r\nConnection: close\r\n\r\n"
                    stream.write(Span.fromUnsafe(request.getBytes(Utf8))).andThen {
                        // Read response
                        val buf = new Array[Byte](4096)
                        stream.read(buf).map { n =>
                            assert(n > 0)
                            val response = new String(buf, 0, n, Utf8)
                            assert(response.startsWith("HTTP/1.1 200"))
                        }
                    }
                }
            }
        }
    }

    "TLS HTTP/1.1 via protocol layer" in run {
        Scope.run {
            transport.connect("httpbin.org", 443, tls = true).map { conn =>
                transport.stream(conn).map { stream =>
                    Http1Protocol.writeRequestHead(
                        stream,
                        HttpMethod.GET,
                        "/get",
                        HttpHeaders.empty.add("Host", "httpbin.org").add("Connection", "close")
                    ).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (status, _, _) =>
                            assert(status.code == 200)
                        }
                    }
                }
            }
        }
    }

    // ── Certificate validation ──────────────────────────

    "self-signed cert rejected by default" in run {
        Scope.run {
            Abort.run[HttpException] {
                transport.connect("self-signed.badssl.com", 443, tls = true)
            }.map { result =>
                assert(result.isPanic, s"Expected handshake failure, got: $result")
            }
        }
    }

    "expired cert rejected" in run {
        Scope.run {
            Abort.run[HttpException] {
                transport.connect("expired.badssl.com", 443, tls = true)
            }.map { result =>
                assert(result.isPanic, s"Expected handshake failure, got: $result")
            }
        }
    }

    "wrong hostname rejected" in run {
        Scope.run {
            Abort.run[HttpException] {
                transport.connect("wrong.host.badssl.com", 443, tls = true)
            }.map { result =>
                assert(result.isPanic, s"Expected handshake failure, got: $result")
            }
        }
    }

    // ── Connection lifecycle ────────────────────────────

    "TLS isAlive and closeNow" in run {
        Scope.run {
            transport.connect("httpbin.org", 443, tls = true).map { conn =>
                transport.isAlive(conn).map { alive =>
                    assert(alive)
                    transport.closeNow(conn).map { _ =>
                        transport.isAlive(conn).map { alive2 =>
                            assert(!alive2)
                        }
                    }
                }
            }
        }
    }

    // ── HTTP over TLS ───────────────────────────────────

    "HTTPS GET with response body" in run {
        Scope.run {
            transport.connect("httpbin.org", 443, tls = true).map { conn =>
                transport.stream(conn).map { stream =>
                    Http1Protocol.writeRequestHead(
                        stream,
                        HttpMethod.GET,
                        "/get",
                        HttpHeaders.empty.add("Host", "httpbin.org").add("Connection", "close").add("Content-Length", "0")
                    ).andThen {
                        Http1Protocol.readResponse(stream, Int.MaxValue, HttpMethod.GET).map { (status, _, body) =>
                            assert(status.code == 200)
                            body match
                                case HttpBody.Buffered(data) =>
                                    val text = new String(data.toArrayUnsafe, Utf8)
                                    assert(text.contains("httpbin"))
                                case _ => succeed // any body is fine
                            end match
                        }
                    }
                }
            }
        }
    }

    // ── Server TLS (self-signed, in-memory certs) ────────

    "TLS server + client roundtrip" in run {
        val serverTransport = new NioTransport(serverSslContext = Present(TlsTestHelper.serverSslContext))
        val clientTransport = new NioTransport(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        val server          = new HttpTransportServer(serverTransport, Http1Protocol)
        val client          = new HttpTransportClient(clientTransport, Http1Protocol)
        val route           = HttpRoute.getRaw("hello").response(_.bodyText)
        val handler         = route.handler(_ => HttpResponse.ok.addField("body", "world"))
        Scope.run {
            server.bind(
                Seq(handler),
                HttpServerConfig.default.port(0).host("localhost").tls(TlsConfig.default)
            ).map { binding =>
                val url = HttpUrl.parse(s"https://localhost:${binding.port}").getOrThrow
                client.connectWith(url, Absent) { conn =>
                    client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/hello"))) { resp =>
                        assert(resp.status.code == 200)
                        assert(resp.fields.body == "world")
                    }
                }
            }
        }
    }

    "TLS server + client keep-alive" in run {
        val serverTransport = new NioTransport(serverSslContext = Present(TlsTestHelper.serverSslContext))
        val clientTransport = new NioTransport(clientSslContext = Present(TlsTestHelper.trustAllSslContext))
        val server          = new HttpTransportServer(serverTransport, Http1Protocol)
        val client          = new HttpTransportClient(clientTransport, Http1Protocol)
        val route           = HttpRoute.getRaw("ping").response(_.bodyText)
        val handler         = route.handler(_ => HttpResponse.ok.addField("body", "pong"))
        Scope.run {
            server.bind(
                Seq(handler),
                HttpServerConfig.default.port(0).host("localhost").tls(TlsConfig.default)
            ).map { binding =>
                val url = HttpUrl.parse(s"https://localhost:${binding.port}").getOrThrow
                client.connectWith(url, Absent) { conn =>
                    client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/ping"))) { resp1 =>
                        assert(resp1.status.code == 200)
                        client.sendWith(conn, route, HttpRequest.getRaw(HttpUrl.fromUri("/ping"))) { resp2 =>
                            assert(resp2.status.code == 200)
                        }
                    }
                }
            }
        }
    }

end NioTlsTest
