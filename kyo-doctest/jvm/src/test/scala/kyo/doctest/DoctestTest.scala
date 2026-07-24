package kyo.doctest

import java.util.concurrent.atomic.AtomicLong
import kyo.*

abstract private[doctest] class DoctestTest extends kyo.test.Test[Any]:

    override def aroundLeaf[A](
        body: A < (Async & Abort[Any] & Scope)
    )(using Frame): A < (Async & Abort[Any] & Scope) =
        UUID.let(DoctestTest.generator)(body)
end DoctestTest

private[doctest] object DoctestTest:

    private val CounterMax = 0x03ffffffffffffffL

    val generator: UUIDGenerator = generator(java.lang.ProcessHandle.current().pid())

    private[doctest] def generator(processNamespace: Long): UUIDGenerator =
        val sequence = new AtomicLong(0L)
        new UUIDGenerator:
            def v4(using Frame): UUID < Sync =
                Sync.defer(next(processNamespace, sequence, version = 4))

            def v7(using Frame): UUID < Sync =
                Sync.defer(next(processNamespace, sequence, version = 7))
        end new
    end generator

    private def next(processNamespace: Long, sequence: AtomicLong, version: Int): UUID =
        val counter = sequence.incrementAndGet()
        if counter <= 0L || counter > CounterMax then
            throw new IllegalStateException("deterministic doctest UUID sequence exhausted")

        val mostSignificantBits =
            (processNamespace & 0xffffffffffff0000L) |
                (version.toLong << 12) |
                ((processNamespace >>> 4) & 0x0fffL)
        val leastSignificantBits =
            0x8000000000000000L |
                ((processNamespace & 0x0fL) << 58) |
                counter
        UUID.fromLongs(mostSignificantBits, leastSignificantBits)
    end next
end DoctestTest
