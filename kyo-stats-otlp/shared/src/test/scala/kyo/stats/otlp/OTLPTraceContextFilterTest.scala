package kyo.stats.otlp

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.internal.TraceSpan
import kyo.stats.internal.UnsafeTraceSpan

class OTLPTraceContextFilterTest extends Test:

    private val testUrl = HttpUrl(Maybe("http"), "localhost", 80, "/test", Maybe.empty)

    private class TestPropagatable(val traceId: String, val spanId: String)
        extends UnsafeTraceSpan with UnsafeTraceSpan.Propagatable:
        def end(now: java.time.Instant)(using AllowUnsafe): Unit                                = ()
        def event(name: String, a: Attributes, now: java.time.Instant)(using AllowUnsafe): Unit = ()
        def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe): Unit                  = ()
    end TestPropagatable

    "server filter" - {

        "parses valid traceparent and sets span context" in run {
            val filter  = OTLPTraceContextFilter.server
            val traceId = "0af7651916cd43dd8448eb211c80319c"
            val spanId  = "b7ad6b7169203331"
            val request = HttpRequest(HttpMethod.GET, testUrl)
                .addHeader("traceparent", s"00-$traceId-$spanId-01")

            var capturedTraceId = ""
            var capturedSpanId  = ""

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    TraceSpan.current.map { span =>
                        span match
                            case Present(TraceSpan(s: UnsafeTraceSpan.Propagatable)) =>
                                capturedTraceId = s.traceId
                                capturedSpanId = s.spanId
                            case _ => ()
                        end match
                        HttpResponse.ok
                    }
            ).map { _ =>
                assert(capturedTraceId == traceId)
                assert(capturedSpanId == spanId)
            }
        }

        "passes through without traceparent header" in run {
            val filter  = OTLPTraceContextFilter.server
            val request = HttpRequest(HttpMethod.GET, testUrl)

            var nextCalled = false

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    nextCalled = true
                    HttpResponse.ok
            ).map { _ =>
                assert(nextCalled)
            }
        }

        "passes through with invalid traceparent - wrong traceId length" in run {
            val filter = OTLPTraceContextFilter.server
            val request = HttpRequest(HttpMethod.GET, testUrl)
                .addHeader("traceparent", "00-short-b7ad6b7169203331-01")

            var spanSet = false

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    TraceSpan.current.map { span =>
                        span match
                            case Present(TraceSpan(_: UnsafeTraceSpan.Propagatable)) =>
                                spanSet = true
                            case _ => ()
                        end match
                        HttpResponse.ok
                    }
            ).map { _ =>
                assert(!spanSet)
            }
        }

        "passes through with invalid traceparent - wrong spanId length" in run {
            val filter = OTLPTraceContextFilter.server
            val request = HttpRequest(HttpMethod.GET, testUrl)
                .addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-short-01")

            var spanSet = false

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    TraceSpan.current.map { span =>
                        span match
                            case Present(TraceSpan(_: UnsafeTraceSpan.Propagatable)) =>
                                spanSet = true
                            case _ => ()
                        end match
                        HttpResponse.ok
                    }
            ).map { _ =>
                assert(!spanSet)
            }
        }

        "passes through with invalid traceparent - too few parts" in run {
            val filter = OTLPTraceContextFilter.server
            val request = HttpRequest(HttpMethod.GET, testUrl)
                .addHeader("traceparent", "0af7651916cd43dd8448eb211c80319c")

            var spanSet = false

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    TraceSpan.current.map { span =>
                        span match
                            case Present(TraceSpan(_: UnsafeTraceSpan.Propagatable)) =>
                                spanSet = true
                            case _ => ()
                        end match
                        HttpResponse.ok
                    }
            ).map { _ =>
                assert(!spanSet)
            }
        }

        "passes through with empty traceparent" in run {
            val filter = OTLPTraceContextFilter.server
            val request = HttpRequest(HttpMethod.GET, testUrl)
                .addHeader("traceparent", "")

            var spanSet = false

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    TraceSpan.current.map { span =>
                        span match
                            case Present(TraceSpan(_: UnsafeTraceSpan.Propagatable)) =>
                                spanSet = true
                            case _ => ()
                        end match
                        HttpResponse.ok
                    }
            ).map { _ =>
                assert(!spanSet)
            }
        }
    }

    "client filter" - {

        "adds traceparent header when propagatable span exists" in run {
            val filter  = OTLPTraceContextFilter.client
            val traceId = "0af7651916cd43dd8448eb211c80319c"
            val spanId  = "b7ad6b7169203331"
            val span    = TraceSpan(new TestPropagatable(traceId, spanId))
            val request = HttpRequest(HttpMethod.GET, testUrl)

            var capturedTraceparent: Maybe[String] = Maybe.empty

            TraceSpan.let(span) {
                filter.apply(
                    request,
                    (req: HttpRequest[Any]) =>
                        capturedTraceparent = req.headers.get("traceparent")
                        HttpResponse.ok
                )
            }.map { _ =>
                assert(capturedTraceparent == Present(s"00-$traceId-$spanId-01"))
            }
        }

        "traceparent follows W3C format" in run {
            val filter  = OTLPTraceContextFilter.client
            val traceId = "abcdef1234567890abcdef1234567890"
            val spanId  = "1234567890abcdef"
            val span    = TraceSpan(new TestPropagatable(traceId, spanId))
            val request = HttpRequest(HttpMethod.GET, testUrl)

            var capturedTraceparent: Maybe[String] = Maybe.empty

            TraceSpan.let(span) {
                filter.apply(
                    request,
                    (req: HttpRequest[Any]) =>
                        capturedTraceparent = req.headers.get("traceparent")
                        HttpResponse.ok
                )
            }.map { _ =>
                capturedTraceparent match
                    case Present(tp) =>
                        val parts = tp.split("-")
                        assert(parts.length == 4)
                        assert(parts(0) == "00")
                        assert(parts(1) == traceId)
                        assert(parts(2) == spanId)
                        assert(parts(3) == "01")
                    case _ =>
                        fail("Expected traceparent header")
            }
        }

        "no header when no current span" in run {
            val filter  = OTLPTraceContextFilter.client
            val request = HttpRequest(HttpMethod.GET, testUrl)

            var capturedTraceparent: Maybe[String] = Maybe.empty

            filter.apply(
                request,
                (req: HttpRequest[Any]) =>
                    capturedTraceparent = req.headers.get("traceparent")
                    HttpResponse.ok
            ).map { _ =>
                assert(capturedTraceparent.isEmpty)
            }
        }

        "no header when span is not propagatable" in run {
            val filter  = OTLPTraceContextFilter.client
            val span    = TraceSpan.noop
            val request = HttpRequest(HttpMethod.GET, testUrl)

            var capturedTraceparent: Maybe[String] = Maybe.empty

            TraceSpan.let(span) {
                filter.apply(
                    request,
                    (req: HttpRequest[Any]) =>
                        capturedTraceparent = req.headers.get("traceparent")
                        HttpResponse.ok
                )
            }.map { _ =>
                assert(capturedTraceparent.isEmpty)
            }
        }

        "preserves existing request headers" in run {
            val filter  = OTLPTraceContextFilter.client
            val traceId = "0af7651916cd43dd8448eb211c80319c"
            val spanId  = "b7ad6b7169203331"
            val span    = TraceSpan(new TestPropagatable(traceId, spanId))
            val request = HttpRequest(HttpMethod.GET, testUrl)
                .addHeader("Authorization", "Bearer token")
                .addHeader("Content-Type", "application/json")

            var capturedAuth: Maybe[String]        = Maybe.empty
            var capturedContentType: Maybe[String] = Maybe.empty

            TraceSpan.let(span) {
                filter.apply(
                    request,
                    (req: HttpRequest[Any]) =>
                        capturedAuth = req.headers.get("Authorization")
                        capturedContentType = req.headers.get("Content-Type")
                        HttpResponse.ok
                )
            }.map { _ =>
                assert(capturedAuth == Present("Bearer token"))
                assert(capturedContentType == Present("application/json"))
            }
        }
    }

    "roundtrip" - {

        "client header is parsed correctly by server" in run {
            val clientFilter = OTLPTraceContextFilter.client
            val serverFilter = OTLPTraceContextFilter.server
            val traceId      = "0af7651916cd43dd8448eb211c80319c"
            val spanId       = "b7ad6b7169203331"
            val span         = TraceSpan(new TestPropagatable(traceId, spanId))
            val request      = HttpRequest(HttpMethod.GET, testUrl)

            var capturedTraceId = ""
            var capturedSpanId  = ""

            TraceSpan.let(span) {
                clientFilter.apply(
                    request,
                    (req: HttpRequest[Any]) =>
                        serverFilter.apply(
                            req,
                            (innerReq: HttpRequest[Any]) =>
                                TraceSpan.current.map { maybSpan =>
                                    maybSpan match
                                        case Present(TraceSpan(s: UnsafeTraceSpan.Propagatable)) =>
                                            capturedTraceId = s.traceId
                                            capturedSpanId = s.spanId
                                        case _ => ()
                                    end match
                                    HttpResponse.ok
                                }
                        )
                )
            }.map { _ =>
                assert(capturedTraceId == traceId)
                assert(capturedSpanId == spanId)
            }
        }
    }

end OTLPTraceContextFilterTest
