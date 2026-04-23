package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Singleton Kqueue backend. Passes filter codes -1 (read) / -2 (write) to the C wrapper, which installs the kevent with EV_ONESHOT. The
  * wrapper writes the same codes back into `outMeta` when the event fires, so each entry is either read-ready or write-ready (never both).
  */
private[kyo] object KqueuePollerBackend extends PollerBackend:
    import PosixBindings.*

    def create()(using AllowUnsafe): CInt                                      = kqueueCreate()
    def registerRead(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt  = kqueueRegister(pollerFd, targetFd, -1)
    def registerWrite(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt = kqueueRegister(pollerFd, targetFd, -2)
    def deregister(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): Unit =
        () // see PollerBackend.deregister: kqueue one-shot auto-removes, closed fd is harmless
    def poll(pollerFd: CInt, outFds: Ptr[CInt], outMeta: Ptr[CInt], maxEvents: CInt)(using AllowUnsafe): CInt =
        kqueueWait(pollerFd, outFds, outMeta, maxEvents)
    def isRead(meta: CInt): Boolean                    = meta == -1
    def isWrite(meta: CInt): Boolean                   = meta == -2
    def close(pollerFd: CInt)(using AllowUnsafe): Unit = tcpClose(pollerFd)
end KqueuePollerBackend
