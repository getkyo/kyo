package kyo.internal.client

import kyo.*
import kyo.test.HostFilter

/** The client a page gets, exercised in a page.
  *
  * The requests below go to the origin serving the test bundle, which is a kyo `HttpServer`: `/` answers with the page that loaded this
  * code, and a path it does not serve answers 404. That is a real round trip, and in a page it can only have gone through `fetch`, because
  * a page has no socket for the other backend to open.
  *
  * The rest of the suite is what a page refuses. Each of those reaches the browser as something it would drop or reject silently, so the
  * client fails typed instead, and these leaves are the record of which surface does that.
  */
class FetchClientBackendTest extends kyo.test.Test[Any]:

    // The subject is the backend a page selects, so the page is the only host that has one.
    override protected def hostFilters = Chunk(HostFilter.OnlyBrowser)

    private def origin: String =
        scalajs.js.Dynamic.global.location.origin.asInstanceOf[String]

    /** The same origin as a WebSocket URL, which is what `HttpUrl` accepts for a socket. */
    private def socketOrigin: String =
        if origin.startsWith("https:") then s"wss:${origin.drop("https:".length)}" else s"ws:${origin.drop("http:".length)}"

    "a page fetches its own origin" - {

        "GET / answers with the page that loaded this bundle" in {
            HttpClient.getText("/").map { body =>
                assert(body.startsWith("<!doctype html>"), s"the body began with ${body.take(40)}")
            }
        }

        "an absolute URL to the same origin reaches the same page" in {
            HttpClient.getText(s"$origin/").map { body =>
                assert(body.startsWith("<!doctype html>"), s"the body began with ${body.take(40)}")
            }
        }

        "a path the server does not serve answers 404" in {
            HttpClient.getTextResponse("/there-is-no-such-file", failOnError = false).map { response =>
                assert(response.status.code == 404, s"the status was ${response.status.code}")
            }
        }

        "the response carries the headers the server sent" in {
            HttpClient.getTextResponse("/").map { response =>
                assert(
                    response.headers.get("content-type").exists(_.contains("text/html")),
                    s"the content type was ${response.headers.get("content-type")}"
                )
            }
        }
    }

    "a page sends a request body" - {

        "a posted body comes back from the echo fixture" in {
            HttpClient.postText("/__kyo_test__/echo", "the body a page sent").map { echoed =>
                assert(echoed == "the body a page sent", s"the echo answered $echoed")
            }
        }

        "a header the program sets reaches the server" in {
            HttpClient.getText("/__kyo_test__/headers", headers = Seq("X-Kyo-Probe" -> "present")).map { lines =>
                assert(lines.linesIterator.exists(_ == "X-Kyo-Probe: present"), s"the server saw $lines")
            }
        }

        "a response past the configured size is refused, not handed on" in {
            HttpClient.withConfig(_.maxResponseLength(8)) {
                Abort.run[HttpException](HttpClient.getText("/")).map {
                    case Result.Failure(e: HttpPayloadTooLargeException) =>
                        assert(e.maxSize == 8, s"the cap it reported was ${e.maxSize}")
                    case other => assert(false, s"the request ended as $other")
                }
            }
        }

        "a status the server chooses arrives as that status" in {
            HttpClient.getTextResponse("/__kyo_test__/status?code=503", failOnError = false).map { response =>
                assert(response.status.code == 503, s"the status was ${response.status.code}")
            }
        }
    }

    "a page streams a response body" - {

        "the byte stream carries the same page the buffered read returns" in {
            for
                buffered <- HttpClient.getText("/")
                streamed <- HttpClient.getStreamBytes("/").run
            yield
                val joined = new String(streamed.flatMap(_.toArray).toArray, "UTF-8")
                assert(joined == buffered, s"the streamed body was ${joined.length} bytes and the buffered one ${buffered.length}")
        }

        "a stream-typed route reading an error answers the status, not an undrained body" in {
            Abort.run[HttpException](HttpClient.getStreamBytes("/there-is-no-such-file").run).map {
                case Result.Failure(e: HttpStatusException) =>
                    assert(e.status.code == 404, s"the status was ${e.status.code}")
                case other => assert(false, s"the request ended as $other")
            }
        }
    }

    "a page says what it cannot do" - {

        "a header the browser reserves for itself fails rather than being dropped" in {
            Abort.run[HttpException](HttpClient.getText("/", headers = Seq("Cookie" -> "session=1"))).map {
                case Result.Failure(e: HttpUnsupportedOnHostException) =>
                    assert(e.getMessage.contains("Cookie"), s"the failure said ${e.getMessage}")
                case other => assert(false, s"the request ended as $other")
            }
        }

        "a unix socket fails" in {
            Abort.run[HttpException](HttpClient.getText("http+unix://%2Ftmp%2Fkyo.sock/status")).map {
                case Result.Failure(e: HttpUnsupportedOnHostException) =>
                    assert(e.operation == "A unix socket", s"the failure named ${e.operation}")
                case other => assert(false, s"the request ended as $other")
            }
        }

        "a TLS configuration of its own fails" in {
            HttpClient.withConfig(_.tls(HttpTlsConfig(trustAll = true))) {
                Abort.run[HttpException](HttpClient.getText("/")).map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation == "A TLS configuration of its own", s"the failure named ${e.operation}")
                    case other => assert(false, s"the request ended as $other")
                }
            }
        }

        "keeping a redirect for the program fails, because the browser follows it first" in {
            HttpClient.withConfig(_.followRedirects(false)) {
                Abort.run[HttpException](HttpClient.getText("/")).map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation.startsWith("Leaving a redirect for the program"), s"the failure named ${e.operation}")
                    case other => assert(false, s"the request ended as $other")
                }
            }
        }

        "a redirect limit of the program's own fails" in {
            HttpClient.withConfig(_.maxRedirects(2)) {
                Abort.run[HttpException](HttpClient.getText("/")).map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation.startsWith("A redirect limit of its own"), s"the failure named ${e.operation}")
                    case other => assert(false, s"the request ended as $other")
                }
            }
        }

        "a raw connection fails" in {
            Scope.run {
                Abort.run[HttpException](HttpClient.connectRaw(s"$origin/")).map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation == "A raw connection", s"the failure named ${e.operation}")
                    case other => assert(false, s"the connection ended as $other")
                }
            }
        }

        "a WebSocket carrying headers fails, because the browser sends none" in {
            Abort.get(HttpUrl.parse(s"$socketOrigin/__kyo_test__/ws-echo")).map { url =>
                Abort.run[HttpException] {
                    HttpClient.webSocket(url, HttpHeaders.init(Seq("Authorization" -> "Bearer t")), HttpWebSocket.Config())(_ => Kyo.unit)
                }.map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation.startsWith("A WebSocket carrying request headers"), s"the failure named ${e.operation}")
                    case other => assert(false, s"the connection ended as $other")
                }
            }
        }

        "a WebSocket ping interval fails, because the browser answers pings itself" in {
            Abort.get(HttpUrl.parse(s"$socketOrigin/__kyo_test__/ws-echo")).map { url =>
                Abort.run[HttpException] {
                    HttpClient.webSocket(url, HttpHeaders.empty, HttpWebSocket.Config(autoPingInterval = Present(1.second)))(_ => Kyo.unit)
                }.map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation == "A WebSocket ping interval", s"the failure named ${e.operation}")
                    case other => assert(false, s"the connection ended as $other")
                }
            }
        }
    }

    "a page runs a WebSocket session" - {

        "a text frame comes back from the echo fixture" in {
            Abort.get(HttpUrl.parse(s"$socketOrigin/__kyo_test__/ws-echo")).map { url =>
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Text("hello from the page")).andThen {
                        ws.take().map(frame => assert(frame == HttpWebSocket.Payload.Text("hello from the page"), s"the frame was $frame"))
                    }
                }
            }
        }

        "a binary frame keeps its bytes" in {
            val sent = Span.from(Array[Byte](0, 1, 2, 127, -1))
            Abort.get(HttpUrl.parse(s"$socketOrigin/__kyo_test__/ws-echo")).map { url =>
                HttpClient.webSocket(url) { ws =>
                    ws.put(HttpWebSocket.Payload.Binary(sent)).andThen {
                        ws.take().map {
                            case HttpWebSocket.Payload.Binary(back) =>
                                assert(back.toArray.sameElements(sent.toArray), s"the bytes came back as ${back.toArray.toSeq}")
                            case other => assert(false, s"the frame was $other")
                        }
                    }
                }
            }
        }

        "frames arrive in the order they were sent" in {
            Abort.get(HttpUrl.parse(s"$socketOrigin/__kyo_test__/ws-echo")).map { url =>
                HttpClient.webSocket(url) { ws =>
                    Kyo.foreachDiscard(1 to 20)(i => ws.put(HttpWebSocket.Payload.Text(s"frame $i"))).andThen {
                        Kyo.foreach(1 to 20)(_ => ws.take()).map { frames =>
                            val expected = (1 to 20).map(i => HttpWebSocket.Payload.Text(s"frame $i"))
                            assert(frames == Chunk.from(expected), s"the frames arrived as $frames")
                        }
                    }
                }
            }
        }

        "closing the session reports the close reason" in {
            Abort.get(HttpUrl.parse(s"$socketOrigin/__kyo_test__/ws-echo")).map { url =>
                HttpClient.webSocket(url) { ws =>
                    ws.close(1000, "done").andThen(ws.onPeerClose).andThen(ws.closeReason).map { reason =>
                        assert(reason.exists((code, _) => code == 1000), s"the close reason was $reason")
                    }
                }
            }
        }
    }

end FetchClientBackendTest
