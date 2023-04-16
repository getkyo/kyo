package java.util.concurrent.locks

class AbstractQueuedSynchronizer {
  private var state = 0
  def releaseShared(ign: Int): Boolean = {
    state = 1
    true
  }
  def acquireSharedInterruptibly(ign: Int): Unit =
    ()
  def tryAcquireShared(ignored: Int): Int =
    if (state != 0) 1 else -1
  def tryReleaseShared(ignore: Int): Boolean = {
    state = 1
    true
  }
  def getState = state
  def setState(s: Int): Unit =
    state = s
}
