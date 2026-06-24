package kyo.stats.otlp

import java.util.concurrent.ConcurrentLinkedQueue
import kyo.*
import kyo.stats.*
import kyo.stats.Attributes
import kyo.stats.Attributes.Attribute
import kyo.stats.internal.TraceExporter
import kyo.stats.internal.UnsafeTraceSpan

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

    private val droppedSpansCounter =
        Stat.initScope("kyo", "stats", "otel").initCounter("spans.dropped", "Spans dropped due to full export queue")

    private val maxExportBatchSize = config.bspMaxExportBatchSize

    // ExporterFactory (kyo-stats-registry) can't have Frame
    private val spanChannel =
        given Frame = Frame.internal
        Channel.Unsafe.init[OTLPSpan](
            config.bspMaxQueueSize,
            Access.MultiProducerSingleConsumer
        )
    end spanChannel

    private[otlp] val trigger =
        given Frame = Frame.internal
        Channel.Unsafe.init[Unit](1, Access.MultiProducerSingleConsumer)

    // Coalesces flush requests through a single work counter so the single-consumer span channel is never drained by
    // two fibers at once (which would corrupt the MPSC queue). Each flush increments the counter; the caller that
    // increments it from zero owns the drain and keeps draining, decrementing once per request, until the counter
    // returns to zero. Concurrent requests just increment and return; the owner picks them up on its next decrement.
    private val flushCount = AtomicLong.Unsafe.init(0)

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
      *   An `UnsafeTraceSpan` handle — call `end()` to finalize and enqueue for export
      */
    def startSpan(
        scope: List[String],
        name: String,
        now: java.time.Instant,
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
        val startNanos = Instant.fromJava(now).toDuration.toNanos
        new SpanUnsafe(
            traceId,
            spanId,
            parentSpanId,
            scope,
            name,
            startNanos,
            toKeyValues(attributes)
        )
    end startSpan

    private class SpanUnsafe(
        val traceId: String,
        val spanId: String,
        val parentSpanId: String,
        scope: List[String],
        name: String,
        startNanos: Long,
        attrs: Seq[KeyValue]
    ) extends UnsafeTraceSpan with UnsafeTraceSpan.Propagatable:
        private val events                                       = new ConcurrentLinkedQueue[SpanEvent]()
        @volatile private var spanStatus: UnsafeTraceSpan.Status = UnsafeTraceSpan.Status.Unset

        def setStatus(status: UnsafeTraceSpan.Status)(using AllowUnsafe): Unit =
            spanStatus = status

        // UnsafeTraceSpan (kyo-stats-registry) can't have Frame
        def end(now: java.time.Instant)(using AllowUnsafe): Unit =
            given Frame  = Frame.internal
            val endNanos = Instant.fromJava(now).toDuration.toNanos
            val status = (spanStatus: UnsafeTraceSpan.Status) match
                case _: UnsafeTraceSpan.Status.Unset.type => SpanStatus(code = OTLPModel.StatusUnset)
                case _: UnsafeTraceSpan.Status.Ok.type    => SpanStatus(code = OTLPModel.StatusOk)
                case e: UnsafeTraceSpan.Status.Error      => SpanStatus(code = OTLPModel.StatusError, message = e.message)
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
                            val _ = trigger.offer(())
                        case _ => ()
                case _ => droppedSpansCounter.unsafe.inc()
            end match
        end end

        def event(name: String, a: Attributes, now: java.time.Instant)(using AllowUnsafe): Unit =
            val eventNanos = Instant.fromJava(now).toDuration.toNanos
            val _ = events.add(SpanEvent(
                name = name,
                timeUnixNano = eventNanos.toString,
                attributes = toKeyValues(a)
            ))
        end event

        private def drainEvents(): Seq[SpanEvent] =
            import scala.annotation.tailrec
            val buf = ChunkBuilder.init[SpanEvent]
            @tailrec def loop(): Unit =
                val item = events.poll()
                if item ne null then
                    buf.addOne(item)
                    loop()
            end loop
            loop()
            buf.result()
        end drainEvents
    end SpanUnsafe

    /** Requests a drain of buffered spans to the collector.
      *
      * Non-blocking and coalescing: the span channel is single-consumer, so two fibers draining at once would corrupt the MPSC queue. The
      * caller that increments `flushCount` from zero owns the drain; a request that arrives while a drain is running just increments the
      * counter and returns, and the owner observes it on its next decrement. No fiber waits to acquire a lock.
      */
    private[otlp] def flush(config: OTLPConfig)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            if flushCount.getAndIncrement() == 0L then drainLoop(config)
            else ()
        }

    /** Owner-only drain loop: drains everything queued, then decrements one request. Repeats while the counter stays above zero, so any
      * request that arrived during a drain triggers another pass. Only the fiber that incremented from zero runs this, so `drainAndSend`
      * never has a concurrent consumer.
      */
    private def drainLoop(config: OTLPConfig)(using Frame): Unit < Async =
        drainAndSend(config).andThen {
            Sync.Unsafe.defer {
                if flushCount.decrementAndGet() == 0L then ()
                else drainLoop(config)
            }
        }

    private def drainAndSend(config: OTLPConfig)(using Frame): Unit < Async =
        Sync.Unsafe.defer {
            spanChannel.drainUpTo(config.bspMaxExportBatchSize) match
                case Result.Success(spans) if spans.nonEmpty =>
                    val request = ExportTraceRequest(
                        resourceSpans = Seq(ResourceSpans(
                            resource = OTLPClient.buildResource(config),
                            scopeSpans = Seq(ScopeSpans(
                                scope = OTLPClient.instrumentationScope,
                                spans = spans.toSeq
                            ))
                        ))
                    )
                    OTLPClient.sendTraces(config, request).map { _ =>
                        if spans.size == config.bspMaxExportBatchSize then
                            drainAndSend(config)
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
    end randomHex

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
