package kyo.net.internal.posix

import kyo.*
import kyo.net.Test

/** Verifies that two epoll drivers keep INDEPENDENT desired-interest state.
  *
  * The epoll arm tracks each fd's currently-armed direction union in [[PollScratch.epollDesired]], a PER-DRIVER map, so registering interest on
  * one driver's fd never shows up in another driver's desired state. Before the fix the desired map was a process singleton on the
  * `EpollPollerBackend` object, so an arm on one driver leaked into every driver's view, breaking the single-owner interest invariant across a
  * multi-driver pool.
  *
  * Two epoll drivers are modeled by two `PollScratch` instances and two real epoll fds. Interest is armed on the FIRST driver's fd via its
  * scratch; the test asserts the FIRST scratch records the interest and the SECOND scratch's desired map stays empty. Pure interest-state
  * assertion on the per-driver map, no timing. FAILs before the fix (the singleton shows the interest on both). Linux-only (epoll); cancels
  * elsewhere, where the real-epoll leg runs in CI.
  */
class EpollPollerBackendDesiredIsolationTest extends Test:

    import AllowUnsafe.embrace.danger
    given Frame = Frame.internal

    "two epoll drivers keep independent desired scratch" in {
        if !PosixConstants.isLinux then cancel("epoll is Linux-only; the desired-isolation leg runs in CI")
        Sync.defer {
            val backend = EpollPollerBackend
            val epfd1   = backend.create()
            val epfd2   = backend.create()
            assert(epfd1 >= 0 && epfd2 >= 0, s"epoll_create1 failed: epfd1=$epfd1 epfd2=$epfd2")
            val scratch1 = backend.newPollScratch()
            val scratch2 = backend.newPollScratch()
            try
                // A real fd to register: a fresh eventfd or pipe read end works; use epfd2 itself as a registerable fd on epfd1 (an epoll fd is a
                // valid pollable fd). The fd value is what the desired map keys on.
                val fd = epfd2
                discard(backend.registerRead(epfd1, fd, scratch1))

                // The first driver's scratch records the armed interest for fd.
                assert(
                    scratch1.epollDesired.containsKey(fd) && (scratch1.epollDesired.get(fd) & PosixConstants.EPOLLIN) != 0,
                    s"driver 1 scratch must record EPOLLIN interest for fd=$fd, got ${scratch1.epollDesired}"
                )
                // The SECOND driver's scratch must NOT see that interest: the desired state is per-driver, not a process singleton.
                assert(
                    !scratch2.epollDesired.containsKey(fd),
                    s"driver 2 scratch leaked driver 1's interest for fd=$fd: ${scratch2.epollDesired} (the desired map must be per-driver)"
                )
            finally
                scratch1.close()
                scratch2.close()
                discard(backend.close(epfd1))
                discard(backend.close(epfd2))
            end try
        }
    }

end EpollPollerBackendDesiredIsolationTest
