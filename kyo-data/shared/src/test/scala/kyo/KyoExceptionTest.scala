package kyo

class KyoExceptionTest extends Test:

    "getMessage" - {
        "includes Throwable cause class and message" in {
            val ex  = new KyoException("outer", new java.io.IOException("inner"))
            val msg = ex.getMessage
            assert(msg.contains("outer"), s"expected 'outer' in '$msg'")
            assert(msg.contains("IOException"), s"expected 'IOException' in '$msg'")
            assert(msg.contains("inner"), s"expected 'inner' in '$msg'")
        }
        "handles Throwable with null message" in {
            val ex  = new KyoException("outer", new RuntimeException())
            val msg = ex.getMessage
            assert(msg.contains("outer"), s"expected 'outer' in '$msg'")
            assert(msg.contains("RuntimeException"), s"expected 'RuntimeException' in '$msg'")
        }
        "includes String cause" in {
            val ex  = new KyoException("outer", "hint text")
            val msg = ex.getMessage
            assert(msg.contains("outer"), s"expected 'outer' in '$msg'")
            assert(msg.contains("hint text"), s"expected 'hint text' in '$msg'")
        }
        "uses message only when no cause is provided" in {
            val ex  = new KyoException("just the msg")
            val msg = ex.getMessage
            assert(msg.contains("just the msg"), s"expected 'just the msg' in '$msg'")
        }
    }
end KyoExceptionTest
