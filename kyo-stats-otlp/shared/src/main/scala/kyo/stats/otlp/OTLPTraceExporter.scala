package kyo.stats.otlp

import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.Attributes.Attribute
import kyo.stats.internal.TraceExporter
import kyo.stats.internal.UnsafeTraceSpan
import kyo.stats.internal.StatsRegistry
import java.util.concurrent.ConcurrentLinkedQueue

/** Trace exporter that buffers completed spans and flushes them to an OTLP collector over HTTP/JSON.
  *
  * Spans are queued in a bounded channel and exported in configurable batches. When the queue reaches the batch-size threshold a flush is
  * triggered eagerly via a signal channel; a periodic timer also flushes on a fixed schedule. Spans that arrive when the queue is full are
  * dropped and counted.
  *
  * On JVM/Native the background fibers are started automatically during construction via `OTLPInitPlatform`. On JS, initialization is
  * deferred to the first span activity.
  *
  * @param config
  *   OTLP exporter configuration controlling endpoints, batch sizes, and timeouts
  */
class OTLPTraceExporter private (val config: OTLPConfig)(using AllowUnsafe) extends TraceExporter:

    private[otlp] val started = AtomicBoolean.Unsafe.init

    private val droppedSpansCounter = StatsRegistry.scope("kyo", "stats", "otel").counter("spans.dropped", "Spans dropped due to full export queue")

    private val maxExportBatchSize = config.bspMaxExportBatchSize

    private val spanChannel = {
        given Frame = Frame.internal
        Channel.Unsafe.init[OTLPSpan](
            config.bspMaxQueueSize,
            Access.MultiProducerSingleConsumer
        )
    }

    private[otlp] val flushSignal = {
        given Frame = Frame.internal
        Channel.Unsafe.init[Unit](1, Access.MultiProducerSingleConsumer)
    }

    OTLPInitPlatform.triggerStart(this)

    /** Creates a new span and returns an unsafe handle for recording events, setting status, and ending the span.
      *
      * If a `Propagatable` parent is provided the child inherits the parent's trace ID and links via `parentSpanId`. Otherwise a fresh
      * trace ID is generated. The span is enqueued for export when `end()` is called on the returned handle.
      *
      * @param scope
      *   Hierarchical scope path (e.g. `List("http", "server")`)
      * @param name
      *   Operation name for this span
      * @param parent
      *   Optional parent span for trace propagation
      * @param attributes
      *   Initial span attributes
      * @return
      *   An [[UnsafeTraceSpan]] handle — call `end()` to finalize and enqueue for export
      */
    def startSpan(
        scope: List[String],
        name: String,
        parent: Option[UnsafeTraceSpan] = None,
        attributes: Attributes = Attributes.empty
    )(using AllowUnsafe): UnsafeTraceSpan =
        val traceId = parent match
            case Some(p: UnsafeTraceSpan.Propagatable) => p.traceId
            case _                                     => randomHex(16)
        val spanId = randomHex(8)
        val parentSpanId = parent match
            case Some(p: UnsafeTraceSpan.Propagatable) => p.spanId
            case _                                     => ""
        val now        = java.time.Instant.now()
        val startNanos = now.getEpochSecond * 1_000_000_000L + now.getNano
        new SpanUnsafe(
            traceId,
            spanId,
            parentSpanId,
            scope,
            name,
            startNanos,
            toKeyValues(attributes)
        )

    private class SpanUnsafe(
        val traceId: String,
        val spanId: String,
        val parentSpanId: String,
        scope: List[String],
        name: String,
        startNanos: Long,
        attrs: Seq[KeyValue]
    ) extends UnsafeTraceSpan with UnsafeTraceSpan.Propagatable:
        private val events = new ConcurrentLinkedQueue[SpanEvent]()
        @volatile private var spanStatus: UnsafeTraceSpan.Status = UnsafeTraceSpan.Status.Unset

        def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe): Unit =
            spanStatus = status

        def end()(using AllowUnsafe): Unit =
            given Frame = Frame.internal
            val endInstant = java.time.Instant.now()
            val endNanos   = endInstant.getEpochSecond * 1_000_000_000L + endInstant.getNano
            val status = spanStatus match
                case UnsafeTraceSpan.Status.Unset      => SpanStatus(code = OTLPModel.StatusUnset)
                case UnsafeTraceSpan.Status.Ok         => SpanStatus(code = OTLPModel.StatusOk)
                case UnsafeTraceSpan.Status.Error(msg) => SpanStatus(code = OTLPModel.StatusError, message = msg)
            val span = OTLPSpan(
                traceId = traceId,
                spanId = spanId,
                parentSpanId = parentSpanId,
                name = name,
                kind = OTLPModel.SpanKindInternal,
                startTimeUnixNano = startNanos.toString,
                endTimeUnixNano = endNanos.toString,
                attributes = attrs,
                events = drainEvents(),
                status = status
            )
            spanChannel.offer(span) match
                case Result.Success(true) =>
                    spanChannel.size() match
                        case Result.Success(s) if s >= maxExportBatchSize =>
                            val _ = flushSignal.offer(())
                        case _ => ()
                case _ => droppedSpansCounter.inc()
        end end

        def event(name: String, a: Attributes)(using AllowUnsafe): Unit =
            val eventInstant = java.time.Instant.now()
            val eventNanos   = eventInstant.getEpochSecond * 1_000_000_000L + eventInstant.getNano
            val _ = events.add(SpanEvent(
                name = name,
                timeUnixNano = eventNanos.toString,
                attributes = toKeyValues(a)
            ))
        end event

        private def drainEvents(): Seq[SpanEvent] =
            import scala.annotation.tailrec
            val buf = scala.collection.mutable.ArrayBuffer[SpanEvent]()
            @tailrec def loop(): Unit =
                val item = events.poll()
                if item ne null then
                    buf += item
                    loop()
            loop()
            buf.toSeq
    end SpanUnsafe

    /** Drains up to `bspMaxExportBatchSize` spans from the queue and sends them. Recurses if a full batch was drained. */
    private[otlp] def flush(config: OTLPConfig)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            spanChannel.drainUpTo(config.bspMaxExportBatchSize) match
                case Result.Success(spans) if spans.nonEmpty =>
                    val request = ExportTraceRequest(
                        resourceSpans = Seq(ResourceSpans(
                            resource = OTLPClient.buildResource(config),
                            scopeSpans = Seq(ScopeSpans(
                                scope = InstrumentationScope("kyo-stats", version = "1.0.0"),
                                spans = spans.toSeq
                            ))
                        ))
                    )
                    OTLPClient.sendTraces(config, request).map { _ =>
                        if spans.size == config.bspMaxExportBatchSize then
                            flush(config)
                        else ()
                    }
                case _ => ()
        }

    /** Flushes remaining spans within the given timeout, logging a warning if it expires. */
    private[otlp] def shutdown(timeout: Duration)(using Frame): Unit < Async =
        Abort.run[Timeout] {
            Async.timeout(timeout) {
                flush(config)
            }
        }.map {
            case Result.Success(_) => ()
            case Result.Failure(_) => Log.warn("OTLP shutdown flush timed out")
        }

    private def randomHex(bytes: Int): String =
        val buf = new Array[Byte](bytes)
        java.util.concurrent.ThreadLocalRandom.current().nextBytes(buf)
        buf.map(b => String.format("%02x", Byte.box(b))).mkString

    private def toKeyValues(a: Attributes): Seq[KeyValue] =
        a.get.map {
            case Attribute.BooleanAttribute(name, value)     => KeyValue(name, AnyValue.boolean(value))
            case Attribute.DoubleAttribute(name, value)      => KeyValue(name, AnyValue.double(value))
            case Attribute.LongAttribute(name, value)        => KeyValue(name, AnyValue.int(value))
            case Attribute.StringAttribute(name, value)      => KeyValue(name, AnyValue.string(value))
            case Attribute.BooleanListAttribute(name, value) => KeyValue(name, AnyValue.string(value.mkString(",")))
            case Attribute.DoubleListAttribute(name, value)  => KeyValue(name, AnyValue.string(value.mkString(",")))
            case Attribute.LongListAttribute(name, value)    => KeyValue(name, AnyValue.string(value.mkString(",")))
            case Attribute.StringListAttribute(name, value)  => KeyValue(name, AnyValue.string(value.mkString(",")))
        }
end OTLPTraceExporter

object OTLPTraceExporter:
    /** Creates a new OTLP trace exporter with the given configuration. Initializes internal channels and starts background fibers. */
    def init(config: OTLPConfig)(using AllowUnsafe): OTLPTraceExporter =
        new OTLPTraceExporter(config)
end OTLPTraceExporter
