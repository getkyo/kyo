package kyo.ffi

class FfiOutcomeTest extends Test:

    "decodeTotality" - {
        "positive fd success: fromValueErrno(1024, 0)" in {
            val o = Ffi.Outcome.fromValueErrno[Int](1024L, 0)
            assert(o.value == 1024L)
            assert(o.errorCode == 0)
            assert(!o.isError)
        }

        "zero success: fromValueErrno(0, 0)" in {
            val o = Ffi.Outcome.fromValueErrno[Int](0L, 0)
            assert(o.value == 0L)
            assert(o.errorCode == 0)
            assert(!o.isError)
        }

        "large byte count success: fromValueErrno(1L << 40, 0)" in {
            val o = Ffi.Outcome.fromValueErrno[Long](1L << 40, 0)
            assert(o.value == (1L << 40))
            assert(o.errorCode == 0)
            assert(!o.isError)
        }

        "EBADF error: fromValueErrno(-1, 9)" in {
            val o = Ffi.Outcome.fromValueErrno[Int](-1L, 9)
            assert(o.errorCode == 9)
            assert(o.isError)
            assert(o.value == -1L)
        }

        "EAGAIN error: fromValueErrno(-1, 11)" in {
            val o = Ffi.Outcome.fromValueErrno[Int](-1L, 11)
            assert(o.errorCode == 11)
            assert(o.isError)
            assert(o.value == -1L)
        }

        "ETIMEDOUT error: fromValueErrno(-1, 110)" in {
            val o = Ffi.Outcome.fromValueErrno[Int](-1L, 110)
            assert(o.errorCode == 110)
            assert(o.isError)
            assert(o.value == -1L)
        }

        "throwing model preserved: Ffi.load of missing binding still throws" in {
            intercept[Throwable](Ffi.load[FfiOutcomeTest.MissingBindings])
            succeed
        }
    }

    "intWidthSignExtension" - {
        // An int-width C return of -1 reaches the packer sign-extended (-1L), not zero-extended (4294967295L). This
        // is the width contract the phantom Outcome[Int] carrier exists to hold: the codegen reads it at JAVA_INT, so
        // the negative sentinel arrives as -1L. A guard for the ABI defect where Outcome[Int] read a C int at
        // JAVA_LONG, decoding -1 as 4294967295. The packer receives the sign-extended Long; this leaf pins that an
        // Outcome[Int] built from it decodes .value == -1L directly (no .toInt recovery), and that the unsigned
        // 32-bit form 4294967295L is NOT what .value yields.
        "negative int-width sentinel decodes .value == -1L directly" in {
            val sentinel = (-1).toLong
            val o        = Ffi.Outcome.fromValueErrno[Int](sentinel, 22)
            assert(o.value == -1L)
            assert(o.value != 4294967295L)
            assert(o.errorCode == 22)
            assert(o.isError)
        }
    }

end FfiOutcomeTest

object FfiOutcomeTest:
    private trait MissingBindings extends Ffi
end FfiOutcomeTest
