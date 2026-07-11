package kyo.stats.machine

import java.lang.management.ManagementFactory
import kyo.*
import scala.annotation.tailrec

// JVM-only allocation probe for MachineSampler.readScoped/fill: java.lang.management is
// absent on JS/Native, the same platform split PathJvmTest uses for its retained-ByteBuffer
// allocation leaves.
class MachineSamplerJvmTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    private val allocBean: com.sun.management.ThreadMXBean =
        ManagementFactory.getThreadMXBean() match
            case bean: com.sun.management.ThreadMXBean => bean
            case _                                     => null

    "the sampler readScoped/fill path assembles a multi-chunk file in full and allocates zero per read after warmup" in {
        for
            dir <- Path.tempDir("kyo-stats-machine-alloc")
            // Larger than the sampler's fixed 8192-byte scratch chunk, so a full read spans
            // multiple readChunk calls and exercises fill's multi-chunk arraycopy assembly.
            content = ("0123456789" * 2000) // 20000 bytes
            file    = dir / "proc-fixture.txt"
            _       <- file.write(content)
            handles <- MachineHandles.init
        yield
            assert(allocBean != null, "com.sun.management.ThreadMXBean unavailable on this JVM")
            assert(
                allocBean.isThreadAllocatedMemorySupported,
                "ThreadMXBean.getThreadAllocatedBytes is unsupported on this JVM (cannot falsify the allocation bound)"
            )
            if !allocBean.isThreadAllocatedMemoryEnabled then allocBean.setThreadAllocatedMemoryEnabled(true)

            val sampler = new MachineSampler(handles, Machine.NullMachine)
            val tid     = Thread.currentThread().getId

            // The measured callback returns only an Int (the byte count): no String, no tuple, no
            // array is allocated per call, isolating the measurement to the sampler's own
            // readScoped/fill zero-alloc claim rather than the test's own byte-to-content
            // conversion. Content correctness is checked separately, outside the measured window,
            // via a String-producing call whose allocation is expected and irrelevant to the bound.
            def readLen(): Int =
                sampler.readScoped(file, (_, len) => len).getOrElse(fail("readScoped returned Absent for a real, readable file"))

            def readWhole(): String =
                sampler.readScoped(
                    file,
                    (bytes, len) => new String(Span.toArrayUnsafe(bytes), 0, len, java.nio.charset.StandardCharsets.UTF_8)
                )
                    .getOrElse(fail("readScoped returned Absent for a real, readable file"))

            // Correctness, outside the measured window: the multi-chunk assembly reproduces the
            // full file content byte-for-byte (this is also the warmup call's first read, which
            // installs the retained handle and grows the output buffer to fit, the one legitimate
            // allocation).
            assert(readWhole() == content, "the assembled read did not equal the full file content byte-for-byte")

            @tailrec def warmup(remaining: Int): Unit =
                if remaining > 0 then
                    val _ = readLen()
                    warmup(remaining - 1)
            warmup(20000)

            val n = 2000

            val before = allocBean.getThreadAllocatedBytes(tid)
            @tailrec def measured(remaining: Int, lastLen: Int): Int =
                if remaining <= 0 then lastLen
                else measured(remaining - 1, readLen())
            val lastLen = measured(n, 0)
            val after   = allocBean.getThreadAllocatedBytes(tid)

            assert(lastLen == content.length, "assembled byte count mismatch on the measured window's last read")
            assert(readWhole() == content, "the assembled read did not equal the full file content byte-for-byte after the measured window")

            val perRead = (after - before).toDouble / n
            // A regression to a per-chunk allocation (a lost retained-buffer cache hit, or a
            // per-call array/tuple) would show a large per-read cost; the bound is set well below
            // that regression signal while absorbing residual JIT/probe noise.
            assert(perRead <= 64.0, s"steady-state per-read allocation was $perRead bytes (expected ~0 after warmup)")
    }

end MachineSamplerJvmTest
