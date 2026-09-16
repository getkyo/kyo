package kyo.internal.client

import kyo.*
import kyo.internal.PlatformJs
import scala.scalajs.js as sjs

/** The fetch client on a host that lacks part of what a page has.
  *
  * [[ClientPlatform]] hands this client to every JS host without sockets, not only to pages: an edge runtime, an embedded engine and a
  * worker are in the same position, and each can lack `fetch`, `WebSocket` or a page location. A read of a global the host does not
  * declare throws a `ReferenceError` before any check runs, so each of these has to arrive as the typed refusal a caller can handle.
  *
  * The leaves take a global away for their duration, which a page cannot survive while its other suites run, so they run where the fetch
  * client is not the one in use.
  */
class FetchClientBackendHostTest extends kyo.test.Test[Any]:

    override def config = super.config.sequential

    private val client: HttpClient = (new FetchClientBackend).asInstanceOf[HttpClient]

    /** Runs `v` with `name` removed from the global object, restored afterwards. */
    private def without[A, S](name: String)(v: A < S)(using Frame): A < (S & Sync) =
        Sync.defer {
            val global = sjs.Dynamic.global.globalThis
            PlatformJs.jsGlobal(name).fold(v: A < (S & Sync)) { saved =>
                sjs.special.delete(global, name)
                Sync.ensure(Sync.defer(global.updateDynamic(name)(saved)))(v)
            }
        }

    "a host with no fetch refuses a request, naming fetch" in {
        assume(!kyo.internal.Platform.isBrowser, "takes fetch away, which the page's other suites use")
        without("fetch") {
            Abort.run[HttpException](HttpClient.let(client)(HttpClient.getText("http://127.0.0.1:1/")))
        }.map {
            case Result.Failure(e: HttpUnsupportedOnHostException) => assert(e.operation == "fetch", s"the failure named ${e.operation}")
            case other                                             => assert(false, s"the request ended as $other")
        }
    }

    "a host with no WebSocket refuses a WebSocket, naming it" in {
        assume(!kyo.internal.Platform.isBrowser, "takes WebSocket away, which the page's other suites use")
        Abort.get(HttpUrl.parse("ws://127.0.0.1:1/socket")).map { url =>
            without("WebSocket") {
                Abort.run[HttpException](HttpClient.let(client)(HttpClient.webSocket(url)(_ => Kyo.unit)))
            }.map {
                case Result.Failure(e: HttpUnsupportedOnHostException) =>
                    assert(e.operation == "WebSocket", s"the failure named ${e.operation}")
                case other => assert(false, s"the connection ended as $other")
            }
        }
    }

    "a WebSocket URL with no host is refused on a host with no page location" in {
        assume(PlatformJs.jsGlobal("location").isEmpty, "resolves against the page's location on a host that has one")
        Abort.get(HttpUrl.parse("/socket")).map { url =>
            Abort.run[HttpException](HttpClient.let(client)(HttpClient.webSocket(url)(_ => Kyo.unit))).map {
                case Result.Failure(e: HttpUnsupportedOnHostException) =>
                    assert(e.operation.startsWith("A WebSocket URL missing its scheme or host"), s"the failure named ${e.operation}")
                case other => assert(false, s"the connection ended as $other")
            }
        }
    }

    "socketUrl" - {
        "keeps a ws URL's own scheme, host and port" in {
            Abort.get(HttpUrl.parse("ws://example.test:8080/a?b=1")).map { url =>
                assert(FetchClientBackend.socketUrl(url) == Present("ws://example.test:8080/a?b=1"))
            }
        }

        "maps https to wss and leaves the default port out" in {
            Abort.get(HttpUrl.parse("https://example.test/a")).map { url =>
                assert(FetchClientBackend.socketUrl(url) == Present("wss://example.test/a"))
            }
        }

        "has nothing to resolve a URL with no host against on a host with no page location" in {
            assume(PlatformJs.jsGlobal("location").isEmpty, "resolves against the page's location on a host that has one")
            Abort.get(HttpUrl.parse("/a")).map { url =>
                assert(FetchClientBackend.socketUrl(url) == Absent)
            }
        }
    }
end FetchClientBackendHostTest
