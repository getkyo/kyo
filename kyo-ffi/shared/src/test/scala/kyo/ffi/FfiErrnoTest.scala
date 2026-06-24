package kyo.ffi

class FfiErrnoTest extends Test:

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

end FfiErrnoTest
