package kyo

import kyo.*
import kyo.Record2.~

sealed abstract class HttpFilter[ReqIn, ReqOut, ResIn, ResOut, +E]:

    def apply[In, Out, E2](
        request: HttpRequest[In & ReqIn],
        next: HttpRequest[In & ReqIn & ReqOut] => HttpResponse[Out & ResIn] < (Async & Abort[E2 | HttpResponse.Halt])
    )(using Frame): HttpResponse[Out & ResIn & ResOut] < (Async & Abort[E | E2 | HttpResponse.Halt])

    final def andThen[RI2, RO2, SI2, SO2, E2](
        that: HttpFilter[RI2, RO2, SI2, SO2, E2]
    ): HttpFilter[ReqIn & RI2, ReqOut & RO2, ResIn & SI2, ResOut & SO2, E | E2] =
        val self = this
        new HttpFilter[ReqIn & RI2, ReqOut & RO2, ResIn & SI2, ResOut & SO2, E | E2]:
            def apply[In, Out, E3](
                request: HttpRequest[In & ReqIn & RI2],
                next: HttpRequest[In & ReqIn & RI2 & ReqOut & RO2] => HttpResponse[
                    Out & ResIn & SI2
                ] < (Async & Abort[E3 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out & ResIn & SI2 & ResOut & SO2] < (Async & Abort[E | E2 | E3 | HttpResponse.Halt]) =
                self(request, req => that(req, next))
        end new
    end andThen

end HttpFilter

object HttpFilter:

    abstract class Request[ReqIn, ReqOut, +E]
        extends HttpFilter[ReqIn, ReqOut, Any, Any, E]

    abstract class Response[ResIn, ResOut, +E]
        extends HttpFilter[Any, Any, ResIn, ResOut, E]

    abstract class Passthrough[+E]
        extends HttpFilter[Any, Any, Any, Any, E]

    val noop: Passthrough[Nothing] =
        new Passthrough[Nothing]:
            def apply[In, Out, E2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                next(request)

    // --- Server-side filters ---

    object server:

        /** Validates Basic auth credentials. Reads "authorization" from request fields, adds "user" ~ String for downstream handlers.
          * Short-circuits with Abort[HttpResponse.Halt] on failure.
          */
        def basicAuth(validate: (String, String) => Boolean < Async) =
            new Request["authorization" ~ Maybe[String], "user" ~ String, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "authorization" ~ Maybe[String]],
                    next: HttpRequest[In & "authorization" ~ Maybe[String] & "user" ~ String] => HttpResponse[
                        Out
                    ] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    val unauthorized = HttpResponse.Halt(
                        HttpResponse.unauthorized
                            .setHeader("WWW-Authenticate", "Basic")
                    )
                    request.fields.authorization match
                        case Present(auth) if auth.length > 6 && auth.substring(0, 6).equalsIgnoreCase("Basic ") =>
                            try
                                val decoded = new String(
                                    java.util.Base64.getDecoder.decode(auth.drop(6)),
                                    "UTF-8"
                                )
                                decoded.split(":", 2) match
                                    case Array(username, password) =>
                                        validate(username, password).map { valid =>
                                            if valid then next(request.addField("user", username))
                                            else Abort.fail(unauthorized)
                                        }
                                    case _ => Abort.fail(unauthorized)
                                end match
                            catch case _: IllegalArgumentException => Abort.fail(unauthorized)
                        case _ => Abort.fail(unauthorized)
                    end match
                end apply

        /** Validates Bearer token. Reads "authorization" from request fields. Short-circuits with Abort[HttpResponse.Halt] on failure.
          */
        def bearerAuth(validate: String => Boolean < Async) =
            new Request["authorization" ~ Maybe[String], Any, Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In & "authorization" ~ Maybe[String]],
                    next: HttpRequest[In & "authorization" ~ Maybe[String]] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    val unauthorized = HttpResponse.Halt(
                        HttpResponse.unauthorized
                            .setHeader("WWW-Authenticate", "Bearer")
                    )
                    request.fields.authorization match
                        case Present(auth) if auth.length > 7 && auth.substring(0, 7).equalsIgnoreCase("Bearer ") =>
                            validate(auth.drop(7)).map { valid =>
                                if valid then next(request)
                                else Abort.fail(unauthorized)
                            }
                        case _ => Abort.fail(unauthorized)
                    end match
                end apply

        /** Rate limits using a Meter. Short-circuits with Abort[HttpResponse.Halt] when limit exceeded.
          */
        def rateLimit(meter: Meter, retryAfter: Int = 1) =
            val tooMany = HttpResponse.Halt(
                HttpResponse.tooManyRequests
                    .setHeader("Retry-After", retryAfter.toString)
            )
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    meter.tryRun(next(request)).handle(Abort.run[Closed]).map {
                        case Result.Success(Present(res)) => res
                        case _                            => Abort.fail(tooMany)
                    }
            end new
        end rateLimit

        /** CORS headers and preflight handling. */
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
        ) =
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
                        val preflight = maxAge match
                            case Present(d) => r3.setHeader("Access-Control-Max-Age", d.toSeconds.toString)
                            case Absent     => r3
                        Abort.fail(HttpResponse.Halt(preflight))
                    else
                        next(request).map(addCorsHeaders)
                    end if
                end apply

        /** Adds standard security headers to responses. */
        def securityHeaders: Passthrough[Nothing] = securityHeaders()

        def securityHeaders(
            hsts: Maybe[Duration] = Absent,
            csp: Maybe[String] = Absent
        ) =
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
        def logging =
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

        def requestId(headerName: String) =
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
        def bearerAuth(token: String) =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader("Authorization", s"Bearer $token"))

        /** Adds Basic auth header to outgoing requests. */
        def basicAuth(username: String, password: String) =
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
        def addHeader(name: String, value: String) =
            new Passthrough[Nothing]:
                def apply[In, Out, E2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
                )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                    next(request.setHeader(name, value))

        /** Logs requests: "METHOD /path -> STATUS (Xms)" */
        def logging =
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
    def adapt[In, ReqOut, Out, ResOut, E](
        composed: HttpFilter[In, ReqOut, Out, ResOut, E]
    ): HttpFilter[In & ReqOut, Any, Out & ResOut, Any, E] =
        new HttpFilter[In & ReqOut, Any, Out & ResOut, Any, E]:
            def apply[In2, Out2, E2](
                request: HttpRequest[In2 & (In & ReqOut)],
                next: HttpRequest[In2 & (In & ReqOut)] => HttpResponse[Out2 & (Out & ResOut)] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out2 & (Out & ResOut)] < (Async & Abort[E | E2 | HttpResponse.Halt]) =
                composed[In2 & ReqOut, Out2 & ResOut, E2](request, next)

end HttpFilter
