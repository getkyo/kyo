package kyo.stats.internal

import java.time.Instant
import kyo.AllowUnsafe
import kyo.stats.Attributes

/** Low-level handle for a single trace span, used by exporter implementations.
  *
  * Callers record events and set status during the span's lifetime, then call `end()` to finalize it. Spans that also implement the
  * `Propagatable` trait carry a `traceId` and `spanId` for cross-service context propagation.
  */
abstract class UnsafeTraceSpan {
    def end(now: Instant)(implicit _au: AllowUnsafe): Unit
    def event(name: String, a: Attributes, now: Instant)(implicit _au: AllowUnsafe): Unit
    def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe): Unit
}

object UnsafeTraceSpan {

    sealed abstract class Status
    object Status {
        case object Unset                 extends Status
        case object Ok                    extends Status
        case class Error(message: String) extends Status
    }

    trait Propagatable {
        def traceId: String
        def spanId: String
    }

    val noop: UnsafeTraceSpan = {
        implicit val _au: AllowUnsafe = AllowUnsafe.embrace.danger
        new UnsafeTraceSpan {
            def end(now: Instant)(implicit _au: AllowUnsafe)                                = ()
            def event(name: String, a: Attributes, now: Instant)(implicit _au: AllowUnsafe) = ()
            def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe)        = ()
        }
    }
}
