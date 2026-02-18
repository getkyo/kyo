package kyo

import kyo.internal.BaseKyoCoreTest
import org.scalatest.NonImplicitAssertions
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

abstract class Test extends AsyncFreeSpec with NonImplicitAssertions with BaseKyoCoreTest:

    type Assertion = org.scalatest.Assertion
    def assertionSuccess              = succeed
    def assertionFailure(msg: String) = fail(msg)

    override given executionContext: ExecutionContext = kyo.internal.Platform.executionContext

    private lazy val testClient: HttpClient =
        import AllowUnsafe.embrace.danger
        HttpClient.Unsafe.init(backend = PlatformTestBackend.client)

    protected def useTestClient: Boolean = true

    override def run(v: Future[Assertion] < (Abort[Any] & Async & Scope))(using Frame): Future[Assertion] =
        if useTestClient then super.run(HttpClient.let(testClient)(v))
        else super.run(v)

    // Server lifecycle utilities

    /** Starts a test server on a random available port. The server is automatically stopped when the Scope closes.
      * @return
      *   the actual bound port
      */
    def startTestServer(handlers: HttpHandler[?]*)(using Frame): Int < (Async & Scope) =
        HttpServer.init(HttpServer.Config(port = 0), PlatformTestBackend.server)(handlers*).map(_.port)

    /** Runs a test with a server, ensuring cleanup.
      * @param handlers
      *   the handlers to register on the server
      * @param test
      *   function receiving the port, returns assertion
      */
    def withTestServer(handlers: HttpHandler[?]*)(test: Int => Assertion < Async)(using Frame): Assertion < (Async & Scope) =
        startTestServer(handlers*).map(test)

    // Request helpers

    /** Makes a GET request to localhost on the given port, returns HttpResponse. */
    def testGet(port: Int, path: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpClient.send(HttpRequest.get(s"http://localhost:$port$path"))

    /** Makes a GET request and parses the response body as type A. */
    def testGetAs[A: Schema](port: Int, path: String)(using Frame): A < (Async & Abort[HttpError]) =
        HttpClient.get[A](s"http://localhost:$port$path")

    /** Makes a POST request to localhost on the given port, returns HttpResponse. */
    def testPost[A: Schema](port: Int, path: String, body: A)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpClient.send(HttpRequest.post(s"http://localhost:$port$path", body))

    /** Makes a POST request and parses the response body as type B. */
    def testPostAs[A: Schema, B: Schema](port: Int, path: String, body: A)(using Frame): B < (Async & Abort[HttpError]) =
        HttpClient.post[B, A](s"http://localhost:$port$path", body)

    /** Makes a PUT request to localhost on the given port, returns HttpResponse. */
    def testPut[A: Schema](port: Int, path: String, body: A)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpClient.send(HttpRequest.put(s"http://localhost:$port$path", body))

    /** Makes a DELETE request to localhost on the given port, returns HttpResponse. */
    def testDelete(port: Int, path: String)(using Frame): HttpResponse[HttpBody.Bytes] < (Async & Abort[HttpError]) =
        HttpClient.send(HttpRequest.delete(s"http://localhost:$port$path"))

    // Echo handler for testing client behavior

    /** A handler that echoes request details back in the response. Response body contains: method, path, headers, and body.
      */
    def echoHandler(using Frame): HttpHandler[Any] =
        HttpHandler.get("/*") { in =>
            val info = Map(
                "method" -> in.request.method.toString,
                "path"   -> in.request.path,
                "headers" -> {
                    val sb    = new StringBuilder
                    var first = true
                    in.request.headers.foreach { (k, v) =>
                        if !first then sb.append(", ")
                        sb.append(s"$k: $v")
                        first = false
                    }
                    sb.toString
                },
                "body" -> in.request.bodyText
            )
            HttpResponse.ok(info.map((k, v) => s"$k=$v").mkString("\n"))
        }

    /** An echo handler that accepts any HTTP method. */
    def echoHandlerAllMethods(using Frame): Seq[HttpHandler[Any]] =
        import HttpRequest.Method.*
        Seq(GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS).map { method =>
            HttpHandler.init(method, "/*") { in =>
                val info = Map(
                    "method" -> in.request.method.toString,
                    "path"   -> in.request.path,
                    "headers" -> {
                        val sb    = new StringBuilder
                        var first = true
                        in.request.headers.foreach { (k, v) =>
                            if !first then sb.append(", ")
                            sb.append(s"$k: $v")
                            first = false
                        }
                        sb.toString
                    },
                    "body" -> in.request.bodyText
                )
                HttpResponse.ok(info.map((k, v) => s"$k=$v").mkString("\n"))
            }
        }
    end echoHandlerAllMethods

    // Response assertion helpers

    /** Asserts that the response has the expected status. */
    def assertStatus(response: HttpResponse[?], expected: HttpStatus): Assertion =
        assert(response.status == expected, s"Expected status $expected but got ${response.status}")

    /** Asserts that the response body text matches the expected string. */
    def assertBodyText(response: HttpResponse[HttpBody.Bytes], expected: String): Assertion =
        val actual = response.bodyText
        assert(actual == expected, s"Expected body '$expected' but got '$actual'")

    /** Asserts that the response body text contains the expected substring. */
    def assertBodyContains(response: HttpResponse[HttpBody.Bytes], expected: String): Assertion =
        val actual = response.bodyText
        assert(actual.contains(expected), s"Expected body to contain '$expected' but got '$actual'")

    /** Asserts that the response body deserializes to the expected value. */
    def assertBody[A: Schema](response: HttpResponse[HttpBody.Bytes], expected: A)(using CanEqual[A, A]): Assertion =
        Abort.run(response.bodyAs[A]).eval match
            case Result.Success(actual) => assert(actual == expected, s"Expected body $expected but got $actual")
            case Result.Failure(e)      => fail(s"Unexpected error accessing body: $e")
            case Result.Panic(ex)       => throw ex

    /** Asserts that the response has a header with the expected value. */
    def assertHeader(response: HttpResponse[?], name: String, expected: String): Assertion =
        response.header(name) match
            case Present(actual) =>
                assert(actual == expected, s"Expected header '$name: $expected' but got '$name: $actual'")
            case Absent =>
                fail(s"Expected header '$name' but it was not present")

    /** Asserts that the response has the specified header (any value). */
    def assertHasHeader(response: HttpResponse[?], name: String): Assertion =
        assert(response.header(name).isDefined, s"Expected header '$name' to be present")

    /** Asserts that the response has a cookie with the expected value. */
    def assertCookie(response: HttpResponse[?], name: String, expected: String): Assertion =
        response.cookie(name) match
            case Present(cookie) =>
                assert(cookie.value == expected, s"Expected cookie '$name=$expected' but got '$name=${cookie.value}'")
            case Absent =>
                fail(s"Expected cookie '$name' but it was not present")

    /** Asserts that the response has the specified cookie (any value). */
    def assertHasCookie(response: HttpResponse[?], name: String): Assertion =
        assert(response.cookie(name).isDefined, s"Expected cookie '$name' to be present")

    // Common test handlers (using API methods where available)

    /** A handler that returns JSON data. */
    def jsonHandler[A: Schema](path: String, data: A)(using Frame): HttpHandler[Any] =
        HttpHandler.get(path) { _ =>
            HttpResponse.ok(data)
        }

    /** A handler that delays before responding. */
    def delayedHandler(path: String, delay: Duration)(using Frame): HttpHandler[Any] =
        HttpHandler.get(path) { _ =>
            Async.delay(delay)(HttpResponse.ok("delayed"))
        }

    /** A handler that never responds (blocks forever). Useful for timeout tests. */
    def neverRespondHandler(path: String)(using Frame): HttpHandler[Any] =
        HttpHandler.get(path) { _ =>
            Latch.init(1).map { latch =>
                latch.await.andThen(HttpResponse.ok("never"))
            }
        }

    /** A handler that throws an exception. */
    def errorHandler(path: String)(using Frame): HttpHandler[Any] =
        HttpHandler.get(path) { _ =>
            throw new RuntimeException("test error")
        }

end Test
