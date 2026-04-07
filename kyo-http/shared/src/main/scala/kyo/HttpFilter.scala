package kyo

import kyo.*

/** Composable middleware that intercepts requests and responses, with compile-time field tracking.
  *
  * HttpFilter has five type parameters that look complex but encode a simple idea: a filter can *require* fields from the request
  * (`ReqUse`), *add* fields for downstream handlers (`ReqAdd`), and similarly for responses (`ResUse`, `ResAdd`). The fifth parameter `E`
  * tracks error types the filter may introduce.
  *
  * For example, `basicAuth` requires `"authorization" ~ Maybe[String]` on the request and adds `"user" ~ String` for downstream — the
  * compiler enforces that you can't use `basicAuth` without first declaring the authorization header on the route with
  * `.request(_.headerOpt[String]("authorization"))`.
  *
  * Three abstract subclasses simplify common patterns:
  *   - `HttpFilter.Request[ReqUse, ReqAdd, E]` — transforms only the request (response passes through)
  *   - `HttpFilter.Response[ResUse, ResAdd, E]` — transforms only the response (request passes through)
  *   - `HttpFilter.Passthrough[E]` — can observe/modify both without field requirements (used by `cors`, `logging`, etc.)
  *
  * Filters compose with `andThen`, which intersects their type parameters. Composition is optimized: `noop.andThen(f)` and
  * `f.andThen(noop)` return the non-noop filter without wrapping. Server filters can short-circuit by aborting with `HttpResponse.Halt` to
  * send a response immediately (e.g., 401 Unauthorized) without calling the next handler.
  *
  * Server-side filters live in `HttpFilter.server` (authentication, rate limiting, CORS, logging). Client-side filters live in
  * `HttpFilter.client` (attaching auth headers, logging outgoing requests).
  *
  * @tparam ReqUse
  *   fields required from the incoming request
  * @tparam ReqAdd
  *   fields added to the request for downstream handlers
  * @tparam ResUse
  *   fields required from the response (before transformation)
  * @tparam ResAdd
  *   fields added to the response (after transformation)
  * @tparam E
  *   error types this filter may introduce
  *
  * @see
  *   [[kyo.HttpRoute.filter]] How filters are applied to routes
  * @see
  *   [[kyo.HttpResponse.Halt]] The short-circuit mechanism for filters
  */
sealed abstract class HttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, +E]:

    def apply[In, Out, E2](
        request: HttpRequest[In & ReqUse],
        next: HttpRequest[In & ReqUse & ReqAdd] => HttpResponse[Out & ResUse] < (Async & Abort[E2 | HttpResponse.Halt])
    )(using Frame): HttpResponse[Out & ResUse & ResAdd] < (Async & Abort[E | E2 | HttpResponse.Halt])

    /** Composes this filter with another, running this filter first, then `that`. Type parameters are intersected: the composed filter
      * requires the union of both filters' required fields and adds the union of both filters' added fields. If either filter is `noop`,
      * the other is returned directly without wrapping.
      */
    final def andThen[RI2, RO2, SI2, SO2, E2](
        that: HttpFilter[RI2, RO2, SI2, SO2, E2]
    ): HttpFilter[ReqUse & RI2, ReqAdd & RO2, ResUse & SI2, ResAdd & SO2, E | E2] =
        if this eq HttpFilter.noop then that.asInstanceOf[HttpFilter[ReqUse & RI2, ReqAdd & RO2, ResUse & SI2, ResAdd & SO2, E | E2]]
        else if that eq HttpFilter.noop then this.asInstanceOf[HttpFilter[ReqUse & RI2, ReqAdd & RO2, ResUse & SI2, ResAdd & SO2, E | E2]]
        else
            val self = this
            new HttpFilter[ReqUse & RI2, ReqAdd & RO2, ResUse & SI2, ResAdd & SO2, E | E2]:
                def apply[In, Out, E3](
                    request: HttpRequest[In & ReqUse & RI2],
                    next: HttpRequest[In & ReqUse & RI2 & ReqAdd & RO2] => HttpResponse[
                        Out & ResUse & SI2
                    ] < (Async & Abort[E3 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out & ResUse & SI2 & ResAdd & SO2] < (Async & Abort[E | E2 | E3 | HttpResponse.Halt]) =
                    self(request, req => that(req, next))
            end new
    end andThen

end HttpFilter

object HttpFilter:

    abstract class Request[ReqUse, ReqAdd, +E]
        extends HttpFilter[ReqUse, ReqAdd, Any, Any, E]

    abstract class Response[ResUse, ResAdd, +E]
        extends HttpFilter[Any, Any, ResUse, ResAdd, E]

    abstract class Passthrough[+E]
        extends HttpFilter[Any, Any, Any, Any, E]:
        final def andThen[E2](that: Passthrough[E2]): Passthrough[E | E2] =
            if this eq HttpFilter.noop then that
            else if that eq HttpFilter.noop then this
            else
                val self = this
                new Passthrough[E | E2]:
                    def apply[In, Out, E3](
                        request: HttpRequest[In],
                        next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E3 | HttpResponse.Halt])
                    )(using Frame): HttpResponse[Out] < (Async & Abort[E | E2 | E3 | HttpResponse.Halt]) =
                        self(request, req => that(req, next))
                end new
    end Passthrough

    val noop: Passthrough[Nothing] =
        new Passthrough[Nothing]:
            def apply[In, Out, E2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                next(request)

    // --- Server-side filters ---

    object server:

        private val unauthorizedBasic = HttpResponse.Halt(
            HttpResponse.unauthorized
                .setHeader("WWW-Authenticate", "Basic")
        )

        private val unauthorizedBearer = HttpResponse.Halt(
            HttpResponse.unauthorized
                .setHeader("WWW-Authenticate", "Bearer")
        )

        /** Validates HTTP Basic authentication credentials. Reads the `"authorization"` field from the request, decodes the Base64
          * username:password pair, and calls `validate`. On success, adds `"user" ~ String` to the request for downstream handlers. On
          * failure, short-circuits with 401 and `WWW-Authenticate: Basic`.
          */
        def basicAuth(validate: (String, String) => Boolean < Async): Request["authorization" ~ Maybe[String], "user" ~ String, Nothing] =
            new Request["authorization" ~ Maybe[String], "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "authorization" ~ Maybe[String]],
                    next: HttpRequest[In & "authorization" ~ Maybe[String] & "user" ~ String] => HttpResponse[
                        Out
                    ] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    request.fields.authorization match
                        case Present(auth)
                            if auth.length > 6 && auth.regionMatches(true, 0, "Basic ", 0, 6) =>
                            try
                                val decoded = new String(
                                    java.util.Base64.getDecoder.decode(auth.substring(6)),
                                    "UTF-8"
                                )
                                val colonIdx = decoded.indexOf(':')
                                if colonIdx >= 0 then
                                    val username = decoded.substring(0, colonIdx)
                                    val password = decoded.substring(colonIdx + 1)
                                    validate(username, password).map { valid =>
                                        if valid then next(request.addField("user", username))
                                        else Abort.fail(unauthorizedBasic)
                                    }
                                else Abort.fail(unauthorizedBasic)
                                end if
                            catch case _: IllegalArgumentException => Abort.fail(unauthorizedBasic)
                        case _ => Abort.fail(unauthorizedBasic)
                    end match
                end apply

        /** Validates a Bearer token. Reads the `"authorization"` field from the request and extracts the token after `"Bearer "`. On
          * failure, short-circuits with 401 and `WWW-Authenticate: Bearer`.
          */
        def bearerAuth(validate: String => Boolean < Async): Request["authorization" ~ Maybe[String], Any, Nothing] =
            new Request["authorization" ~ Maybe[String], Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "authorization" ~ Maybe[String]],
                    next: HttpRequest[In & "authorization" ~ Maybe[String]] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    request.fields.authorization match
                        case Present(auth)
                            if auth.length > 7 && auth.regionMatches(true, 0, "Bearer ", 0, 7) =>
                            validate(auth.substring(7)).map { valid =>
                                if valid then next(request)
                                else Abort.fail(unauthorizedBearer)
                            }
                        case _ => Abort.fail(unauthorizedBearer)
                    end match
                end apply

        /** Rate-limits requests using a Meter. When the meter's limit is exceeded (or the meter is closed), short-circuits with 429 Too
          * Many Requests and a `Retry-After` header.
          */
        def rateLimit(meter: Meter, retryAfter: Int = 1): Passthrough[Nothing] =
            val tooMany = HttpResponse.Halt(
                HttpResponse.tooManyRequests
                    .setHeader("Retry-After", retryAfter.toString)
            )
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    // Meter.tryRun introduces Abort[Closed] which must be handled here to avoid
                    // leaking it into the filter's return type. A closed meter is treated as exhausted.
                    meter.tryRun(next(request)).handle(Abort.run[Closed]).map {
                        case Result.Success(Present(res)) => res
                        case _                            => Abort.fail(tooMany)
                    }
            end new
        end rateLimit

        /** Handles CORS preflight (OPTIONS) requests and adds CORS response headers. For server-wide CORS that applies to all routes
          * without per-route filter setup, configure `HttpServerConfig.cors` instead.
          */
        def cors: Passthrough[Nothing] = cors()

        def cors(
            allowOrigin: String = "*",
            allowMethods: Seq[HttpMethod] = Seq(
                HttpMethod.GET,
                HttpMethod.POST,
                HttpMethod.PUT,
                HttpMethod.DELETE
            ),
            allowHeaders: Seq[String] = Seq.empty,
            exposeHeaders: Seq[String] = Seq.empty,
            allowCredentials: Boolean = false,
            maxAge: Maybe[Duration] = Absent
        ): Passthrough[Nothing] =
            val preflight =
                val r0 = HttpResponse.noContent
                    .setHeader("Access-Control-Allow-Origin", allowOrigin)
                    .setHeader("Access-Control-Allow-Methods", allowMethods.map(_.name).mkString(", "))
                val r1 =
                    if allowHeaders.nonEmpty
                    then r0.setHeader("Access-Control-Allow-Headers", allowHeaders.mkString(", "))
                    else r0
                val r2 =
                    if exposeHeaders.nonEmpty
                    then r1.setHeader("Access-Control-Expose-Headers", exposeHeaders.mkString(", "))
                    else r1
                val r3 =
                    if allowCredentials
                    then r2.setHeader("Access-Control-Allow-Credentials", "true")
                    else r2
                val r4 = maxAge match
                    case Present(d) => r3.setHeader("Access-Control-Max-Age", d.toSeconds.toString)
                    case Absent     => r3
                HttpResponse.Halt(r4)
            end preflight
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    def addCorsHeaders[F](res: HttpResponse[F]): HttpResponse[F] =
                        val r0 = res.setHeader("Access-Control-Allow-Origin", allowOrigin)
                        val r1 =
                            if allowCredentials then r0.setHeader("Access-Control-Allow-Credentials", "true")
                            else r0
                        if exposeHeaders.nonEmpty
                        then r1.setHeader("Access-Control-Expose-Headers", exposeHeaders.mkString(", "))
                        else r1
                    end addCorsHeaders

                    if request.method == HttpMethod.OPTIONS then
                        Abort.fail(preflight)
                    else
                        next(request).map(addCorsHeaders)
                    end if
                end apply
            end new
        end cors

        /** Adds standard security headers to responses. */
        def securityHeaders: Passthrough[Nothing] = securityHeaders()

        def securityHeaders(
            hsts: Maybe[Duration] = Absent,
            csp: Maybe[String] = Absent
        ): Passthrough[Nothing] =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request).map { res =>
                        val r0 = res
                            .setHeader("X-Content-Type-Options", "nosniff")
                            .setHeader("X-Frame-Options", "DENY")
                            .setHeader("Referrer-Policy", "strict-origin-when-cross-origin")
                        val r1 = hsts match
                            case Present(d) =>
                                r0.setHeader("Strict-Transport-Security", s"max-age=${d.toSeconds}")
                            case Absent => r0
                        csp match
                            case Present(v) => r1.setHeader("Content-Security-Policy", v)
                            case Absent     => r1
                    }

        /** Logs requests: "METHOD /path -> STATUS (Xms)" */
        def logging: Passthrough[Nothing] =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    Clock.stopwatch.map { sw =>
                        next(request).map { res =>
                            sw.elapsed.map { dur =>
                                Log.info(
                                    s"${request.method.name} ${request.path} -> ${res.status.code} (${dur.toMillis}ms)"
                                ).andThen(res)
                            }
                        }
                    }

        /** Generates or propagates request ID header. */
        def requestId: Passthrough[Nothing] = requestId("X-Request-ID")

        def requestId(headerName: String): Passthrough[Nothing] =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    val getId = request.headers.get(headerName) match
                        case Present(id) => id: String < Any
                        case Absent      => Random.nextStringAlphanumeric(32)
                    getId.map { id =>
                        next(request.setHeader(headerName, id)).map(_.setHeader(headerName, id))
                    }
                end apply

    end server

    // --- Client-side filters ---

    object client:

        /** Adds Bearer token header to outgoing requests. */
        def bearerAuth(token: String): Passthrough[Nothing] =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader("Authorization", s"Bearer $token"))

        /** Adds Basic auth header to outgoing requests. */
        def basicAuth(username: String, password: String): Passthrough[Nothing] =
            val encoded = java.util.Base64.getEncoder.encodeToString(
                s"$username:$password".getBytes("UTF-8")
            )
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader("Authorization", s"Basic $encoded"))
            end new
        end basicAuth

        /** Adds a custom header to outgoing requests. */
        def addHeader(name: String, value: String): Passthrough[Nothing] =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader(name, value))

        /** Logs requests: "METHOD /path -> STATUS (Xms)" */
        def logging: Passthrough[Nothing] =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    Clock.stopwatch.map { sw =>
                        next(request).map { res =>
                            sw.elapsed.map { dur =>
                                Log.info(
                                    s"${request.method.name} ${request.path} -> ${res.status.code} (${dur.toMillis}ms)"
                                ).andThen(res)
                            }
                        }
                    }

    end client

    /** Adapts a composed filter into the normalized form for storage in HttpRoute. */
    def adapt[In, ReqAdd, Out, ResAdd, E](
        composed: HttpFilter[In, ReqAdd, Out, ResAdd, E]
    ): HttpFilter[In & ReqAdd, Any, Out & ResAdd, Any, E] =
        new HttpFilter[In & ReqAdd, Any, Out & ResAdd, Any, E]:
            def apply[In2, Out2, E2](
                request: HttpRequest[In2 & (In & ReqAdd)],
                next: HttpRequest[In2 & (In & ReqAdd)] => HttpResponse[Out2 & (Out & ResAdd)] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out2 & (Out & ResAdd)] < (Async & Abort[E | E2 | HttpResponse.Halt]) =
                composed[In2 & ReqAdd, Out2 & ResAdd, E2](request, next)

end HttpFilter
