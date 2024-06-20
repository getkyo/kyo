package java.util.concurrent.locks

object LockSupport:
    private def fail =
        (new Exception).printStackTrace()
        throw new UnsupportedOperationException("fiber.block is not supported in ScalaJS")
    def park(o: Object): Unit         = fail
    def parkNanos(o: Object, l: Long) = fail
    def unpark(t: Thread): Unit       = fail
end LockSupport
