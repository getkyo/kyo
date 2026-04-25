package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.*
import kyo.internal.codec.*
import kyo.internal.server.*

class RouteLookupTest extends kyo.Test:

    import AllowUnsafe.embrace.danger
    import HttpPath./

    given CanEqual[Any, Any] = CanEqual.derived

    case class User(name: String, age: Int) derives Schema

    def mkEndpoint(route: HttpRoute[?, ?, ?]): HttpHandler[?, ?, ?] =
        HttpHandler.init(route.asInstanceOf[HttpRoute[Any, Any, Any]])(req => HttpResponse.ok)

    /** Build a ParsedRequest from a method ordinal and raw request path (e.g. "/api/v1/users?q=test"). */
    private def buildParsedRequest(methodOrdinal: Int, rawPath: String): ParsedRequest =
        val builder = new ParsedRequestBuilder
        builder.setMethod(methodOrdinal)
        builder.setKeepAlive(true)

        // Split path from query
        val qIdx      = rawPath.indexOf('?')
        val pathPart  = if qIdx >= 0 then rawPath.substring(0, qIdx) else rawPath
        val queryPart = if qIdx >= 0 then rawPath.substring(qIdx + 1) else null

        // Set path bytes
        val pathBytes = pathPart.getBytes(StandardCharsets.UTF_8)
        builder.setPath(pathBytes, 0, pathBytes.length)

        // Set query if present
        if queryPart != null then
            val queryBytes = queryPart.getBytes(StandardCharsets.UTF_8)
            builder.setQuery(queryBytes, 0, queryBytes.length)

        // Add path segments (skip leading slashes, split on /)
        val segments = pathPart.split('/').filter(_.nonEmpty)
        segments.foreach { seg =>
            val segBytes = seg.getBytes(StandardCharsets.UTF_8)
            builder.addPathSegment(segBytes, 0, segBytes.length)
        }

        builder.build()
    end buildParsedRequest

    // Method ordinals matching ParsedRequest.methods array
    private val GET     = 0
    private val POST    = 1
    private val PUT     = 2
    private val DELETE  = 4
    private val HEAD    = 5
    private val OPTIONS = 6

    "findParsed" - {

        "matches simple path" in {
            val route  = HttpRoute.getRaw("hello")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/hello")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    assert(lookup.captureCount == 0)
                case other => fail(s"expected match, got $other")
            end match
        }

        "returns NotFound for missing path" in {
            val route  = HttpRoute.getRaw("hello")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/missing")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Failure(HttpRouter.FindError.NotFound) =>
                    assert(!lookup.matched)
                case other => fail(s"expected NotFound, got $other")
            end match
        }

        "matches path with captures" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/users/42")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    assert(lookup.captureCount == 1)
                    // Segment index 1 is "42" (segment 0 is "users")
                    assert(lookup.captureSegmentIndices(0) == 1)
                    // Verify the segment value via ParsedRequest
                    assert(req.pathSegmentAsString(lookup.captureSegmentIndices(0)) == "42")
                case other => fail(s"expected match, got $other")
            end match
        }

        "matches nested path" in {
            val route  = HttpRoute.getRaw("api" / HttpPath.Literal("v1") / HttpPath.Literal("items"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/api/v1/items")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    assert(lookup.captureCount == 0)
                case other => fail(s"expected match, got $other")
            end match
        }

        "HEAD matches GET" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(HEAD, "/users")

            router.findParsed(HttpMethod.HEAD, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                case other => fail(s"expected match, got $other")
            end match
        }

        "MethodNotAllowed" in {
            val route  = HttpRoute.getRaw("users")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(POST, "/users")

            router.findParsed(HttpMethod.POST, req, lookup) match
                case Result.Failure(HttpRouter.FindError.MethodNotAllowed(methods)) =>
                    assert(methods.contains(HttpMethod.GET))
                case other => fail(s"expected MethodNotAllowed, got $other")
            end match
        }

        "streaming flags" in {
            val streamRoute = HttpRoute.postRaw("upload").request(_.bodyStream)
            val router      = HttpRouter(Seq(mkEndpoint(streamRoute)), Absent)
            val lookup      = new RouteLookup(8)
            val req         = buildParsedRequest(POST, "/upload")

            router.findParsed(HttpMethod.POST, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    assert(lookup.isStreamingRequest)
                    assert(!lookup.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "streaming response flags" in {
            val route  = HttpRoute.getRaw("events").response(_.bodySseJson[User])
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/events")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    assert(!lookup.isStreamingRequest)
                    assert(lookup.isStreamingResponse)
                case other => fail(s"expected match, got $other")
            end match
        }

        "multiple captures" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/users/42/posts/7")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    assert(lookup.captureCount == 2)
                    // Segment 1 is "42", segment 3 is "7"
                    assert(lookup.captureSegmentIndices(0) == 1)
                    assert(lookup.captureSegmentIndices(1) == 3)
                    assert(req.pathSegmentAsString(lookup.captureSegmentIndices(0)) == "42")
                    assert(req.pathSegmentAsString(lookup.captureSegmentIndices(1)) == "7")
                case other => fail(s"expected match, got $other")
            end match
        }

        "reset reuses lookup" in {
            val usersRoute = HttpRoute.getRaw("users")
            val postsRoute = HttpRoute.getRaw("posts")
            val router     = HttpRouter(Seq(mkEndpoint(usersRoute), mkEndpoint(postsRoute)), Absent)
            val lookup     = new RouteLookup(8)

            // First match
            val req1 = buildParsedRequest(GET, "/users")
            router.findParsed(HttpMethod.GET, req1, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    val firstIdx = lookup.endpointIdx
                    assert(router.endpoint(lookup).route.request.path == usersRoute.request.path)

                    // Second match - findParsed calls reset() internally
                    val req2 = buildParsedRequest(GET, "/posts")
                    router.findParsed(HttpMethod.GET, req2, lookup) match
                        case Result.Success(()) =>
                            assert(lookup.matched)
                            assert(lookup.endpointIdx != firstIdx)
                            assert(router.endpoint(lookup).route.request.path == postsRoute.request.path)
                        case other => fail(s"expected second match, got $other")
                    end match
                case other => fail(s"expected first match, got $other")
            end match
        }

        "with query string" in {
            val route  = HttpRoute.getRaw("search")
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/search?q=hello&page=2")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    assert(lookup.matched)
                    // Query params should not affect path matching
                    assert(lookup.captureCount == 0)
                case other => fail(s"expected match, got $other")
            end match
        }

        "endpoint accessor returns correct handler" in {
            val usersRoute = HttpRoute.getRaw("users")
            val postsRoute = HttpRoute.getRaw("posts")
            val router     = HttpRouter(Seq(mkEndpoint(usersRoute), mkEndpoint(postsRoute)), Absent)
            val lookup     = new RouteLookup(8)

            val req = buildParsedRequest(GET, "/posts")
            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    val ep = router.endpoint(lookup)
                    assert(ep.route.request.path == postsRoute.request.path)
                case other => fail(s"expected match, got $other")
            end match
        }

        "captureNames accessor" in {
            val route  = HttpRoute.getRaw("users" / HttpPath.Capture[Int]("userId") / "posts" / HttpPath.Capture[Int]("postId"))
            val router = HttpRouter(Seq(mkEndpoint(route)), Absent)
            val lookup = new RouteLookup(8)
            val req    = buildParsedRequest(GET, "/users/42/posts/7")

            router.findParsed(HttpMethod.GET, req, lookup) match
                case Result.Success(()) =>
                    val names = router.captureNames(lookup)
                    assert(names.size == 2)
                    assert(names(0) == "userId")
                    assert(names(1) == "postId")
                case other => fail(s"expected match, got $other")
            end match
        }
    }

end RouteLookupTest
