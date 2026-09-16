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

        "a raw connection fails" in {
            Scope.run {
                Abort.run[HttpException](HttpClient.connectRaw(s"$origin/")).map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation == "A raw connection", s"the failure named ${e.operation}")
                    case other => assert(false, s"the connection ended as $other")
                }
            }
        }

        "a WebSocket fails" in {
            Abort.get(HttpUrl.parse(s"$origin/socket")).map { url =>
                Abort.run[HttpException](HttpClient.webSocket(url)(_ => Kyo.unit)).map {
                    case Result.Failure(e: HttpUnsupportedOnHostException) =>
                        assert(e.operation == "A WebSocket", s"the failure named ${e.operation}")
                    case other => assert(false, s"the connection ended as $other")
                }
            }
        }
    }

end FetchClientBackendTest
