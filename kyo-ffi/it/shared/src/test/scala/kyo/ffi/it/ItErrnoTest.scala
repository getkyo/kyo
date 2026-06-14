package kyo.ffi.it

import kyo.ffi.Ffi
import kyo.ffi.FfiErrno

/** Cross-platform errno-capture spec.
  *
  * `kyoItAlwaysFail` sets `errno = EINVAL` (22) and returns `-1`. The method declares `WithError[Int]` so the caller can inspect both the
  * return value and the captured error code.
  *
  * `kyoItClearErrno` sets `errno = 0` and returns `1`. The method declares a plain `Int` return, since errno = 0, no `FfiErrno` is thrown.
  *
  * `EINVAL` is numerically 22 on Linux, macOS, and Windows.
  */
class ItErrnoTest extends ItTestBase:

    "kyoItAlwaysFail (WithError return)" - {
        "returns -1 with errorCode 22 (EINVAL)" in {
            val b = Ffi.load[ItErrnoBindings]
            val r = b.kyoItAlwaysFail()
            assert(r.value == -1)
            assert(r.errorCode == 22)
        }

        "return value and errorCode are stable across repeated invocations" in {
            val b          = Ffi.load[ItErrnoBindings]
            var i          = 0
            var last: Unit = succeed
            while i < 8 do
                val r = b.kyoItAlwaysFail()
                assert(r.value == -1)
                last = assert(r.errorCode == 22)
                i += 1
            end while
            last
        }

        "paired invocations observe identical errorCode values" in {
            val b  = Ffi.load[ItErrnoBindings]
            val r1 = b.kyoItAlwaysFail()
            val r2 = b.kyoItAlwaysFail()
            assert(r1.errorCode == r2.errorCode)
        }
    }

    "kyoItClearErrno (plain return, errno = 0)" - {
        "returns 1 without throwing" in {
            val b = Ffi.load[ItErrnoBindings]
            assert(b.kyoItClearErrno() == 1)
        }

        "succeeds after a failing call (errno isolation per call)" in {
            val b = Ffi.load[ItErrnoBindings]
            val r = b.kyoItAlwaysFail()
            assert(r.errorCode == 22)
            // The subsequent call clears errno, plain return does not throw.
            assert(b.kyoItClearErrno() == 1)
        }

        "second-to-last clearing followed by a failing call shows errorCode 22" in {
            val b = Ffi.load[ItErrnoBindings]
            assert(b.kyoItClearErrno() == 1)
            val r = b.kyoItAlwaysFail()
            assert(r.value == -1)
            assert(r.errorCode == 22)
        }
    }
end ItErrnoTest
