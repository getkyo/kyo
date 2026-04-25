package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Singleton Epoll backend. Passes mode codes 1 (read) / 2 (write) to the C wrapper, which adds EPOLLONESHOT before handing the event to
  * the kernel. Readiness meta is decoded the same way: bit 0 = read, bit 1 = write, both bits may be set together.
  */
private[kyo] object EpollPollerBackend extends PollerBackend:
    import PosixBindings.*

    def create()(using AllowUnsafe): CInt                                      = epollCreate()
    def registerRead(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt  = epollRegister(pollerFd, targetFd, 1)
    def registerWrite(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt = epollRegister(pollerFd, targetFd, 2)
    def deregister(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): Unit    = discard(epollDeregister(pollerFd, targetFd))
    def poll(pollerFd: CInt, outFds: Ptr[CInt], outMeta: Ptr[CInt], maxEvents: CInt)(using AllowUnsafe): CInt =
        epollWaitTimeout(pollerFd, outFds, outMeta, maxEvents)
    def isRead(meta: CInt): Boolean                    = (meta & 1) != 0
    def isWrite(meta: CInt): Boolean                   = (meta & 2) != 0
    def close(pollerFd: CInt)(using AllowUnsafe): Unit = tcpClose(pollerFd)
end EpollPollerBackend
