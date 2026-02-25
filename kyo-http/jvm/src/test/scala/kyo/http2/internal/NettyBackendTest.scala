// package kyo.http2.internal

// import kyo.<
// import kyo.Abort
// import kyo.AllowUnsafe
// import kyo.Absent
// import kyo.Async
// import kyo.Chunk
// import kyo.Frame
// import kyo.Emit
// import kyo.Loop
// import kyo.Record2.~
// import kyo.Scope
// import kyo.Span
// import kyo.Stream
// import scala.concurrent.duration.*
// import kyo.http2.HttpBackend
// import kyo.http2.HttpEndpoint
// import kyo.http2.HttpError
// import kyo.http2.HttpHeaders as Http2Headers
// import kyo.http2.HttpMethod as Http2Method
// import kyo.http2.HttpPath.*
// import kyo.http2.HttpRequest as Http2Request
// import kyo.http2.HttpResponse as Http2Response
// import kyo.http2.HttpRoute
// import kyo.http2.HttpStatus as Http2Status
// import kyo.http2.HttpUrl as Http2Url
// import kyo.http2.Schema

// class NettyHttp2BackendTest extends kyo.Test:

//     override protected def useTestClient: Boolean = false

//     val server = new NettyHttp2ServerBackend()
//     val client = new NettyClientBackend()

//     case class User(id: Int, name: String) derives Schema, CanEqual

//     def withServer[A](endpoints: HttpEndpoint[?, ?, ?]*)(
//         test: Int => A < (Async & Abort[HttpError])
//     )(using Frame): A < (Async & Scope & Abort[HttpError]) =
//         Scope.acquireRelease(server.bind(endpoints.asInstanceOf[Seq[HttpEndpoint[?, ?, Any]]], 0, "localhost"))(_.closeNow).map { binding =>
//             test(binding.port)
//         }

//     def sendRequest[In, Out](
//         port: Int,
//         route: HttpRoute[In, Out, ?],
//         request: Http2Request[In]
//     )(using Frame): Http2Response[Out] < (Async & Abort[HttpError]) =
//         client.connectWith("localhost", port, ssl = false, Absent) { conn =>
//             client.sendWith(conn, route, request)(identity)
//         }

//     "server" - {

//         "binds and returns port" in run {
//             withServer() { port =>
//                 assert(port > 0)
//             }
//         }

//         "returns 404 for unknown path" in run {
//             val route = HttpRoute.getRaw("test").response(_.bodyText)
//             val ep    = route.endpoint(_ => Http2Response.ok.addField("body", "hello"))

//             // Use a different route for the client request to hit an unregistered path
//             val unknownRoute = HttpRoute.getRaw("unknown").response(_.bodyText)
//             withServer(ep) { port =>
//                 sendRequest(port, unknownRoute, Http2Request(Http2Method.GET, Http2Url.fromUri("/unknown"))).map { resp =>
//                     assert(resp.status == Http2Status.NotFound)
//                 }
//             }
//         }

//         "returns 405 for wrong method" in run {
//             val route = HttpRoute.getRaw("test").response(_.bodyText)
//             val ep    = route.endpoint(_ => Http2Response.ok.addField("body", "hello"))

//             val postRoute = HttpRoute.postRaw("test").response(_.bodyText)

//             withServer(ep) { port =>
//                 sendRequest(port, postRoute, Http2Request(Http2Method.POST, Http2Url.fromUri("/test"))).map { resp =>
//                     assert(resp.status == Http2Status.MethodNotAllowed)
//                 }
//             }
//         }

//         "handles GET with text body response" in run {
//             val route = HttpRoute.getRaw("hello").response(_.bodyText)
//             val ep    = route.endpoint(_ => Http2Response.ok.addField("body", "world"))
//             withServer(ep) { port =>
//                 sendRequest(port, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/hello"))).map { resp =>
//                     assert(resp.status == Http2Status.OK)
//                     assert(resp.fields.body == "world")
//                 }
//             }
//         }

//         "handles GET with JSON response" in run {
//             val route = HttpRoute.getRaw("user").response(_.bodyJson[User])
//             val ep    = route.endpoint(_ => Http2Response.ok.addField("body", User(1, "alice")))
//             withServer(ep) { port =>
//                 sendRequest(port, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/user"))).map { resp =>
//                     assert(resp.status == Http2Status.OK)
//                     assert(resp.fields.body == User(1, "alice"))
//                 }
//             }
//         }

//         "handles POST with JSON request and response" in run {
//             val route = HttpRoute.postRaw("user")
//                 .request(_.bodyJson[User])
//                 .response(_.bodyJson[User])
//             val ep = route.endpoint { req =>
//                 val user = req.fields.body
//                 Http2Response.ok.addField("body", User(user.id + 1, user.name.toUpperCase))
//             }
//             withServer(ep) { port =>
//                 val request = Http2Request(Http2Method.POST, Http2Url.fromUri("/user"))
//                     .addField("body", User(1, "bob"))
//                 sendRequest(port, route, request).map { resp =>
//                     assert(resp.status == Http2Status.OK)
//                     assert(resp.fields.body == User(2, "BOB"))
//                 }
//             }
//         }

//         "handles path captures" in run {
//             val route = HttpRoute.getRaw("users" / Capture[Int]("id")).response(_.bodyText)
//             val ep = route.endpoint { req =>
//                 val id = req.fields.id
//                 Http2Response.ok.addField("body", s"user-$id")
//             }
//             withServer(ep) { port =>
//                 sendRequest(
//                     port,
//                     route,
//                     Http2Request(Http2Method.GET, Http2Url.fromUri("/users/42")).addField("id", 42)
//                 ).map { resp =>
//                     assert(resp.status == Http2Status.OK)
//                     assert(resp.fields.body == "user-42")
//                 }
//             }
//         }

//         "handles query parameters" in run {
//             val route = HttpRoute.getRaw("search")
//                 .request(_.query[String]("q"))
//                 .response(_.bodyText)
//             val ep = route.endpoint { req =>
//                 val q = req.fields.q
//                 Http2Response.ok.addField("body", s"searching: $q")
//             }
//             withServer(ep) { port =>
//                 sendRequest(
//                     port,
//                     route,
//                     Http2Request(Http2Method.GET, Http2Url.fromUri("/search?q=hello")).addField("q", "hello")
//                 ).map { resp =>
//                     assert(resp.status == Http2Status.OK)
//                     assert(resp.fields.body == "searching: hello")
//                 }
//             }
//         }

//         "handles multiple endpoints" in run {
//             val route1 = HttpRoute.getRaw("a").response(_.bodyText)
//             val ep1    = route1.endpoint(_ => Http2Response.ok.addField("body", "endpoint-a"))

//             val route2 = HttpRoute.getRaw("b").response(_.bodyText)
//             val ep2    = route2.endpoint(_ => Http2Response.ok.addField("body", "endpoint-b"))

//             withServer(ep1, ep2) { port =>
//                 sendRequest(port, route1, Http2Request(Http2Method.GET, Http2Url.fromUri("/a"))).map { resp1 =>
//                     assert(resp1.fields.body == "endpoint-a")
//                     sendRequest(port, route2, Http2Request(Http2Method.GET, Http2Url.fromUri("/b"))).map { resp2 =>
//                         assert(resp2.fields.body == "endpoint-b")
//                     }
//                 }
//             }
//         }

//         "handles response headers" in run {
//             val route = HttpRoute.getRaw("headers")
//                 .response(_.header[String]("X-Custom").bodyText)
//             val ep = route.endpoint { _ =>
//                 Http2Response.ok
//                     .addField("X-Custom", "custom-value")
//                     .addField("body", "with-headers")
//             }
//             withServer(ep) { port =>
//                 sendRequest(port, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/headers"))).map { resp =>
//                     assert(resp.status == Http2Status.OK)
//                     assert(resp.fields.`X-Custom` == "custom-value")
//                     assert(resp.fields.body == "with-headers")
//                 }
//             }
//         }

//         "handles empty body response" in run {
//             val route = HttpRoute.getRaw("empty")
//             val ep    = route.endpoint(_ => Http2Response(Http2Status.NoContent))
//             withServer(ep) { port =>
//                 sendRequest(port, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/empty"))).map { resp =>
//                     assert(resp.status == Http2Status.NoContent)
//                 }
//             }
//         }
//     }

//     "streaming" - {

//         "streaming response" in run {
//             val route = HttpRoute.getRaw("stream").response(_.bodyStream)
//             val ep = route.endpoint { _ =>
//                 val chunks = Stream.init(Seq(
//                     Span.fromUnsafe("hello ".getBytes("UTF-8")),
//                     Span.fromUnsafe("world".getBytes("UTF-8"))
//                 ))
//                 Http2Response.ok.addField("body", chunks)
//             }
//             withServer(ep) { port =>
//                 client.connectWith("localhost", port, ssl = false, Absent) { conn =>
//                     client.sendWith(conn, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/stream"))) { resp =>
//                         assert(resp.status == Http2Status.OK)
//                         Scope.run {
//                             resp.fields.body.run.map { chunks =>
//                                 val text = chunks.foldLeft("")((acc, span) =>
//                                     acc + new String(span.toArrayUnsafe, "UTF-8")
//                                 )
//                                 assert(text == "hello world")
//                             }
//                         }
//                     }
//                 }
//             }
//         }

//         "streaming request" in run {
//             val route = HttpRoute.postRaw("upload")
//                 .request(_.bodyStream)
//                 .response(_.bodyText)
//             val ep = route.endpoint { req =>
//                 Scope.run {
//                     req.fields.body.run.map { chunks =>
//                         val totalBytes = chunks.foldLeft(0)(_ + _.size)
//                         Http2Response.ok.addField("body", s"received $totalBytes bytes")
//                     }
//                 }
//             }
//             withServer(ep) { port =>
//                 val bodyStream = Stream.init(Seq(
//                     Span.fromUnsafe("chunk1".getBytes("UTF-8")),
//                     Span.fromUnsafe("chunk2".getBytes("UTF-8"))
//                 ))
//                 val request = Http2Request(Http2Method.POST, Http2Url.fromUri("/upload"))
//                     .addField("body", bodyStream)
//                 client.connectWith("localhost", port, ssl = false, Absent) { conn =>
//                     client.sendWith(conn, route, request) { resp =>
//                         assert(resp.status == Http2Status.OK)
//                         assert(resp.fields.body == "received 12 bytes")
//                     }
//                 }
//             }
//         }

//         "connection drop during streaming response" in run {
//             val route = HttpRoute.getRaw("slow-stream").response(_.bodyStream)
//             val ep = route.endpoint { _ =>
//                 val chunks: Stream[Span[Byte], Async & Scope] = Stream[Span[Byte], Async & Scope] {
//                     Loop(0) { i =>
//                         if i >= 1000 then Loop.done(())
//                         else
//                             Async.sleep(10.millis).andThen {
//                                 Emit.valueWith(Chunk(Span.fromUnsafe(new Array[Byte](1024))))(Loop.continue(i + 1))
//                             }
//                     }
//                 }
//                 Http2Response.ok.addField("body", chunks)
//             }
//             withServer(ep) { port =>
//                 Abort.run[Throwable] {
//                     Abort.catching[Throwable] {
//                         client.connectWith("localhost", port, ssl = false, Absent) { conn =>
//                             client.sendWith(conn, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/slow-stream"))) { resp =>
//                                 // Close connection immediately after getting headers
//                                 conn.closeNowUnsafe()(using AllowUnsafe.embrace.danger)
//                                 Scope.run {
//                                     resp.fields.body.run.map { chunks =>
//                                         // Should not get all chunks since we closed
//                                         chunks
//                                     }
//                                 }
//                             }
//                         }
//                     }
//                 }.map { result =>
//                     // Either fails with error or succeeds with partial data - both are acceptable
//                     // The key is it doesn't hang forever
//                     assert(true)
//                 }
//             }
//         }
//     }

//     "client" - {

//         "connects and sends request" in run {
//             val route = HttpRoute.getRaw("ping").response(_.bodyText)
//             val ep    = route.endpoint(_ => Http2Response.ok.addField("body", "pong"))
//             withServer(ep) { port =>
//                 sendRequest(port, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/ping"))).map { resp =>
//                     assert(resp.fields.body == "pong")
//                 }
//             }
//         }

//         "connection failure to invalid port" in run {
//             Abort.run[Throwable] {
//                 Abort.catching[Throwable] {
//                     client.connectWith("localhost", 1, ssl = false, Absent) { conn =>
//                         ()
//                     }
//                 }
//             }.map { result =>
//                 assert(result.isFailure || result.isPanic)
//             }
//         }

//         "handles connection close" in run {
//             val route = HttpRoute.getRaw("test").response(_.bodyText)
//             val ep    = route.endpoint(_ => Http2Response.ok.addField("body", "ok"))
//             withServer(ep) { port =>
//                 client.connectWith("localhost", port, ssl = false, Absent) { conn =>
//                     client.sendWith(conn, route, Http2Request(Http2Method.GET, Http2Url.fromUri("/test"))) { resp =>
//                         client.close(conn).andThen {
//                             assert(resp.fields.body == "ok")
//                         }
//                     }
//                 }
//             }
//         }
//     }

// end NettyHttp2BackendTest
