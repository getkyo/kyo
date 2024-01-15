package java.util.concurrent.locks

object LockSupport {
  private def fail =
    throw new UnsupportedOperationException("fiber.block is not supported in ScalaJS")
  def park(): Unit            = fail
  def unpark(t: Thread): Unit = fail
}
