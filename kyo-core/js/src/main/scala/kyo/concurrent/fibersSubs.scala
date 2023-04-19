package java.util.concurrent.locks

class AbstractQueuedSynchronizer {
  private def fail =
    throw new UnsupportedOperationException("fiber.block is not supported in ScalaJS")
  def releaseShared(ign: Int): Boolean           = fail
  def acquireSharedInterruptibly(ign: Int): Unit = fail
  def tryAcquireShared(ignored: Int): Int        = fail
  def tryReleaseShared(ignore: Int): Boolean     = fail
  def getState                                   = fail
  def setState(s: Int): Unit                     = fail
}
