package kyo.internal

import kyo.*
import scala.scalanative.unsafe.*

/** Abstracts the tiny differences between epoll (Linux) and kqueue (macOS/BSD) behind a uniform interface.
  *
  * Each platform provides a singleton backend (no per-call allocation). The shared I/O event loop holds one `PollerBackend` and forwards
  * create/register/poll/deregister calls to it. Epoll and kqueue differ in five points: the fd-create syscall, the register mode encoding,
  * whether explicit deregister is needed, the meta format returned by poll (bit flags vs. filter IDs), and the poll syscall itself. Every
  * other aspect of non-blocking I/O (pending tables, pump dispatch, await loops, cancel semantics) is shared above this class.
  */
abstract private[kyo] class PollerBackend:

    /** Create a new poller fd (epoll fd or kqueue fd). Returns -1 on failure. Performs a syscall. */
    def create()(using AllowUnsafe): CInt

    /** Register one-shot read interest on `targetFd`. Returns the underlying register syscall rc (<0 = failure). Performs a syscall. */
    def registerRead(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt

    /** Register one-shot write interest on `targetFd`. Returns the underlying register syscall rc (<0 = failure). Performs a syscall. */
    def registerWrite(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): CInt

    /** Remove `targetFd` from the poller.
      *
      *   - Epoll implements this via EPOLL_CTL_DEL so the fd no longer produces events.
      *   - Kqueue is a no-op: one-shot filters auto-remove after firing, and any unfired filter becomes harmless as soon as the fd is
      *     closed.
      */
    def deregister(pollerFd: CInt, targetFd: CInt)(using AllowUnsafe): Unit

    /** Block until one or more fds become ready or the backend's internal timeout expires. The underlying extern is `@blocking`, so the Kyo
      * scheduler spawns a fresh worker while this OS thread sleeps in the kernel.
      *
      * @param outFds
      *   filled with ready fds (size at least `maxEvents`)
      * @param outMeta
      *   filled with readiness meta, one per entry:
      *   - Epoll: bitmask using the C wrapper's mode encoding (1 = read, 2 = write, 3 = both).
      *   - Kqueue: filter id set by the C wrapper (-1 = read, -2 = write).
      * @return
      *   number of entries written to outFds/outMeta (0..maxEvents)
      */
    def poll(pollerFd: CInt, outFds: Ptr[CInt], outMeta: Ptr[CInt], maxEvents: CInt)(using AllowUnsafe): CInt

    /** Interpret an `outMeta` entry as "fd is ready to read". Pure — no side effect. On epoll, both `isRead` and `isWrite` may be true for
      * the same entry when read+write readiness arrive in one event; the shared loop processes both branches in that case.
      */
    def isRead(meta: CInt): Boolean

    /** Interpret an `outMeta` entry as "fd is ready to write". Pure — no side effect. See `isRead` for the both-can-be-true note. */
    def isWrite(meta: CInt): Boolean

    /** Close the poller fd. Performs a syscall. */
    def close(pollerFd: CInt)(using AllowUnsafe): Unit

end PollerBackend

/** Selects the appropriate backend for the host OS. Centralizes the OS detection previously duplicated in HttpPlatformTransport, the HTTP
  * client, and the HTTP server.
  */
private[kyo] object PollerBackend:
    lazy val default: PollerBackend =
        val os = java.lang.System.getProperty("os.name", "").toLowerCase
        if os.contains("linux") then EpollPollerBackend
        else KqueuePollerBackend
    end default
end PollerBackend
