package kyo.internal

import kyo.*
import kyo.internal.codec.*
import kyo.internal.http1.*
import kyo.internal.server.*

class HttpRouterTest extends kyo.BaseHttpTest:

    import HttpPath./

    given CanEqual[Any, Any] = CanEqual.derived

    import AllowUnsafe.embrace.danger

    case class User(name: String, age: Int) derives Schema

    def mkEndpoint(route: HttpRoute[?, ?, ?]): HttpHandler[?, ?, ?] =
        HttpHandler.init(route.asInstanceOf[HttpRoute[Any, Any, Any]])(req => HttpResponse.ok)

    /** Why a request did not reach a route: the parser refused it, or it parsed and matched nothing.
      *
      * Kept distinct because the helper below cannot otherwise tell them apart, and a leaf asserting "not found" would
      * silently accept a request that never parsed, which is a different statement about the server.
      */
    enum NotRouted derives CanEqual:
        case Unparseable
        case Routing(error: HttpRouter.FindError)

    /** What routing yields a handler: the capture values and the endpoint's streaming shape. */
    final case class Routed(
        pathCaptures: Dict[String, String],
        endpoint: HttpHandler[?, ?, ?],
        isStreamingRequest: Boolean,
        isStreamingResponse: Boolean
    )

    /** Routes a request exactly as the server does: a real parse, `findParsed`, and the server's OWN capture assembly.
      *
      * The capture values come from `UnsafeServerDispatch.buildCaptures`, the production function, deliberately rather than from a copy of
      * it here. A test that re-implemented capture assembly would pass or fail on the copy, so it would say nothing about what a handler
      * actually receives, which is the only thing these assertions are about.
      *
      * Going through `Http1Parser` also means these tests see segmentation, dot-segment resolution and escape validation as a served
      * request does, none of which the router applies on its own.
      */
    def findVia(router: HttpRouter, method: HttpMethod, path: String): Result[NotRouted, Routed] =
        val channel = Channel.Unsafe.init[Span[Byte]](16)
        val raw     = s"${method.name} $path HTTP/1.1\r\nHost: localhost\r\n\r\n"
        discard(channel.offer(Span.fromUnsafe(raw.getBytes(java.nio.charset.StandardCharsets.US_ASCII))))
        val builder                = new ParsedRequestBuilder
        var request: ParsedRequest = null.asInstanceOf[ParsedRequest]
        val parser                 = new Http1Parser(channel, builder, onRequestParsed = (req, _) => request = req)
        parser.start()
        if request.asInstanceOf[AnyRef] eq null then Result.fail(NotRouted.Unparseable)
        else
            val lookup = new RouteLookup(router.maxCaptures)
            router.findParsed(method, request, lookup).mapFailure(NotRouted.Routing(_)).map { _ =>
                Routed(
                    UnsafeServerDispatch.buildCaptures(request, lookup, router.captureNames(lookup)),
                    router.endpoint(lookup),
                    lookup.isStreamingRequest,
                    lookup.isStreamingResponse
                )
            }
        end if
    end findVia

    // ==================== Empty router ====================

    "empty router" - {
        "returns NotFound for any path" in {
            val router = HttpRouter(Seq.empty, Absent)
            findVia(router, HttpMethod.GET, "/anything") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.NotFound)) => succeed("expected: empty router returns NotFound")
                case other                                                            => fail(s"expected NotFound, got $other")
        }
    }

    // ==================== Single route ====================

    "single route" - {
        "matches exact path" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.isEmpty)
                    assert(!m.isStreamingRequest)
                    assert(!m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "matches without leading slash" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "users") match
                case Result.Success(m) => assert(m.pathCaptures.isEmpty) // path matched without leading slash
                case other             => fail(s"expected match, got $other")
        }

        "matches with trailing slash" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users/") match
                case Result.Success(m) => assert(!m.isStreamingRequest) // path matched with trailing slash
                case other             => fail(s"expected match, got $other")
        }

        "matches with multiple slashes" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "///users///") match
                case Result.Success(m) => assert(!m.isStreamingRequest) // path matched with multiple slashes
                case other             => fail(s"expected match, got $other")
        }

        "returns NotFound for wrong path" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/posts") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.NotFound)) => succeed("expected: wrong path returns NotFound")
                case other                                                            => fail(s"expected NotFound, got $other")
        }

        "returns MethodNotAllowed for wrong method" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.POST, "/users") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.MethodNotAllowed(methods))) =>
                    assert(methods.contains(HttpMethod.GET))
                case other => fail(s"expected MethodNotAllowed, got $other")
            end match
        }

        "HEAD matches GET route" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.HEAD, "/users") match
                case Result.Success(m) => assert(m.endpoint.route.method == HttpMethod.GET) // HEAD resolves to GET route
                case other             => fail(s"expected match, got $other")
        }
    }

    // ==================== Multiple segments ====================

    "multi-segment paths" - {
        "matches nested path" in {
            val route  = HttpRoute.getRaw("api" / HttpPath.Literal("v1") / HttpPath.Literal("users"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/api/v1/users") match
                case Result.Success(m) => assert(m.pathCaptures.isEmpty) // multi-segment path matched
                case other             => fail(s"expected match, got $other")
        }

        "NotFound for partial match" in {
            val route  = HttpRoute.getRaw("api" / HttpPath.Literal("v1") / HttpPath.Literal("users"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/api/v1") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.NotFound)) => succeed("expected: partial path returns NotFound")
                case other                                                            => fail(s"expected NotFound, got $other")
        }

        "NotFound for too-deep path" in {
            val route  = HttpRoute.getRaw("api")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/api/v1/users") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.NotFound)) => succeed("expected: too-deep path returns NotFound")
                case other                                                            => fail(s"expected NotFound, got $other")
        }
    }

    // ==================== Path captures ====================

    "path captures" - {
        "extracts single capture" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users/42") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("userId" -> "42")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "extracts multiple captures" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users/42/posts/7") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("userId" -> "42", "postId" -> "7")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "URL-decodes capture values" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[String]("name"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users/John%20Doe") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("name" -> "John Doe")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "uses wireName when set" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[String]("userId", wireName = "user_id"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users/42") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("user_id" -> "42")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "capture matches any segment" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[String]("userId"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users/anything-goes-here") match
                case Result.Success(m) =>
                    assert(m.pathCaptures("userId") == "anything-goes-here")
                case other => fail(s"expected match, got $other")
            end match
        }
    }

    // ==================== Rest captures ====================

    "rest captures" - {
        "captures remaining path" in {
            val route  = HttpRoute.getRaw("files" / Capture.Rest("path"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/files/a/b/c.txt") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("path" -> "a/b/c.txt")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "captures single segment" in {
            val route  = HttpRoute.getRaw("files" / Capture.Rest("path"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/files/readme.md") match
                case Result.Success(m) =>
                    assert(m.pathCaptures("path") == "readme.md")
                case other => fail(s"expected match, got $other")
            end match
        }
        "rejects Rest in non-terminal position" in {
            val route = HttpRoute.getRaw("api" / Capture.Rest("mid") / "suffix")
            val ex = intercept[IllegalArgumentException] {
                HttpRouter(Seq(mkEndpoint(route)), Absent)
            }
            assert(ex.getMessage.contains("Rest capture must be the last segment"))
        }

        "allows Rest as the only segment" in {
            val route  = HttpRoute.getRaw(Capture.Rest("path"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/anything/here") match
                case Result.Success(m) =>
                    assert(m.pathCaptures("path") == "anything/here")
                case other => fail(s"expected match, got $other")
            end match
        }
    }

    // ==================== Multiple routes ====================

    "multiple routes" - {
        "routes to correct endpoint by path" in {
            val usersRoute = HttpRoute.getRaw("users")
            val postsRoute = HttpRoute.getRaw("posts")
            val router     = HttpRouter(Seq(mkEndpoint(usersRoute), mkEndpoint(postsRoute)), Absent)
            findVia(router, HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.request.path == usersRoute.request.path)
                case other => fail(s"expected match, got $other")
            end match
            findVia(router, HttpMethod.GET, "/posts") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.request.path == postsRoute.request.path)
                case other => fail(s"expected match, got $other")
            end match
        }

        "routes to correct endpoint by method" in {
            val getRoute  = HttpRoute.getRaw("users")
            val postRoute = HttpRoute.postRaw("users").request(_.bodyJson[User])
            val router    = HttpRouter(Seq(mkEndpoint(getRoute), mkEndpoint(postRoute)), Absent)
            findVia(router, HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.method == HttpMethod.GET)
                case other => fail(s"expected match, got $other")
            end match
            findVia(router, HttpMethod.POST, "/users") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.method == HttpMethod.POST)
                case other => fail(s"expected match, got $other")
            end match
        }

        "MethodNotAllowed includes all allowed methods" in {
            val getRoute    = HttpRoute.getRaw("users")
            val postRoute   = HttpRoute.postRaw("users").request(_.bodyJson[User])
            val deleteRoute = HttpRoute.deleteRaw("users")
            val router      = HttpRouter(Seq(mkEndpoint(getRoute), mkEndpoint(postRoute), mkEndpoint(deleteRoute)), Absent)
            findVia(router, HttpMethod.PUT, "/users") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.MethodNotAllowed(methods))) =>
                    assert(methods.contains(HttpMethod.GET))
                    assert(methods.contains(HttpMethod.POST))
                    assert(methods.contains(HttpMethod.DELETE))
                case other => fail(s"expected MethodNotAllowed, got $other")
            end match
        }

        "literal preferred over capture" in {
            val literalRoute = HttpRoute.getRaw("users" / HttpPath.Literal("me"))
            val captureRoute = HttpRoute.getRaw("users" / HttpPath.Capture[String]("userId"))
            val router       = HttpRouter(Seq(mkEndpoint(literalRoute), mkEndpoint(captureRoute)), Absent)
            // "me" should match literal
            findVia(router, HttpMethod.GET, "/users/me") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.isEmpty)
                case other => fail(s"expected literal match, got $other")
            end match
            // other segments should match capture
            findVia(router, HttpMethod.GET, "/users/42") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("userId" -> "42")))
                case other => fail(s"expected capture match, got $other")
            end match
        }
    }

    // ==================== Streaming flags ====================

    "streaming flags" - {
        "non-streaming route" in {
            val route  = HttpRoute.getRaw("users").response(_.bodyJson[User])
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(!m.isStreamingRequest)
                    assert(!m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "streaming request" in {
            val route  = HttpRoute.postRaw("upload").request(_.bodyStream)
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.POST, "/upload") match
                case Result.Success(m) =>
                    assert(m.isStreamingRequest)
                    assert(!m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "streaming response" in {
            val route  = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/events") match
                case Result.Success(m) =>
                    assert(!m.isStreamingRequest)
                    assert(m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "both streaming" in {
            val route  = HttpRoute.postRaw("pipe").request(_.bodyStream).response(_.bodyStream)
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.POST, "/pipe") match
                case Result.Success(m) =>
                    assert(m.isStreamingRequest)
                    assert(m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }
    }

    // ==================== Root path ====================

    "root path" - {
        "matches empty path" in {
            val route  = HttpRoute.getRaw("")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "/") match
                case Result.Success(m) => assert(m.pathCaptures.isEmpty) // root path matches "/"
                case other             => fail(s"expected match, got $other")
        }

        // This used to assert that a root route matches the empty STRING, which was a property of the router's
        // String-based lookup rather than of any request: RFC 9112 section 3.2.1 gives origin-form a minimum of "/", so
        // a request target is never empty on the wire. With routing driven by a real request the equivalent question is
        // what happens to a request line that omits the target, and the answer must be refusal rather than a match on
        // the root, since treating an absent target as "/" would serve the root to a malformed request.
        "does not route a request whose target is missing" in {
            val route  = HttpRoute.getRaw("")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            findVia(router, HttpMethod.GET, "") match
                case Result.Failure(NotRouted.Unparseable) =>
                    succeed("expected: a request line with no target is refused before routing")
                case other => fail(s"an empty request target must be refused as unparseable, got $other")
            end match
        }
    }

    // ==================== Binary search correctness ====================

    "binary search" - {
        "works with many literal siblings" in {
            val routes = (0 until 20).map(i => HttpRoute.getRaw(f"route$i%02d"))
            val router = HttpRouter(routes.map(mkEndpoint), Absent)
            // Check first, last, and middle
            findVia(router, HttpMethod.GET, "/route00") match
                case Result.Success(m) => assert(m.pathCaptures.isEmpty) // first route matched by binary search
                case other             => fail(s"expected match for route00, got $other")
            findVia(router, HttpMethod.GET, "/route10") match
                case Result.Success(m) => assert(m.pathCaptures.isEmpty) // middle route matched by binary search
                case other             => fail(s"expected match for route10, got $other")
            findVia(router, HttpMethod.GET, "/route19") match
                case Result.Success(m) => assert(m.pathCaptures.isEmpty) // last route matched by binary search
                case other             => fail(s"expected match for route19, got $other")
            // Non-existent
            findVia(router, HttpMethod.GET, "/route20") match
                case Result.Failure(NotRouted.Routing(HttpRouter.FindError.NotFound)) =>
                    succeed("expected: out-of-range route returns NotFound")
                case other => fail(s"expected NotFound for route20, got $other")
            end match
        }
    }

    // ==================== All HTTP methods ====================

    "all HTTP methods" - {
        "maps each method correctly" in {
            val methods = Seq(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.PATCH,
                HttpMethod.DELETE,
                HttpMethod.HEAD,
                HttpMethod.OPTIONS,
                HttpMethod.TRACE
            )
            val routes = methods.filterNot(_ == HttpMethod.HEAD).map { m =>
                val route = HttpRoute(m, HttpRoute.RequestDef(HttpPath.Literal("test")))
                mkEndpoint(route)
            }
            val router = HttpRouter(routes, Absent)
            // All methods should match (HEAD via GET)
            val failures = methods.flatMap { m =>
                findVia(router, m, "/test") match
                    case Result.Success(rm) =>
                        if m == HttpMethod.HEAD then
                            if rm.endpoint.route.method != HttpMethod.GET then Some(s"HEAD did not resolve to GET")
                            else None
                        else if rm.endpoint.route.method != m then Some(s"${m.name} matched wrong method")
                        else None
                    case other => Some(s"expected match for ${m.name}, got $other")
            }
            assert(failures.isEmpty, failures.mkString("; "))
        }
    }

end HttpRouterTest
