package kyo.ffi

class WithErrorTest extends Test:

    "WithError" - {
        "stores value and errorCode with zero error" in {
            val r = new Ffi.WithError(42, 0)
            assert(r.value == 42)
            assert(r.errorCode == 0)
        }

        "stores non-zero errorCode" in {
            val r = new Ffi.WithError(-1, 22)
            assert(r.value == -1)
            assert(r.errorCode == 22)
        }

        "stores Long value" in {
            val r = new Ffi.WithError(Long.MaxValue, 5)
            assert(r.value == Long.MaxValue)
            assert(r.errorCode == 5)
        }

        "stores Double value" in {
            val r = new Ffi.WithError(3.14, 0)
            assert(r.value == 3.14)
            assert(r.errorCode == 0)
        }

        "stores Boolean value" in {
            val r = new Ffi.WithError(true, 0)
            assert(r.value == true)
            assert(r.errorCode == 0)
        }

        "stores Unit value" in {
            val r = new Ffi.WithError((), 13)
            assert(r.value == ())
            assert(r.errorCode == 13)
        }

        "stores large errorCode (Int.MaxValue)" in {
            val r = new Ffi.WithError(0, Int.MaxValue)
            assert(r.value == 0)
            assert(r.errorCode == Int.MaxValue)
        }

        "private[kyo] apply factory creates instance" in {
            val r = Ffi.WithError(99, 42)
            assert(r.value == 99)
            assert(r.errorCode == 42)
        }
    }

    "FfiErrno" - {
        "includes errorCode and context in message" in {
            val e = FfiErrno(22, "MyBindings", "riskyOp")
            assert(e.errorCode == 22)
            assert(e.getMessage.contains("22"))
            assert(e.getMessage.contains("MyBindings"))
            assert(e.getMessage.contains("riskyOp"))
        }

        "is a RuntimeException" in {
            val e = FfiErrno(1, "B", "m")
            assert(e.isInstanceOf[RuntimeException])
        }

        "preserves errorCode as a field accessible after catch" in {
            val caught =
                try
                    throw FfiErrno(42, "TestBinding", "testMethod")
                catch
                    case e: FfiErrno => e
            assert(caught.errorCode == 42)
            assert(caught.getMessage.contains("42"))
            assert(caught.getMessage.contains("TestBinding"))
            assert(caught.getMessage.contains("testMethod"))
        }
    }

end WithErrorTest
