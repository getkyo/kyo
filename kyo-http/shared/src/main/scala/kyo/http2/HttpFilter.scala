package kyo.http2

import kyo.<
import kyo.Abort
import kyo.Absent
import kyo.Async
import kyo.Clock
import kyo.Closed
import kyo.Duration
import kyo.Frame
import kyo.Log
import kyo.Maybe
import kyo.Meter
import kyo.Present
import kyo.Random
import kyo.Record
import kyo.Record.~
import kyo.Result
import kyo.Sync

sealed abstract class HttpFilter[ReqIn, ReqOut, ResIn, ResOut, -S]:

    def apply[In, Out, S2](
        request: HttpRequest[In & ReqIn],
        next: HttpRequest[In & ReqIn & ReqOut] => HttpResponse[Out & ResIn] < S2
    ): HttpResponse[Out & ResIn & ResOut] < (S & S2)

    final def andThen[RI2, RO2, SI2, SO2, S2](
        that: HttpFilter[RI2, RO2, SI2, SO2, S2]
    ): HttpFilter[ReqIn & RI2, ReqOut & RO2, ResIn & SI2, ResOut & SO2, S & S2] =
        val self = this
        new HttpFilter[ReqIn & RI2, ReqOut & RO2, ResIn & SI2, ResOut & SO2, S & S2]:
            def apply[In, Out, S3](
                request: HttpRequest[In & ReqIn & RI2],
                next: HttpRequest[In & ReqIn & RI2 & ReqOut & RO2] => HttpResponse[Out & ResIn & SI2] < S3
            ): HttpResponse[Out & ResIn & SI2 & ResOut & SO2] < (S & S2 & S3) =
                self(request, req => that(req, next))
        end new
    end andThen

end HttpFilter

object HttpFilter:

    /** Short-circuit response used with Abort to reject requests (401, 429, etc.) */
    case class Reject(response: HttpResponse[Any])

    abstract class Request[ReqIn, ReqOut, -S]
        extends HttpFilter[ReqIn, ReqOut, Any, Any, S]

    abstract class Response[ResIn, ResOut, -S]
        extends HttpFilter[Any, Any, ResIn, ResOut, S]

    abstract class Passthrough[-S]
        extends HttpFilter[Any, Any, Any, Any, S]

    val noop: Passthrough[Any] =
        new Passthrough[Any]:
            def apply[In, Out, S2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < S2
            ): HttpResponse[Out] < S2 =
                next(request)

    // --- Server-side filters ---

    object server:

        /** Validates Basic auth credentials. Reads "authorization" from request fields, adds "user" ~ String for downstream handlers.
          * Short-circuits with Abort[Reject] on failure.
          */
        def basicAuth(validate: (String, String) => Boolean < Async)(using Frame) =
            new Request["authorization" ~ Maybe[String], "user" ~ String, Async & Abort[Reject]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In & "authorization" ~ Maybe[String]],
                    next: HttpRequest[In & "authorization" ~ Maybe[String] & "user" ~ String] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Async & Abort[Reject] & S2) =
                    val unauthorized = Reject(
                        HttpResponse(HttpStatus.Unauthorized)
                            .setHeader("WWW-Authenticate", "Basic")
                    )
                    request.fields.authorization match
                        case Present(auth) if auth.startsWith("Basic ") =>
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

        /** Validates Bearer token. Reads "authorization" from request fields. Short-circuits with Abort[Reject] on failure.
          */
        def bearerAuth(validate: String => Boolean < Async)(using Frame) =
            new Request["authorization" ~ Maybe[String], Any, Async & Abort[Reject]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In & "authorization" ~ Maybe[String]],
                    next: HttpRequest[In & "authorization" ~ Maybe[String]] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Async & Abort[Reject] & S2) =
                    val unauthorized = Reject(
                        HttpResponse(HttpStatus.Unauthorized)
                            .setHeader("WWW-Authenticate", "Bearer")
                    )
                    request.fields.authorization match
                        case Present(auth) if auth.startsWith("Bearer ") =>
                            validate(auth.drop(7)).map { valid =>
                                if valid then next(request)
                                else Abort.fail(unauthorized)
                            }
                        case _ => Abort.fail(unauthorized)
                    end match
                end apply

        /** Rate limits using a Meter. Short-circuits with Abort[Reject] when limit exceeded.
          */
        def rateLimit(meter: Meter, retryAfter: Int = 1)(using Frame) =
            val tooMany = Reject(
                HttpResponse(HttpStatus.TooManyRequests)
                    .setHeader("Retry-After", retryAfter.toString)
            )
            new Passthrough[Async & Abort[Reject]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Async & Abort[Reject] & S2) =
                    Abort.run[Closed](meter.tryRun(next(request))).map {
                        case Result.Success(Present(res)) => res
                        case _                            => Abort.fail(tooMany)
                    }
            end new
        end rateLimit

        /** CORS headers and preflight handling. */
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
        )(using Frame) =
            new Passthrough[Abort[Reject]]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Abort[Reject] & S2) =
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
                        val r0 = HttpResponse(HttpStatus.NoContent)
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
                        Abort.fail(Reject(preflight))
                    else
                        next(request).map(addCorsHeaders)
                    end if
                end apply

        /** Adds standard security headers to responses. */
        def securityHeaders(
            hsts: Maybe[Duration] = Absent,
            csp: Maybe[String] = Absent
        )(using Frame) =
            new Passthrough[Any]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < S2 =
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
        def logging(using Frame) =
            new Passthrough[Async]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Async & S2) =
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
        def requestId(headerName: String = "X-Request-ID")(using Frame) =
            new Passthrough[Sync]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Sync & S2) =
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
            new Passthrough[Any]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < S2 =
                    next(request.setHeader("Authorization", s"Bearer $token"))

        /** Adds Basic auth header to outgoing requests. */
        def basicAuth(username: String, password: String) =
            val encoded = java.util.Base64.getEncoder.encodeToString(
                s"$username:$password".getBytes("UTF-8")
            )
            new Passthrough[Any]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < S2 =
                    next(request.setHeader("Authorization", s"Basic $encoded"))
            end new
        end basicAuth

        /** Adds a custom header to outgoing requests. */
        def addHeader(name: String, value: String) =
            new Passthrough[Any]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < S2 =
                    next(request.setHeader(name, value))

        /** Logs requests: "METHOD /path -> STATUS (Xms)" */
        def logging(using Frame) =
            new Passthrough[Async]:
                def apply[In, Out, S2](
                    request: HttpRequest[In],
                    next: HttpRequest[In] => HttpResponse[Out] < S2
                ): HttpResponse[Out] < (Async & S2) =
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

    /** Adapts a composed filter into the normalized form `HttpFilter[In & ReqOut, Any, Out & ResOut, Any, S]` for storage in HttpRoute.
      */
    def adapt[In, ReqOut, Out, ResOut, S](
        composed: HttpFilter[In, ReqOut, Out, ResOut, S]
    ): HttpFilter[In & ReqOut, Any, Out & ResOut, Any, S] =
        new HttpFilter[In & ReqOut, Any, Out & ResOut, Any, S]:
            def apply[In2, Out2, S2](
                request: HttpRequest[In2 & (In & ReqOut)],
                next: HttpRequest[In2 & (In & ReqOut)] => HttpResponse[Out2 & (Out & ResOut)] < S2
            ): HttpResponse[Out2 & (Out & ResOut)] < (S & S2) =
                composed[In2 & ReqOut, Out2 & ResOut, S2](request, next)

end HttpFilter
