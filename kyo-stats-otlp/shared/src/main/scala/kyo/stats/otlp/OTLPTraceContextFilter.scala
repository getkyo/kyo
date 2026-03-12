package kyo.stats.otlp

import kyo.*
import kyo.stats.*
import kyo.stats.internal.UnsafeTraceSpan
import kyo.stats.internal.TraceSpan

/** W3C Trace Context propagation filters for HTTP client and server.
  *
  * The client filter injects a `traceparent` header into outgoing requests when the current span implements `Propagatable`. The server
  * filter parses incoming `traceparent` headers and sets the extracted remote span as the current trace context so that downstream spans
  * are linked to the caller's trace.
  *
  * @see
  *   [[https://www.w3.org/TR/trace-context/ W3C Trace Context specification]]
  */
object OTLPTraceContextFilter:

    /** Client-side filter that adds a `traceparent` header when the current span is propagatable. */
    val client: HttpFilter.Passthrough[Nothing] =
        new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                TraceSpan.current.map {
                    case Present(TraceSpan(span: UnsafeTraceSpan.Propagatable)) =>
                        val traceparent = s"00-${span.traceId}-${span.spanId}-01"
                        next(request.addHeader("traceparent", traceparent))
                    case _ =>
                        next(request)
                }

    /** Server-side filter that parses the `traceparent` header and sets the remote span as the current trace context. */
    val server: HttpFilter.Passthrough[Nothing] =
        new HttpFilter.Passthrough[Nothing]:
            def apply[In, Out, E2](
                request: HttpRequest[In],
                next: HttpRequest[In] => HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt])
            )(using Frame): HttpResponse[Out] < (Async & Abort[E2 | HttpResponse.Halt]) =
                request.headers.get("traceparent") match
                    case Present(traceparent) =>
                        parseTraceparent(traceparent) match
                            case Some((traceId, spanId)) =>
                                val remoteParent = TraceSpan(new RemoteSpanUnsafe(traceId, spanId))
                                TraceSpan.let(remoteParent) {
                                    next(request)
                                }
                            case None =>
                                next(request)
                    case _ =>
                        next(request)

    private def parseTraceparent(value: String): Option[(String, String)] =
        val parts = value.split("-")
        if parts.length >= 4 && parts(1).length == 32 && parts(2).length == 16 then
            Some((parts(1), parts(2)))
        else
            None
end OTLPTraceContextFilter

private class RemoteSpanUnsafe(
    val traceId: String,
    val spanId: String
) extends UnsafeTraceSpan with UnsafeTraceSpan.Propagatable:
    def end()(using AllowUnsafe): Unit                                     = ()
    def event(name: String, a: Attributes)(using AllowUnsafe): Unit        = ()
    def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe): Unit = ()
