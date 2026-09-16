package kyo.stats.internal

import kyo.AllowUnsafe
import kyo.stats.Attributes

/** Low-level handle for a single trace span, used by exporter implementations.
  *
  * Callers record events and set status during the span's lifetime, then call `end()` to finalize it. Spans that also implement the
  * `Propagatable` trait carry a `traceId` and `spanId` for cross-service context propagation.
  */
abstract class UnsafeTraceSpan {

    /** Finalizes the span.
      *
      * @param nowEpochNanos
      *   the time, as nanoseconds from 1970-01-01T00:00:00Z. A count rather than a date-time type because this
      *   module cross-builds to Scala 2.13, where kyo's own `Instant` (an opaque type) does not exist, and because
      *   a `java.time.Instant` here carried a date-time library and a locale database into every program that
      *   traced. It is also the unit OTLP's protocol uses, so the only consumer converted to it immediately.
      */
    def end(nowEpochNanos: Long)(implicit _au: AllowUnsafe): Unit

    /** Records an event on the span. `nowEpochNanos` is as in [[end]]. */
    def event(name: String, a: Attributes, nowEpochNanos: Long)(implicit _au: AllowUnsafe): Unit

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
            def end(nowEpochNanos: Long)(implicit _au: AllowUnsafe)                                = ()
            def event(name: String, a: Attributes, nowEpochNanos: Long)(implicit _au: AllowUnsafe) = ()
            def setStatus(status: UnsafeTraceSpan.Status)(implicit _au: AllowUnsafe)        = ()
        }
    }
}
