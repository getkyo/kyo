package kyo

import kyo.internal.UUIDEntropyPlatform

/** Generates secure random and time-ordered UUIDs.
  *
  * A generator owns the entropy source and monotonic version 7 state used by its operations. The companion provides a live generator and
  * dynamically scoped delegation, so applications can use [[UUIDGenerator.v4]] and [[UUIDGenerator.v7]] directly while tests install a
  * deterministic generator with [[UUIDGenerator.let]].
  *
  * Version 4 generation stamps secure random bytes with the RFC version and variant bits. Version 7 generation combines the current Unix
  * millisecond with a secure 74-bit payload. Calls on one generator remain strictly ordered when the clock repeats or moves backward.
  *
  * IMPORTANT: Entropy and clock failures are untracked panics in [[Sync]]. Generation never falls back to weaker randomness, timestamps,
  * or process-local counters.
  *
  * @see [[UUID]] for the generated value type
  * @see [[UUIDGenerator.live]] for the default secure generator
  * @see [[UUIDGenerator.let]] for dynamic scoping
  * @see [[Random]] for deterministic non-cryptographic randomness
  */
abstract class UUIDGenerator:

    /** Generates a secure RFC version 4 UUID. */
    def v4(using Frame): UUID < Sync

    /** Generates a monotonic RFC version 7 UUID. */
    def v7(using Frame): UUID < Sync
end UUIDGenerator

object UUIDGenerator:

    /** The default generator backed by the platform secure entropy source and the dynamically scoped Kyo clock. */
    val live: UUIDGenerator =
        make(Clock.nowWith(_.toJava.toEpochMilli), UUIDEntropyPlatform.live)

    private val local = Local.init(live)

    /** Generates a version 4 UUID with the dynamically scoped generator. */
    def v4(using Frame): UUID < Sync =
        Sync.withLocal(local)(_.v4)

    /** Generates a version 7 UUID with the dynamically scoped generator. */
    def v7(using Frame): UUID < Sync =
        Sync.withLocal(local)(_.v7)

    /** Runs `value` with `generator` installed as the dynamically scoped UUID generator. */
    def let[A, S](generator: UUIDGenerator)(value: A < S)(using Frame): A < (S & Sync) =
        local.let(generator)(value)

    abstract private[kyo] class TestControl extends UUIDGenerator:
        def v7WithStateReadHook(hook: UUID => Unit < Async)(using Frame): UUID < Async
    end TestControl

    private[kyo] def init(clockMillis: () => Long, entropy: UUIDEntropyPlatform): UUIDGenerator =
        make(Sync.defer(clockMillis()), entropy)

    private[kyo] def test(clockMillis: Chunk[Long], entropy: Chunk[Byte]): UUIDGenerator =
        makeFinite(clockMillis, entropy)

    private[kyo] def testControlled(clockMillis: Chunk[Long], entropy: Chunk[Byte]): TestControl =
        makeFinite(clockMillis, entropy)

    private enum V7State derives CanEqual:
        case Empty
        case Value(effectiveMillis: Long, payloadHigh: Int, payloadLow: Long)
    end V7State

    final private class Default(
        clockMillis: Frame ?=> Long < Sync,
        entropy: UUIDEntropyPlatform,
        state: AtomicRef[V7State]
    ) extends TestControl:

        def v4(using Frame): UUID < Sync =
            entropy.next16.map(stampV4)

        def v7(using Frame): UUID < Sync =
            observeMillis.map: observedMillis =>
                validateMillis(observedMillis)
                v7Loop(observedMillis, _ => ())

        def v7WithStateReadHook(hook: UUID => Unit < Async)(using Frame): UUID < Async =
            observeMillis.map: observedMillis =>
                validateMillis(observedMillis)
                v7Loop(observedMillis, hook)

        private def observeMillis(using Frame): Long < Sync =
            clockMillis

        private def v7Loop[S](observedMillis: Long, hook: UUID => Unit < S)(using Frame): UUID < (S & Sync) =
            state.get.map: previous =>
                hook(toUUID(previous)).andThen:
                    nextState(previous, observedMillis).map: next =>
                        state.compareAndSet(previous, next).map: updated =>
                            if updated then toUUID(next)
                            else v7Loop(observedMillis, hook)

        private def nextState(previous: V7State, observedMillis: Long)(using Frame): V7State < Sync =
            previous match
                case V7State.Empty =>
                    samplePayload.map: payload =>
                        V7State.Value(observedMillis, payload._1, payload._2)
                case current: V7State.Value if observedMillis > current.effectiveMillis =>
                    samplePayload.map: payload =>
                        V7State.Value(observedMillis, payload._1, payload._2)
                case current: V7State.Value if current.payloadLow < PayloadLowMax =>
                    V7State.Value(current.effectiveMillis, current.payloadHigh, current.payloadLow + 1)
                case current: V7State.Value if current.payloadHigh < PayloadHighMax =>
                    V7State.Value(current.effectiveMillis, current.payloadHigh + 1, 0L)
                case current: V7State.Value if current.effectiveMillis == TimestampMax =>
                    throw new IllegalStateException("UUID version 7 timestamp and payload space exhausted")
                case current: V7State.Value =>
                    val nextMillis = current.effectiveMillis + 1
                    samplePayload.map: payload =>
                        V7State.Value(nextMillis, payload._1, payload._2)
            end match
        end nextState

        private def samplePayload(using Frame): (Int, Long) < Sync =
            entropy.next16.map: source =>
                require16(source)
                val high = ((source(6) & 0x0f) << 8) | (source(7) & 0xff)
                var low  = (source(8) & 0x3f).toLong
                var i    = 9
                while i < 16 do
                    low = (low << 8) | (source(i) & 0xffL)
                    i += 1
                (high, low)
        end samplePayload
    end Default

    private val PayloadHighMax = 0x0fff
    private val PayloadLowMax  = 0x3fffffffffffffffL
    private val TimestampMax   = 0xffffffffffffL

    private def validateMillis(millis: Long): Unit =
        if millis < 0 || millis > TimestampMax then
            throw new IllegalArgumentException(
                s"UUID version 7 timestamp must be between 0 and $TimestampMax milliseconds, got $millis"
            )
    end validateMillis

    private def make(clockMillis: Frame ?=> Long < Sync, entropy: UUIDEntropyPlatform): TestControl =
        // Unsafe: construction creates the generator's private atomic state before any effectful operation can observe it.
        import AllowUnsafe.embrace.danger
        new Default(clockMillis, entropy, AtomicRef.Unsafe.init[V7State](V7State.Empty).safe)
    end make

    private def makeFinite(clockMillis: Chunk[Long], entropy: Chunk[Byte]): TestControl =
        // Unsafe: test-only counters that never escape this constructor and are only ever read or updated inside Sync.defer.
        import AllowUnsafe.embrace.danger
        val clockIndex   = AtomicInt.Unsafe.init(0)
        val entropyIndex = AtomicInt.Unsafe.init(0)
        val finiteEntropy = new UUIDEntropyPlatform:
            def next16(using Frame): Span[Byte] < Sync =
                Sync.defer:
                    val start = entropyIndex.getAndAdd(16)
                    if !containsEntropyBlock(entropy.size, start) then
                        throw new NoSuchElementException("UUID test entropy exhausted")
                    val bytes = new Array[Byte](16)
                    var i     = 0
                    while i < 16 do
                        bytes(i) = entropy(start + i)
                        i += 1
                    Span.fromUnsafe(bytes)
        make(
            Sync.defer:
                val index = clockIndex.getAndIncrement()
                if index < 0 || index >= clockMillis.size then
                    throw new NoSuchElementException("UUID test clock exhausted")
                clockMillis(index)
            ,
            finiteEntropy
        )
    end makeFinite

    private[kyo] def containsEntropyBlock(size: Int, start: Int): Boolean =
        start >= 0 && size >= 16 && start <= size - 16

    private def require16(source: Span[Byte]): Unit =
        if source.size != 16 then
            throw new IllegalStateException(s"UUID entropy source returned ${source.size} bytes instead of 16")
    end require16

    private def stampV4(source: Span[Byte]): UUID =
        require16(source)
        val mostSignificantBits  = (readLong(source, 0) & 0xffffffffffff0fffL) | 0x0000000000004000L
        val leastSignificantBits = (readLong(source, 8) & 0x3fffffffffffffffL) | 0x8000000000000000L
        UUID.fromLongs(mostSignificantBits, leastSignificantBits)
    end stampV4

    private def toUUID(state: V7State): UUID =
        state match
            case V7State.Empty => UUID.nil
            case V7State.Value(effectiveMillis, payloadHigh, payloadLow) =>
                val mostSignificantBits  = (effectiveMillis << 16) | 0x7000L | payloadHigh
                val leastSignificantBits = 0x8000000000000000L | payloadLow
                UUID.fromLongs(mostSignificantBits, leastSignificantBits)
        end match
    end toUUID

    private def readLong(source: Span[Byte], offset: Int): Long =
        var result = 0L
        var i      = offset
        while i < offset + 8 do
            result = (result << 8) | (source(i) & 0xffL)
            i += 1
        result
    end readLong
end UUIDGenerator
