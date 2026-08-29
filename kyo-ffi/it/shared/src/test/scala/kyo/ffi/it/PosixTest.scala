package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.internal.Platform

/** POSIX bindings spec, shared across platforms. The bindings resolve the bare POSIX names (`getpid`, `time`) through the native linker's
  * default lookup; the Windows CRT exports these only under underscore-prefixed variants (`_getpid`, `_time64`), and neither Panama's (JVM)
  * nor koffi's (Node) default lookup finds the bare names there, so every Windows target cancels. The leaves run on the Unix JVM, Native, and
  * Node targets.
  *
  * Covers `getpid` (positive, stable across two calls, a tight loop, and a longer burst), `time` (positive, monotonic non-decreasing, and
  * bracketed against the JVM wall clock), and `getenv` (borrowed-String return round-tripped against `java.lang.System.getenv`), extending the
  * stability and cross-call invariants over longer iteration counts.
  */
class PosixTest extends ItTestBase:

    private def assumePosixSymbols(): Unit =
        if Platform.isWindows then
            cancel("bare POSIX symbol names are unavailable in Windows CRT exports")

    "getpid" - {
        "returns a positive process id" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            assert(posix.getpid() > 0)
        }

        "returns the same id across two calls in the same process" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            val a     = posix.getpid()
            val b     = posix.getpid()
            assert(a == b)
        }

        "agrees across repeated calls in a tight loop" in {
            assumePosixSymbols()
            // Stronger than the pair-of-calls test above: 16 back-to-back getpid()
            // calls must all agree (fork-in-middle is the only way this would fail,
            // and the test process does not fork). Avoids Scala Native's linker
            // rejection of `java.lang.ProcessHandle.current()` which is not in its
            // javalib.
            val posix = Ffi.load[PosixBindings]
            val first = posix.getpid()
            var i     = 0
            while i < 16 do
                assert(posix.getpid() == first)
                i += 1
            end while
            succeed
        }

        "stability holds over a longer burst" in {
            assumePosixSymbols()
            // 256 rapid-fire calls: if errno scratch-slot reuse or the
            // generated stub leaks state, this is where it would manifest.
            val posix = Ffi.load[PosixBindings]
            val first = posix.getpid()
            var i     = 0
            while i < 256 do
                assert(posix.getpid() == first)
                i += 1
            end while
            succeed
        }

        "all returned pids are positive" in {
            assumePosixSymbols()
            val posix      = Ffi.load[PosixBindings]
            var i          = 0
            var last: Unit = succeed
            while i < 32 do
                last = assert(posix.getpid() > 0)
                i += 1
            last
        }
    }

    "time(0)" - {
        "returns a positive epoch-seconds value" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            assert(posix.time(0L) > 0L)
        }

        "reads the same epoch clock as java.lang.System" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            // Bracket the C read between two Java reads, with a second of slack. The bracket absorbs host slowness
            // structurally: however long the process stalls between the reads, a same-clock value still lands inside
            // it. The slack absorbs the one real cross-clock difference, that on Scala.js `java.lang.System` reads
            // V8's Date.now while `posix.time` is a genuine time(2) downcall, so the two can round across a second
            // boundary differently. A binding with the wrong unit, the wrong epoch, or garbage misses by orders of
            // magnitude and still fails here, and unlike a magnitude window this also catches one that is merely off
            // by an hour or a day. Ordering and positivity are asserted independently by the other leaves.
            val jBefore = java.lang.System.currentTimeMillis() / 1000L
            val cSecs   = posix.time(0L)
            val jAfter  = java.lang.System.currentTimeMillis() / 1000L
            assert(cSecs >= jBefore - 1 && cSecs <= jAfter + 1, s"time()=$cSecs not within a second of [$jBefore, $jAfter]")
        }

        "two calls are monotonic non-decreasing" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            val a     = posix.time(0L)
            val b     = posix.time(0L)
            assert(b >= a)
        }

        "monotonic non-decreasing across a longer burst" in {
            assumePosixSymbols()
            // 64 calls in sequence: every subsequent reading >= prior. Wall
            // clock is not globally monotonic (NTP slews), but over a ~ms
            // duration within the same process this is effectively monotone.
            val posix      = Ffi.load[PosixBindings]
            var prev       = posix.time(0L)
            var i          = 0
            var last: Unit = succeed
            while i < 64 do
                val cur = posix.time(0L)
                last = assert(cur >= prev)
                prev = cur
                i += 1
            end while
            last
        }

        "each value is positive across multiple reads" in {
            assumePosixSymbols()
            val posix      = Ffi.load[PosixBindings]
            var i          = 0
            var last: Unit = succeed
            while i < 16 do
                last = assert(posix.time(0L) > 0L)
                i += 1
            last
        }

        "each rapid read stays within a second of java.lang.System" in {
            assumePosixSymbols()
            // The single-read leaf's bracket applied to every call in a 32-call burst, so a binding that drifts only
            // under repeated calls is caught too. Each read gets its own surrounding pair rather than one pair around
            // the whole loop, which would widen the window by the loop's duration and weaken every iteration.
            val posix      = Ffi.load[PosixBindings]
            var i          = 0
            var last: Unit = succeed
            while i < 32 do
                val jBefore = java.lang.System.currentTimeMillis() / 1000L
                val cur     = posix.time(0L)
                val jAfter  = java.lang.System.currentTimeMillis() / 1000L
                last = assert(cur >= jBefore - 1 && cur <= jAfter + 1, s"time()=$cur not within a second of [$jBefore, $jAfter]")
                i += 1
            end while
            last
        }
    }

    "getenv (Borrowed[String] return)" - {
        "PATH returns the same value as java.lang.System.getenv" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            val jVal  = java.lang.System.getenv("PATH")
            // PATH is expected to be set on every Unix test host.
            assume(jVal != null, "java.lang.System.getenv(\"PATH\") returned null, test host is not Unix-like")
            val cVal = posix.getenv("PATH").value
            assert(cVal == jVal)
        }

        "a variable that is definitely not set returns null" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            // Name crafted to be implausible; the java-side result must agree for the assumption to hold.
            val name = "KYO_FFI_DEFINITELY_NOT_SET_1234567890"
            assume(java.lang.System.getenv(name) == null, s"$name is unexpectedly set on this host")
            assert(posix.getenv(name).value == null)
        }

        "repeated calls return the same value (String is copied, not aliased)" in {
            assumePosixSymbols()
            val posix = Ffi.load[PosixBindings]
            assume(java.lang.System.getenv("PATH") != null)
            val a = posix.getenv("PATH").value
            val b = posix.getenv("PATH").value
            assert(a == b)
        }
    }
end PosixTest
