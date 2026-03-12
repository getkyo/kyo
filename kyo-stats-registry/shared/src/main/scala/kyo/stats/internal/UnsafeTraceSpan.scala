package kyo.stats.internal

import kyo.AllowUnsafe
import kyo.stats.Attributes

/** Low-level handle for a single trace span, used by exporter implementations.
  *
  * Callers record events and set status during the span's lifetime, then call `end()` to finalize it. Spans that also implement the
  * `Propagatable` trait carry a `traceId` and `spanId` for cross-service context propagation.
  */
abstract class UnsafeTraceSpan:
    def end()(using AllowUnsafe): Unit
    def event(name: String, a: Attributes)(using AllowUnsafe): Unit
    def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe): Unit

object UnsafeTraceSpan:

    enum Status derives CanEqual:
        case Unset
        case Ok
        case Error(message: String)

    trait Propagatable:
        def traceId: String
        def spanId: String

    val noop: UnsafeTraceSpan =
        given AllowUnsafe = AllowUnsafe.embrace.danger
        new UnsafeTraceSpan:
            def end()(using AllowUnsafe)                                     = ()
            def event(name: String, a: Attributes)(using AllowUnsafe)        = ()
            def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe) = ()
end UnsafeTraceSpan
