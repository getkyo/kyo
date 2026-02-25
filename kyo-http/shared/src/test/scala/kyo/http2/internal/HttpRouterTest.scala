package kyo.http2.internal

import kyo.Dict
import kyo.Frame
import kyo.Result
import kyo.http2.HttpCodec
import kyo.http2.HttpHandler
import kyo.http2.HttpMethod
import kyo.http2.HttpPath
import kyo.http2.HttpRequest
import kyo.http2.HttpResponse
import kyo.http2.HttpRoute
import kyo.http2.HttpUrl
import kyo.http2.Schema

class HttpRouterTest extends kyo.Test:

    override protected def useTestClient: Boolean = false

    import HttpPath./

    given CanEqual[Any, Any] = CanEqual.derived

    case class User(name: String, age: Int) derives Schema

    def mkEndpoint(route: HttpRoute[?, ?, ?]): HttpHandler[?, ?, ?] =
        HttpHandler.init(route.asInstanceOf[HttpRoute[Any, Any, Any]])(req => HttpResponse.ok)

    // ==================== Empty router ====================

    "empty router" - {
        "returns NotFound for any path" in {
            val router = HttpRouter(Seq.empty)
            router.find(HttpMethod.GET, "/anything") match
                case Result.Failure(HttpRouter.FindError.NotFound) => succeed
                case other                                         => fail(s"expected NotFound, got $other")
        }
    }

    // ==================== Single route ====================

    "single route" - {
        "matches exact path" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.isEmpty)
                    assert(!m.isStreamingRequest)
                    assert(!m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "matches without leading slash" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "users") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }

        "matches with trailing slash" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users/") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }

        "matches with multiple slashes" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "///users///") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }

        "returns NotFound for wrong path" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/posts") match
                case Result.Failure(HttpRouter.FindError.NotFound) => succeed
                case other                                         => fail(s"expected NotFound, got $other")
        }

        "returns MethodNotAllowed for wrong method" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.POST, "/users") match
                case Result.Failure(HttpRouter.FindError.MethodNotAllowed(methods)) =>
                    assert(methods.contains(HttpMethod.GET))
                case other => fail(s"expected MethodNotAllowed, got $other")
            end match
        }

        "HEAD matches GET route" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.HEAD, "/users") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }
    }

    // ==================== Multiple segments ====================

    "multi-segment paths" - {
        "matches nested path" in {
            val route  = HttpRoute.getRaw("api" / HttpPath.Literal("v1") / HttpPath.Literal("users"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/api/v1/users") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }

        "NotFound for partial match" in {
            val route  = HttpRoute.getRaw("api" / HttpPath.Literal("v1") / HttpPath.Literal("users"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/api/v1") match
                case Result.Failure(HttpRouter.FindError.NotFound) => succeed
                case other                                         => fail(s"expected NotFound, got $other")
        }

        "NotFound for too-deep path" in {
            val route  = HttpRoute.getRaw("api")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/api/v1/users") match
                case Result.Failure(HttpRouter.FindError.NotFound) => succeed
                case other                                         => fail(s"expected NotFound, got $other")
        }
    }

    // ==================== Path captures ====================

    "path captures" - {
        "extracts single capture" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users/42") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("userId" -> "42")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "extracts multiple captures" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users/42/posts/7") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("userId" -> "42", "postId" -> "7")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "URL-decodes capture values" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[String]("name"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users/John%20Doe") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("name" -> "John Doe")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "uses wireName when set" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[String]("userId", wireName = "user_id"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users/42") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("user_id" -> "42")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "capture matches any segment" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[String]("userId"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users/anything-goes-here") match
                case Result.Success(m) =>
                    assert(m.pathCaptures("userId") == "anything-goes-here")
                case other => fail(s"expected match, got $other")
            end match
        }
    }

    // ==================== Rest captures ====================

    "rest captures" - {
        "captures remaining path" in {
            val route  = HttpRoute.getRaw("files" / HttpPath.Rest("path"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/files/a/b/c.txt") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.is(Dict("path" -> "a/b/c.txt")))
                case other => fail(s"expected match, got $other")
            end match
        }

        "captures single segment" in {
            val route  = HttpRoute.getRaw("files" / HttpPath.Rest("path"))
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/files/readme.md") match
                case Result.Success(m) =>
                    assert(m.pathCaptures("path") == "readme.md")
                case other => fail(s"expected match, got $other")
            end match
        }
    }

    // ==================== Multiple routes ====================

    "multiple routes" - {
        "routes to correct endpoint by path" in {
            val usersRoute = HttpRoute.getRaw("users")
            val postsRoute = HttpRoute.getRaw("posts")
            val router     = HttpRouter(Seq(mkEndpoint(usersRoute), mkEndpoint(postsRoute)))
            router.find(HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.request.path == usersRoute.request.path)
                case other => fail(s"expected match, got $other")
            end match
            router.find(HttpMethod.GET, "/posts") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.request.path == postsRoute.request.path)
                case other => fail(s"expected match, got $other")
            end match
        }

        "routes to correct endpoint by method" in {
            val getRoute  = HttpRoute.getRaw("users")
            val postRoute = HttpRoute.postRaw("users").request(_.bodyJson[User])
            val router    = HttpRouter(Seq(mkEndpoint(getRoute), mkEndpoint(postRoute)))
            router.find(HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.method == HttpMethod.GET)
                case other => fail(s"expected match, got $other")
            end match
            router.find(HttpMethod.POST, "/users") match
                case Result.Success(m) =>
                    assert(m.endpoint.route.method == HttpMethod.POST)
                case other => fail(s"expected match, got $other")
            end match
        }

        "MethodNotAllowed includes all allowed methods" in {
            val getRoute    = HttpRoute.getRaw("users")
            val postRoute   = HttpRoute.postRaw("users").request(_.bodyJson[User])
            val deleteRoute = HttpRoute.deleteRaw("users")
            val router      = HttpRouter(Seq(mkEndpoint(getRoute), mkEndpoint(postRoute), mkEndpoint(deleteRoute)))
            router.find(HttpMethod.PUT, "/users") match
                case Result.Failure(HttpRouter.FindError.MethodNotAllowed(methods)) =>
                    assert(methods.contains(HttpMethod.GET))
                    assert(methods.contains(HttpMethod.POST))
                    assert(methods.contains(HttpMethod.DELETE))
                case other => fail(s"expected MethodNotAllowed, got $other")
            end match
        }

        "literal preferred over capture" in {
            val literalRoute = HttpRoute.getRaw("users" / HttpPath.Literal("me"))
            val captureRoute = HttpRoute.getRaw("users" / HttpPath.Capture[String]("userId"))
            val router       = HttpRouter(Seq(mkEndpoint(literalRoute), mkEndpoint(captureRoute)))
            // "me" should match literal
            router.find(HttpMethod.GET, "/users/me") match
                case Result.Success(m) =>
                    assert(m.pathCaptures.isEmpty)
                case other => fail(s"expected literal match, got $other")
            end match
            // other segments should match capture
            router.find(HttpMethod.GET, "/users/42") match
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
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/users") match
                case Result.Success(m) =>
                    assert(!m.isStreamingRequest)
                    assert(!m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "streaming request" in {
            val route  = HttpRoute.postRaw("upload").request(_.bodyStream)
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.POST, "/upload") match
                case Result.Success(m) =>
                    assert(m.isStreamingRequest)
                    assert(!m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "streaming response" in {
            val route  = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/events") match
                case Result.Success(m) =>
                    assert(!m.isStreamingRequest)
                    assert(m.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "both streaming" in {
            val route  = HttpRoute.postRaw("pipe").request(_.bodyStream).response(_.bodyStream)
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.POST, "/pipe") match
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
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "/") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }

        "matches empty string" in {
            val route  = HttpRoute.getRaw("")
            val router = HttpRouter(Seq(mkEndpoint(route)))
            router.find(HttpMethod.GET, "") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match, got $other")
        }
    }

    // ==================== Binary search correctness ====================

    "binary search" - {
        "works with many literal siblings" in {
            val routes = (0 until 20).map(i => HttpRoute.getRaw(f"route$i%02d"))
            val router = HttpRouter(routes.map(mkEndpoint))
            // Check first, last, and middle
            router.find(HttpMethod.GET, "/route00") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match for route00, got $other")
            router.find(HttpMethod.GET, "/route10") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match for route10, got $other")
            router.find(HttpMethod.GET, "/route19") match
                case Result.Success(_) => succeed
                case other             => fail(s"expected match for route19, got $other")
            // Non-existent
            router.find(HttpMethod.GET, "/route20") match
                case Result.Failure(HttpRouter.FindError.NotFound) => succeed
                case other                                         => fail(s"expected NotFound for route20, got $other")
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
            val router = HttpRouter(routes)
            // All methods should match (HEAD via GET)
            val failures = methods.flatMap { m =>
                router.find(m, "/test") match
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
