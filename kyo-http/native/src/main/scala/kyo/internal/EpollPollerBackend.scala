package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Singleton Epoll backend. Passes mode codes 1 (read) / 2 (write) to the C wrapper, which adds EPOLLONESHOT before handing the event to
  * the kernel. The C wrapper returns the raw kernel event mask in `meta`, so `isRead/isWrite` decode the actual epoll bits
  * (EPOLLIN=0x01, EPOLLOUT=0x04, EPOLLERR=0x08, EPOLLHUP=0x10). EPOLLERR/EPOLLHUP signal connection-level error or hangup; the idiomatic
  * epoll pattern wakes BOTH read and write watchers in that case so whichever side was blocked can observe the error via `tcpConnectError`
  * / `read()` / `write()`.
  */
private[kyo] object EpollPollerBackend extends PollerBackend:
    import PosixBindings.*

    // Linux kernel ABI; stable across versions.
    private inline val EPOLLIN  = 0x01
    private inline val EPOLLOUT = 0x04
    private inline val EPOLLERR = 0x08
    private inline val EPOLLHUP = 0x10

    def create()(using AllowUnsafe): CInt                                      = epollCreate()
    def registerRead(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt  = epollRegister(pollerFd, targetFd, 1)
    def registerWrite(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt = epollRegister(pollerFd, targetFd, 2)
    def deregister(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): Unit    = discard(epollDeregister(pollerFd, targetFd))
    def poll(pollerFd: CInt, outFds: Ptr[CInt], outMeta: Ptr[CInt], maxEvents: CInt)(using AllowUnsafe): CInt =
        epollWaitTimeout(pollerFd, outFds, outMeta, maxEvents)
    def isRead(meta: CInt): Boolean                    = (meta & (EPOLLIN | EPOLLERR | EPOLLHUP)) != 0
    def isWrite(meta: CInt): Boolean                   = (meta & (EPOLLOUT | EPOLLERR | EPOLLHUP)) != 0
    def close(pollerFd: CInt)(using AllowUnsafe): Unit = tcpClose(pollerFd)
end EpollPollerBackend
