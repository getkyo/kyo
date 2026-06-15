package kyo.internal.tasty.query

import kyo.Stat
import kyo.stats.internal.UnsafeCounter

/** Performance counters for cold-load timing instrumentation, backed by kyo.Stat (LongAdder-based).
  *
  * Counters are exposed as raw UnsafeCounter values so hot-path callers (ClasspathOrchestrator, ZipHandle) can call .inc()/.add() with
  * zero allocation. All callsites must hold (using AllowUnsafe).
  *
  * Note: UnsafeCounter.get() calls sumThenReset() internally, so reading a counter consumes its value.
  *
  * Private to the kyo package so this never appears in public API.
  */
private[kyo] object TastyPerfStats:
    private val scope: Stat = Stat.initScope("kyo.tasty.cold-load")

    val jarOpens: UnsafeCounter             = scope.initCounter("jar.opens").unsafe
    val entryReads: UnsafeCounter           = scope.initCounter("entry.reads").unsafe
    val bytesRead: UnsafeCounter            = scope.initCounter("bytes.read").unsafe
    val jarConstructNs: UnsafeCounter       = scope.initCounter("jar.construct.ns").unsafe
    val jarReadNs: UnsafeCounter            = scope.initCounter("jar.read.ns").unsafe
    val tastyHeaderNs: UnsafeCounter        = scope.initCounter("tasty-header.ns").unsafe
    val nameUnpicklerNs: UnsafeCounter      = scope.initCounter("name-unpickler.ns").unsafe
    val sectionIndexNs: UnsafeCounter       = scope.initCounter("section-index.ns").unsafe
    val attributeUnpicklerNs: UnsafeCounter = scope.initCounter("attribute-unpickler.ns").unsafe
    val astPass1Ns: UnsafeCounter           = scope.initCounter("ast-pass1.ns").unsafe
    val commentsUnpicklerNs: UnsafeCounter  = scope.initCounter("comments-unpickler.ns").unsafe
    val positionsUnpicklerNs: UnsafeCounter = scope.initCounter("positions-unpickler.ns").unsafe
end TastyPerfStats
