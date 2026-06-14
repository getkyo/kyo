package kyo.ffi.internal

import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout
import java.nio.charset.StandardCharsets

/** Per-thread scratch allocator for transient marshalling (UTF-8 strings, struct-by-value, out-param buffers for multi-value returns, errno
  * capture segments). Mark / allocate / reset protocol, no arena open/close per call on the hot path.
  *
  * Oversized allocations spill to fresh confined arenas, which are closed on the next reset.
  */
object Scratch:
    /** Scratch size per thread. Override via `-Dkyo.ffi.scratch.size=<bytes>`. Default 64 KiB.
      *
      * Sizing rationale: 64 KiB comfortably fits the common FFI marshalling patterns we target, typical UTF-8 string
      * arguments (paths, SQL statements, short blobs), small struct-by-value parameters, and multi-value return out-params. At a page
      * boundary it keeps the confined arena allocation cheap and each call's `mark`/`reset` is a plain long write. Workloads that need
      * larger sustained transient allocations (e.g. encrypting multi-MB payloads) should raise the limit via the system property; oversized
      * one-off allocations already spill transparently to fresh arenas, so this default never hard-caps correctness, only throughput.
      */
    val configuredSize: Long =
        sys.props.get("kyo.ffi.scratch.size").flatMap(s => scala.util.Try(s.toLong).toOption).getOrElse(64L * 1024L)

    /** Maximum scratch size after auto-growth. Override via `-Dkyo.ffi.scratch.maxSize=<bytes>`. Default 4 MiB. */
    val maxScratchSize: Long =
        sys.props.get("kyo.ffi.scratch.maxSize")
            .flatMap(s => scala.util.Try(s.toLong).toOption)
            .getOrElse(4L * 1024L * 1024L)

    /** Opt-in system property, when `true`, [[Scratch]] writes a diagnostic to stderr via [[FfiErrors.scratchSpilled]] on every oversized
      * allocation that falls off the per-thread block onto a fresh confined arena. Default is `false` because steady-state spills for
      * large-buffer APIs are expected and would be noisy under normal use; this flag is intended for operators diagnosing scratch-sizing
      * decisions.
      */
    def logSpills: Boolean =
        sys.props.get("kyo.ffi.scratch.logSpills").map(_.equalsIgnoreCase("true")).getOrElse(false)

    private val tl = ThreadLocal.withInitial[Scratch](() => new Scratch(configuredSize))

    def current: Scratch =
        val s = tl.get().nn
        if s.spilled && s.scratchSize < maxScratchSize then
            val newSize = math.min(s.scratchSize * 2, maxScratchSize)
            s.closeArena()
            val grown = new Scratch(newSize)
            tl.set(grown)
            grown
        else s
        end if
    end current

    /** Acquire the per-thread scratch for a binding. The `bindingFqn` and `size` arguments are accepted for API compatibility with
      * generated code but are ignored, the shared per-thread scratch with auto-grow handles all workloads.
      */
    def currentFor(bindingFqn: String, size: Long): Scratch =
        current

    /** Bulk NUL-byte scan using 8-byte word reads with the SWAR zero-byte detection trick. Returns the absolute offset of the first NUL
      * byte in `seg` within `[from, limit)`, or -1 if no NUL is found.
      *
      * @param seg
      *   the memory segment to scan.
      * @param from
      *   the absolute byte offset to start scanning at.
      * @param limit
      *   the exclusive upper bound (absolute offset), scanning stops before this offset.
      */
    private[ffi] def findNul(seg: MemorySegment, from: Long, limit: Long): Long =
        val lo  = 0x0101010101010101L
        val hi  = 0x8080808080808080L.toLong
        var pos = from
        // Bulk 8-byte scan
        while pos + 8L <= limit do
            val v = seg.get(ValueLayout.JAVA_LONG_UNALIGNED, pos)
            if ((v - lo) & ~v & hi) != 0L then
                // Zero byte detected in this 8-byte chunk, find exact position
                var j = pos
                while j < pos + 8L do
                    if seg.get(ValueLayout.JAVA_BYTE, j) == 0 then return j
                    j += 1L
                end while
            end if
            pos += 8L
        end while
        // Tail bytes (< 8 remaining)
        while pos < limit do
            if seg.get(ValueLayout.JAVA_BYTE, pos) == 0 then return pos
            pos += 1L
        end while
        -1L
    end findNul

    /** Maximum number of bytes the generated struct-field / multi-value String reader will scan for a NUL terminator when decoding a C
      * string pointer returned by a C function. Override via `-Dkyo.ffi.stringFieldMaxBytes=<bytes>`. Default 64 KiB.
      *
      * The bounded cap fails fast if the C library returns a non-NUL-terminated pointer and caps the reinterpretation window so reads do
      * not walk past allocation boundaries.
      */
    val stringFieldMaxBytes: Long =
        sys.props.get("kyo.ffi.stringFieldMaxBytes")
            .flatMap(s => scala.util.Try(s.toLong).toOption)
            .getOrElse(64L * 1024L)

    /** Bounded `readCString` for pointers returned from C that are expected to be NUL-terminated within [[stringFieldMaxBytes]].
      *
      * Reinterpretation uses `cappedBytes` rather than `Long.MaxValue`. Throws [[kyo.ffi.FfiMalformedResult]] when the NUL terminator is
      * not found within the cap, names the binding + method + field so debugging can start from the C signature.
      *
      * @param addrSeg
      *   the NULL-terminated MemorySegment carrying the C `char*`, typically already reinterpreted to `cappedBytes` by the caller.
      * @param offset
      *   byte offset at which the C string starts inside [[addrSeg]]. Always `0L` for struct-field / multi-value reads but exposed for
      *   parity with alternate read paths.
      * @param cappedBytes
      *   the capped reinterpret length, must equal [[stringFieldMaxBytes]] at current use-sites; parameterized so tests can provoke the
      *   overrun path with a small cap.
      * @param bindingFqn
      *   binding trait FQN, surfaced in the thrown error message.
      * @param methodName
      *   method name on the binding trait.
      * @param fieldName
      *   field (or multi-value out-param) name.
      */
    def readCStringBounded(
        addrSeg: MemorySegment,
        offset: Long,
        cappedBytes: Long,
        bindingFqn: String,
        methodName: String,
        fieldName: String
    ): String =
        // The caller already reinterprets to `cappedBytes`; scan up to that cap looking for a NUL.
        val size = addrSeg.byteSize()
        val end0 = if size < cappedBytes then size else cappedBytes
        val nul  = findNul(addrSeg, offset, end0)
        if nul < 0 then
            throw new kyo.ffi.FfiMalformedResult(
                FfiGenErrors.stringFieldUnbounded(bindingFqn, methodName, fieldName, cappedBytes)
            )
        end if
        val len = (nul - offset).toInt
        if len == 0 then ""
        else
            val arr = new Array[Byte](len)
            MemorySegment.copy(addrSeg, ValueLayout.JAVA_BYTE, offset, arr, 0, len)
            new String(arr, StandardCharsets.UTF_8)
        end if
    end readCStringBounded

    /** Cleanup action for the [[java.lang.ref.Cleaner]] that closes the confined Arena when a [[Scratch]] becomes unreachable (e.g. when
      * the owning thread dies and the ThreadLocal is cleared). Captures only the [[Arena]], never `this`, so it does not prevent GC of
      * the Scratch instance. Swallows [[IllegalStateException]] because [[closeArena]] may have already closed the arena (e.g. during
      * auto-grow).
      */
    private[ffi] class ArenaCleanup(arena: Arena) extends Runnable:
        def run(): Unit =
            try arena.close()
            catch case _: IllegalStateException => () // already closed
    end ArenaCleanup

    private val arenaCleaner: java.lang.ref.Cleaner = java.lang.ref.Cleaner.create().nn

    final class Scratch(size: Long):
        private[ffi] val scratchSize: Long = size
        private val arena: Arena           = Arena.ofConfined().nn
        private val base: MemorySegment    = arena.allocate(size).nn
        private var offset: Long           = 0L
        private var spill: List[Arena]     = Nil
        @volatile var spilled: Boolean     = false

        // Register cleanup so the arena is closed when this Scratch is GC'd (e.g. thread death).
        private val cleanable = Scratch.arenaCleaner.register(this, new ArenaCleanup(arena))

        private[ffi] def closeArena(): Unit =
            arena.close()
            spill.foreach(a =>
                try a.close()
                catch case _: Throwable => ()
            )
            spill = Nil
        end closeArena

        def mark(): Long = offset

        def alloc(byteSize: Long, align: Long): MemorySegment =
            alloc(byteSize, align, "<unknown-binding>", "<unknown-method>")

        /** Binding-aware variant of [[alloc]]. When the allocation cannot satisfy from the per-thread block and must spill to a fresh
          * confined arena, the spill is logged via [[FfiErrors.scratchSpilled]] to stderr if `-Dkyo.ffi.scratch.logSpills=true`. Emitted
          * code passes the binding FQN + method name so the log names the call site.
          */
        def alloc(byteSize: Long, align: Long, bindingFqn: String, methodName: String): MemorySegment =
            val aligned = (offset + align - 1L) & -align
            if aligned + byteSize > size then
                val a = Arena.ofConfined().nn
                spill = a :: spill
                spilled = true
                if Scratch.logSpills then
                    java.lang.System.err.println(FfiGenErrors.scratchSpilled(bindingFqn, methodName, byteSize))
                a.allocate(byteSize, align).nn
            else
                offset = aligned + byteSize
                base.asSlice(aligned, byteSize).nn
            end if
        end alloc

        def allocUtf8(s: String): MemorySegment =
            allocUtf8(s, "<unknown-binding>", "<unknown-method>")

        def allocUtf8(s: String, bindingFqn: String, methodName: String): MemorySegment =
            val bytes = s.getBytes(StandardCharsets.UTF_8).nn
            val seg   = alloc(bytes.length.toLong + 1L, 1L, bindingFqn, methodName)
            MemorySegment.copy(bytes, 0, seg, ValueLayout.JAVA_BYTE, 0L, bytes.length)
            seg.set(ValueLayout.JAVA_BYTE, bytes.length.toLong, 0: Byte)
            seg
        end allocUtf8

        def reset(m: Long): Unit =
            offset = m
            if spill.nonEmpty then
                spill.foreach(a =>
                    try a.close()
                    catch case _: Throwable => ()
                )
                spill = Nil
            end if
        end reset

        /** Diagnostic access used by the JVM emitter's retained-callback path: when a method that carries a retained callback parameter
          * spills during argument marshalling, the spill arenas must outlive the method's `finally`-reset (otherwise the C side invoking
          * the retained callback later reads freed memory). The emitter calls [[takeSpills]] BEFORE the `finally`-reset, transferring
          * ownership of the spill arenas to the enclosing `Ffi.Guard` via `JvmGuard.adoptArena`. The scratch's spill list is cleared so the
          * subsequent `reset` does not double-close.
          *
          * Returns the arenas in allocation order (earliest first). After this call, the scratch behaves as if no spills occurred on this
          * hot-path, the caller owns those arenas' close.
          */
        def takeSpills(): List[Arena] =
            val out = spill.reverse
            spill = Nil
            out
        end takeSpills

        /** Internal: number of currently-unclosed spill arenas. Used by tests asserting `takeSpills` / `reset` semantics. */
        private[ffi] def spillCount: Int = spill.length
    end Scratch
end Scratch
