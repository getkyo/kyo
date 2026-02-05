package kyo

import java.security.MessageDigest
import kyo.HttpRequest.Method

/** HTTP filter for intercepting and transforming request/response flows.
  *
  * Filters can be composed with `andThen` and enabled for computations via `enable`. Both HttpServer and HttpClient read the current filter
  * from a Local and apply it to requests.
  *
  * Usage:
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
  */
abstract class HttpFilter:
    def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame): HttpResponse < (Async & S)

    /** Compose this filter with another (this runs first, wrapping other) */
    final def andThen(other: HttpFilter): HttpFilter =
        val self = this
        new HttpFilter:
            def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame): HttpResponse < (Async & S) =
                self(request, innerReq => other(innerReq, next))
    end andThen

    /** Enable this filter for the given computation */
    final def enable[A, S](v: => A < S)(using Frame): A < S =
        HttpFilter.let(this)(v)
end HttpFilter

object HttpFilter:

    /** No-op filter - passes request through unchanged */
    val noop: HttpFilter = new HttpFilter:
        def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame): HttpResponse < (Async & S) =
            next(request)

    /** Create a filter from a function that transforms request before calling next */
    def init(f: HttpRequest => HttpRequest): HttpFilter =
        new HttpFilter:
            def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame): HttpResponse < (Async & S) =
                next(f(request))

    // --- Local-based API ---

    private val local: Local[HttpFilter] = Local.init(noop)

    /** Run computation with filter added to the current filter stack */
    def let[A, S](filter: HttpFilter)(v: => A < S)(using Frame): A < S =
        local.use { current =>
            local.let(current.andThen(filter))(v)
        }

    /** Access current filter */
    def use[A, S](f: HttpFilter => A < S)(using Frame): A < S =
        local.use(f)

    /** Get current filter as an effect */
    def get(using Frame): HttpFilter < Any =
        local.get

    // --- Shared utilities ---

    private val md5ThreadLocal = new ThreadLocal[MessageDigest]:
        override def initialValue() = MessageDigest.getInstance("MD5")

    private def computeETag(bytes: Array[Byte]): String =
        val md5 = md5ThreadLocal.get()
        md5.reset()
        val hash = md5.digest(bytes)
        "\"" + hash.map("%02x".format(_)).mkString + "\""
    end computeETag

    // --- Shared filters ---

    /** Logs requests with custom handler. Shared implementation for server and client. */
    def logging(log: (HttpRequest, HttpResponse, Duration) => Unit < Sync): HttpFilter =
        new HttpFilter:
            def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame) =
                Clock.now.map { start =>
                    next(request).map { response =>
                        Clock.now.map { end =>
                            log(request, response, end - start).andThen(response)
                        }
                    }
                }

    // --- Server-side filters ---

    /** Server-side filters for handling incoming HTTP requests */
    object server:

        /** Logs requests at info level with format: "METHOD /path -> STATUS (Xms)" */
        def logging(using Frame): HttpFilter =
            HttpFilter.logging((req, resp, dur) =>
                Log.info(s"${req.method.name} ${req.path} -> ${resp.status.code} (${dur.toMillis}ms)")
            )

        /** Logs requests with custom handler */
        def logging(log: (HttpRequest, HttpResponse, Duration) => Unit < Sync): HttpFilter =
            HttpFilter.logging(log)

        /** Generates or propagates request ID header with UUID generator */
        def requestId(using Frame): HttpFilter =
            requestId("X-Request-ID", Sync.defer(java.util.UUID.randomUUID().toString))

        /** Generates or propagates request ID header */
        def requestId(
            headerName: String,
            generate: => String < Sync
        ): HttpFilter =
            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame) =
                    val getId = request.header(headerName) match
                        case Present(id) => id: String < Any
                        case Absent      => generate
                    getId.map { id =>
                        val reqWithId = request.addHeader(headerName, id)
                        next(reqWithId).map(_.addHeader(headerName, id))
                    }
                end apply

        /** Rate limits requests using a Meter, returning 429 when limit exceeded */
        def rateLimit(meter: Meter, retryAfter: Int = 1): HttpFilter =
            val tooManyRequests = HttpResponse(HttpResponse.Status.TooManyRequests)
                .addHeader("Retry-After", retryAfter.toString)
            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame) =
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

            def addCorsHeaders(response: HttpResponse): HttpResponse =
                var r = response.addHeader("Access-Control-Allow-Origin", allowOrigin)
                if allowCredentials then
                    r = r.addHeader("Access-Control-Allow-Credentials", "true")
                if exposeHeaders.nonEmpty then
                    r = r.addHeader("Access-Control-Expose-Headers", exposeHeaders.mkString(", "))
                r
            end addCorsHeaders

            def preflightResponse: HttpResponse =
                var response = HttpResponse(HttpResponse.Status.NoContent)
                    .addHeader("Access-Control-Allow-Origin", allowOrigin)
                    .addHeader("Access-Control-Allow-Methods", allowMethods.map(_.name).mkString(", "))
                if allowHeaders.nonEmpty then
                    response = response.addHeader("Access-Control-Allow-Headers", allowHeaders.mkString(", "))
                if exposeHeaders.nonEmpty then
                    response = response.addHeader("Access-Control-Expose-Headers", exposeHeaders.mkString(", "))
                if allowCredentials then
                    response = response.addHeader("Access-Control-Allow-Credentials", "true")
                maxAge.foreach(d => response = response.addHeader("Access-Control-Max-Age", d.toSeconds.toString))
                response
            end preflightResponse

            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using
                    Frame
                ): HttpResponse < (Async & S) =
                    if request.method == Method.OPTIONS then
                        preflightResponse
                    else
                        next(request).map(addCorsHeaders)
            end new
        end cors

        /** Validates HTTP Basic Authentication credentials. */
        def basicAuth(validate: (String, String) => Boolean < Async)(using Frame): HttpFilter =
            val unauthorized = HttpResponse(HttpResponse.Status.Unauthorized).addHeader("WWW-Authenticate", "Basic")
            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using
                    Frame
                ): HttpResponse < (Async & S) =
                    request.header("Authorization") match
                        case Present(auth) if auth.startsWith("Basic ") =>
                            val decoded = new String(java.util.Base64.getDecoder.decode(auth.drop(6)), "UTF-8")
                            decoded.split(":", 2) match
                                case Array(username, password) =>
                                    validate(username, password).map { valid =>
                                        if valid then next(request) else unauthorized
                                    }
                                case _ => unauthorized: HttpResponse
                            end match
                        case _ => unauthorized: HttpResponse
            end new
        end basicAuth

        /** Validates HTTP Bearer token authentication. */
        def bearerAuth(validate: String => Boolean < Async)(using Frame): HttpFilter =
            val unauthorized = HttpResponse(HttpResponse.Status.Unauthorized).addHeader("WWW-Authenticate", "Bearer")
            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using
                    Frame
                ): HttpResponse < (Async & S) =
                    request.header("Authorization") match
                        case Present(auth) if auth.startsWith("Bearer ") =>
                            val token = auth.drop(7)
                            validate(token).map { valid =>
                                if valid then next(request) else unauthorized
                            }
                        case _ => unauthorized: HttpResponse
            end new
        end bearerAuth

        /** Adds ETag headers to responses based on MD5 hash of body. */
        def etag: HttpFilter =
            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using
                    Frame
                ): HttpResponse < (Async & S) =
                    next(request).map { response =>
                        if response.isStreaming then response
                        else
                            Abort.run(response.bodyBytes).map {
                                case Result.Success(bytes) =>
                                    val etagValue = computeETag(bytes.toArrayUnsafe)
                                    response.addHeader("ETag", etagValue)
                                case _ => response
                            }
                    }

        /** Handles conditional requests (If-None-Match) returning 304 when content unchanged. */
        def conditionalRequests: HttpFilter =
            new HttpFilter:
                def apply[S](req: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using Frame): HttpResponse < (Async & S) =
                    next(req).map { response =>
                        if response.isStreaming then response
                        else
                            Abort.run(response.bodyBytes).map {
                                case Result.Success(bytes) =>
                                    val etagValue = computeETag(bytes.toArrayUnsafe)
                                    req.header("If-None-Match") match
                                        case Present(clientEtag) if clientEtag == etagValue =>
                                            HttpResponse(HttpResponse.Status.NotModified)
                                        case _ =>
                                            response.addHeader("ETag", etagValue)
                                    end match
                                case _ => response
                            }
                    }

        /** Adds common security headers to responses. */
        def securityHeaders(
            hsts: Maybe[Duration] = Absent,
            csp: Maybe[String] = Absent
        ): HttpFilter =
            new HttpFilter:
                def apply[S](request: HttpRequest, next: HttpRequest => HttpResponse < (Async & S))(using
                    Frame
                ): HttpResponse < (Async & S) =
                    next(request).map { response =>
                        var r = response
                            .addHeader("X-Content-Type-Options", "nosniff")
                            .addHeader("X-Frame-Options", "DENY")
                            .addHeader("Referrer-Policy", "strict-origin-when-cross-origin")
                        hsts.foreach(d => r = r.addHeader("Strict-Transport-Security", s"max-age=${d.toSeconds}"))
                        csp.foreach(v => r = r.addHeader("Content-Security-Policy", v))
                        r
                    }

    end server

    // --- Client-side filters ---

    /** Client-side filters for outgoing HTTP requests */
    object client:

        /** Logs requests at info level with format: "METHOD url -> STATUS (Xms)" */
        def logging(using Frame): HttpFilter =
            logging((req, resp, dur) =>
                Log.info(s"${req.method.name} ${req.url} -> ${resp.status.code} (${dur.toMillis}ms)")
            )

        /** Logs requests with custom handler */
        def logging(log: (HttpRequest, HttpResponse, Duration) => Unit < Sync): HttpFilter =
            HttpFilter.logging(log)

        /** Adds a header to outgoing requests. */
        def addHeader(name: String, value: String): HttpFilter =
            init(_.addHeader(name, value))

        /** Adds HTTP Basic Authentication header to outgoing requests. */
        def basicAuth(username: String, password: String): HttpFilter =
            val encoded = java.util.Base64.getEncoder.encodeToString(s"$username:$password".getBytes("UTF-8"))
            init(_.addHeader("Authorization", s"Basic $encoded"))

        /** Adds HTTP Bearer token header to outgoing requests. */
        def bearerAuth(token: String): HttpFilter =
            init(_.addHeader("Authorization", s"Bearer $token"))

        /** Adds a custom header to all outgoing requests. */
        def customHeader(name: String, value: String): HttpFilter =
            init(_.addHeader(name, value))

    end client

end HttpFilter
