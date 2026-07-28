package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.internal.Platform

/** POSIX bindings spec, shared across platforms. On Windows the leaves run only on the JVM, whose default lookup resolves the POSIX
  * names; the CRT exports only underscore-prefixed variants (`_getpid`, `_time64`), so the Node targets cancel there.
  *
  * Six assertions spanning `getpid` and `time`. `getenv` coverage is deferred; see `PosixBindings` for the rationale (kyo-ffi codegen does
  * not yet support `String` or borrowed `Buffer[Byte]` as a top-level return, which is what a stock `getenv` binding needs).
  *
  * Extends getpid stability + time monotonicity to more iterations and cross-call invariants.
  */
class PosixTest extends ItTestBase:

    private def assumePosixSymbols(): Unit =
        if Platform.isWindows && !Platform.isJVM then
            cancel("POSIX symbol names are unavailable in Windows CRT exports")

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

        "is close to java.lang.System.currentTimeMillis / 1000" in {
            assumePosixSymbols()
            val posix    = Ffi.load[PosixBindings]
            val cSeconds = posix.time(0L)
            val jSeconds = java.lang.System.currentTimeMillis() / 1000L
            // Absolute skew tolerance: 5 seconds. If the two read points straddle a
            // wall-clock adjustment of more than this, the assertion would (correctly)
            // flag a genuine clock anomaly.
            assert(math.abs(cSeconds - jSeconds) <= 5L)
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

        "values are within a narrow window of each other across rapid calls" in {
            assumePosixSymbols()
            // Sanity check: 32 rapid-fire time() calls should span at most a
            // few seconds. 30s is a very generous upper bound that will flag
            // any wildly broken binding (e.g. returning uninitialized scratch)
            // while tolerating slow CI hosts.
            val posix      = Ffi.load[PosixBindings]
            val first      = posix.time(0L)
            var i          = 0
            var last: Unit = succeed
            while i < 32 do
                val cur = posix.time(0L)
                last = assert((cur - first) <= 30L)
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
