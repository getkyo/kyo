package kyo

import kyo.HttpRequest.Method
import scala.util.hashing.MurmurHash3

/** Composable interceptor for transforming HTTP request/response flows on both client and server.
  *
  * Filters wrap the `request → response` pipeline with full execution control: they can short-circuit, transform, retry, delay, or inspect
  * both buffered and streaming responses. Since `next` returns `HttpResponse[?]`, filters work uniformly regardless of whether the handler
  * produces a buffered or streaming response.
  *
  * Filters compose with `andThen` (left-to-right: the leftmost filter runs first as the outermost wrapper) and are activated for a
  * computation via `enable`. Filters work for both client and server because both share the same `request → response` pipeline shape. Both
  * HttpServer and HttpClient read the current filter from a `Local` and apply it to every request.
  *
  * Pre-built filters are organized into `HttpFilter.server` (CORS, auth, rate limiting, logging, security headers, ETag, conditional
  * requests) and `HttpFilter.client` (auth headers, logging, custom headers).
  *
  * {{{
  * // Server-side
  * HttpFilter.server.cors().andThen(HttpFilter.server.securityHeaders()).enable {
  *     HttpServer.init(handlers*)
  * }
  *
  * // Client-side
  * HttpFilter.client.bearerAuth(token).enable {
  *     HttpClient.send(request)
  * }
  * }}}
  *
  * IMPORTANT: `enable` composes onto the current filter stack (calls `andThen` on the existing filter), it does not replace. Nested
  * `enable` calls stack.
  *
  * Note: Filters see `HttpResponse[?]` — they work uniformly for buffered and streaming responses, but can only inspect body content on
  * buffered responses.
  *
  * @see
  *   [[kyo.HttpClient]]
  * @see
  *   [[kyo.HttpServer]]
  * @see
  *   [[kyo.HttpFilter.server]]
  * @see
  *   [[kyo.HttpFilter.client]]
  */
abstract class HttpFilter:

    def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using Frame): HttpResponse[?] < (Async & S)

    /** Composes this filter with another. This filter runs first (outermost). */
    final def andThen(other: HttpFilter): HttpFilter =
        val self = this
        new HttpFilter:
            def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                Frame
            ): HttpResponse[?] < (Async & S) =
                self(request, innerReq => other(innerReq, next))
        end new
    end andThen

    /** Activates this filter for the given computation. Stacks with any already-active filter. */
    final def enable[A, S](v: => A < S)(using Frame): A < S =
        HttpFilter.let(this)(v)
end HttpFilter

object HttpFilter:

    // --- No-op filter ---

    val noop: HttpFilter = new HttpFilter:
        def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
            Frame
        ): HttpResponse[?] < (Async & S) =
            next(request)

    /** Create a filter from a function that transforms the request before calling next */
    def request(f: HttpRequest[?] => HttpRequest[?]): HttpFilter =
        new HttpFilter:
            def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                Frame
            ): HttpResponse[?] < (Async & S) =
                next(f(request))

    // --- Server-side filters ---

    /** Server-side filters for handling incoming HTTP requests */
    object server:

        /** Logs requests at info level with format: "METHOD /path -> STATUS (Xms)" */
        def logging(using Frame): HttpFilter = timedLogging(_.path)

        /** Logs requests with custom handler */
        def logging(log: (HttpRequest[?], HttpResponse[?], Duration) => Unit < Sync): HttpFilter =
            timedLogging(log)

        /** Generates or propagates request ID header with random ID generator */
        def requestId(using Frame): HttpFilter =
            requestId("X-Request-ID", Random.nextStringAlphanumeric(32))

        /** Generates or propagates request ID header */
        def requestId(
            headerName: String,
            generate: => String < Sync
        ): HttpFilter =
            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using Frame) =
                    val getId = request.header(headerName) match
                        case Present(id) => id: String < Any
                        case Absent      => generate
                    getId.map { id =>
                        val reqWithId = request.addHeader(headerName, id)
                        next(reqWithId).map(_.setHeader(headerName, id))
                    }
                end apply

        /** Rate limits requests using a Meter, returning 429 when limit exceeded */
        def rateLimit(meter: Meter, retryAfter: Int = 1): HttpFilter =
            val tooManyRequests = HttpResponse(HttpStatus.TooManyRequests)
                .setHeader("Retry-After", retryAfter.toString)
            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using Frame) =
                    Abort.run(meter.tryRun(next(request))).map {
                        case Result.Success(Present(response)) => response
                        case Result.Success(Absent)            => tooManyRequests
                        case Result.Failure(_: Closed)         => tooManyRequests
                        case Result.Panic(e)                   => throw e
                    }
            end new
        end rateLimit

        /** Adds CORS (Cross-Origin Resource Sharing) headers to responses and handles preflight requests. */
        def cors(
            allowOrigin: String = "*",
            allowMethods: Seq[Method] = Seq(Method.GET, Method.POST, Method.PUT, Method.DELETE),
            allowHeaders: Seq[String] = Seq.empty,
            exposeHeaders: Seq[String] = Seq.empty,
            allowCredentials: Boolean = false,
            maxAge: Maybe[Duration] = Absent
        ): HttpFilter =
            require(allowOrigin.nonEmpty, "CORS origin cannot be empty")
            maxAge.foreach(d => require(d >= Duration.Zero, "CORS maxAge cannot be negative"))

            def addCorsHeaders(response: HttpResponse[?]): HttpResponse[?] =
                val r0 = response.setHeader("Access-Control-Allow-Origin", allowOrigin)
                val r1 = if allowCredentials then r0.setHeader("Access-Control-Allow-Credentials", "true") else r0
                if exposeHeaders.nonEmpty then r1.setHeader("Access-Control-Expose-Headers", exposeHeaders.mkString(", "))
                else r1
            end addCorsHeaders

            def preflightResponse: HttpResponse[HttpBody.Bytes] =
                val r0 = HttpResponse(HttpStatus.NoContent)
                    .setHeader("Access-Control-Allow-Origin", allowOrigin)
                    .setHeader("Access-Control-Allow-Methods", allowMethods.map(_.name).mkString(", "))
                val r1 = if allowHeaders.nonEmpty then r0.setHeader("Access-Control-Allow-Headers", allowHeaders.mkString(", ")) else r0
                val r2 = if exposeHeaders.nonEmpty then r1.setHeader("Access-Control-Expose-Headers", exposeHeaders.mkString(", ")) else r1
                val r3 = if allowCredentials then r2.setHeader("Access-Control-Allow-Credentials", "true") else r2
                maxAge match
                    case Present(d) => r3.setHeader("Access-Control-Max-Age", d.toSeconds.toString)
                    case Absent     => r3
            end preflightResponse

            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    if request.method == Method.OPTIONS then
                        preflightResponse
                    else
                        next(request).map(addCorsHeaders)
            end new
        end cors

        /** Validates HTTP Basic Authentication credentials. */
        def basicAuth(validate: (String, String) => Boolean < Async)(using Frame): HttpFilter =
            val unauthorized = HttpResponse(HttpStatus.Unauthorized).setHeader("WWW-Authenticate", "Basic")
            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    request.header("Authorization") match
                        case Present(auth) if auth.startsWith("Basic ") =>
                            try
                                val decoded = new String(java.util.Base64.getDecoder.decode(auth.drop(6)), "UTF-8")
                                decoded.split(":", 2) match
                                    case Array(username, password) =>
                                        validate(username, password).map { valid =>
                                            if valid then next(request) else unauthorized
                                        }
                                    case _ => unauthorized: HttpResponse[HttpBody.Bytes]
                                end match
                            catch case _: IllegalArgumentException => unauthorized: HttpResponse[HttpBody.Bytes]
                        case _ => unauthorized: HttpResponse[HttpBody.Bytes]
            end new
        end basicAuth

        /** Validates HTTP Bearer token authentication. */
        def bearerAuth(validate: String => Boolean < Async)(using Frame): HttpFilter =
            val unauthorized = HttpResponse(HttpStatus.Unauthorized).setHeader("WWW-Authenticate", "Bearer")
            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    request.header("Authorization") match
                        case Present(auth) if auth.startsWith("Bearer ") =>
                            val token = auth.drop(7)
                            validate(token).map { valid =>
                                if valid then next(request) else unauthorized
                            }
                        case _ => unauthorized: HttpResponse[HttpBody.Bytes]
            end new
        end bearerAuth

        /** Adds ETag headers to responses based on MurmurHash3 of body. Only for buffered responses. */
        def etag: HttpFilter =
            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    next(request).map { response =>
                        response.body.use(
                            b => response.setHeader("ETag", computeETag(b.data)),
                            _ => response // Skip ETag for streaming responses
                        )
                    }

        /** Handles conditional requests (If-None-Match) returning 304 when content unchanged (only for buffered responses). */
        def conditionalRequests: HttpFilter =
            new HttpFilter:
                def apply[S](req: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    next(req).map { response =>
                        response.body.use(
                            b =>
                                val etagValue = computeETag(b.data)
                                req.header("If-None-Match") match
                                    case Present(clientEtag) if clientEtag == etagValue =>
                                        HttpResponse(HttpStatus.NotModified)
                                    case _ =>
                                        response.setHeader("ETag", etagValue)
                                end match
                            ,
                            _ => response // Skip conditional check for streaming responses
                        )
                    }

        /** Adds common security headers to responses. */
        def securityHeaders(
            hsts: Maybe[Duration] = Absent,
            csp: Maybe[String] = Absent
        ): HttpFilter =
            new HttpFilter:
                def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using
                    Frame
                ): HttpResponse[?] < (Async & S) =
                    next(request).map { response =>
                        val r0 = response
                            .setHeader("X-Content-Type-Options", "nosniff")
                            .setHeader("X-Frame-Options", "DENY")
                            .setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
                        val r1 = hsts match
                            case Present(d) => r0.setHeader("Strict-Transport-Security", s"max-age=${d.toSeconds}")
                            case Absent     => r0
                        csp match
                            case Present(v) => r1.setHeader("Content-Security-Policy", v)
                            case Absent     => r1
                    }

    end server

    // --- Client-side filters ---

    /** Client-side filters for outgoing HTTP requests */
    object client:

        /** Logs requests at info level with format: "METHOD url -> STATUS (Xms)" */
        def logging(using Frame): HttpFilter = timedLogging(_.url)

        /** Logs requests with custom handler */
        def logging(log: (HttpRequest[?], HttpResponse[?], Duration) => Unit < Sync): HttpFilter =
            timedLogging(log)

        /** Adds a header to outgoing requests. */
        def addHeader(name: String, value: String): HttpFilter =
            HttpFilter.request(_.addHeader(name, value))

        /** Adds HTTP Basic Authentication header to outgoing requests. */
        def basicAuth(username: String, password: String): HttpFilter =
            val encoded = java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes("UTF-8"))
            HttpFilter.request(_.addHeader("Authorization", s"Basic $encoded"))

        /** Adds HTTP Bearer token header to outgoing requests. */
        def bearerAuth(token: String): HttpFilter =
            HttpFilter.request(_.addHeader("Authorization", s"Bearer $token"))

    end client

    // --- Private ---

    private val local: Local[HttpFilter] = Local.init(noop)

    // Composes filter onto the current stack — stacks, doesn't replace
    private[kyo] def let[A, S](filter: HttpFilter)(v: => A < S)(using Frame): A < S =
        local.update(_.andThen(filter))(v)

    /** Access current filter */
    private[kyo] def use[A, S](f: HttpFilter => A < S)(using Frame): A < S =
        local.use(f)

    /** Get current filter as an effect */
    private[kyo] def get(using Frame): HttpFilter < Any =
        local.get

    private def computeETag(bytes: Array[Byte]): String =
        val h1 = MurmurHash3.bytesHash(bytes, 0)
        val h2 = MurmurHash3.bytesHash(bytes, h1)
        s"\"${"%08x".format(h1)}${"%08x".format(h2)}\""
    end computeETag

    /** Logs requests with custom handler. Shared implementation for server and client. */
    private def logging(log: (HttpRequest[?], HttpResponse[?]) => Unit < Sync): HttpFilter =
        new HttpFilter:
            def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using Frame) =
                next(request).map { response =>
                    log(request, response).andThen(response)
                }

    /** Shared timed logging implementation. `formatUrl` extracts the URL portion for the log message. */
    private def timedLogging(formatUrl: HttpRequest[?] => String)(using Frame): HttpFilter =
        new HttpFilter:
            def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using Frame) =
                Clock.stopwatch.map { sw =>
                    next(request).map { response =>
                        sw.elapsed.map { dur =>
                            Log.info(s"${request.method.name} ${formatUrl(request)} -> ${response.status.code} (${dur.toMillis}ms)")
                                .andThen(response)
                        }
                    }
                }

    /** Shared custom timed logging implementation. */
    private def timedLogging(log: (HttpRequest[?], HttpResponse[?], Duration) => Unit < Sync): HttpFilter =
        new HttpFilter:
            def apply[S](request: HttpRequest[?], next: HttpRequest[?] => HttpResponse[?] < (Async & S))(using Frame) =
                Clock.stopwatch.map { sw =>
                    next(request).map { response =>
                        sw.elapsed.map { dur =>
                            log(request, response, dur).andThen(response)
                        }
                    }
                }

end HttpFilter
