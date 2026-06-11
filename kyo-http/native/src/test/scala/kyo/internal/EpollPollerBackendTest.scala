package kyo.internal

import kyo.*

// Pure bit-decoding test for EpollPollerBackend. Does NOT touch the kernel — the C wrapper
// `kyo_epoll_wait_*` passes raw epoll event bits straight to Scala (EPOLLIN=0x01, EPOLLOUT=0x04,
// EPOLLERR=0x08, EPOLLHUP=0x10), and `isRead/isWrite` are pure CInt => Boolean. So even on macOS
// Native (where the epoll backend is wired to stubs) the Scala arithmetic exercises the same logic
// that runs on Linux.
class EpollPollerBackendTest extends BaseHttpTest:

    "isRead detects EPOLLIN (0x01)" in {
        assert(EpollPollerBackend.isRead(0x01))
    }

    "isWrite detects EPOLLOUT (0x04)" in {
        assert(EpollPollerBackend.isWrite(0x04))
    }

    "isWrite detects EPOLLOUT | EPOLLERR (0x04 | 0x08)" in {
        assert(EpollPollerBackend.isWrite(0x0c))
    }

    "isRead detects EPOLLIN | EPOLLERR (0x01 | 0x08)" in {
        assert(EpollPollerBackend.isRead(0x09))
    }

    "EPOLLERR alone (0x08) wakes both read and write watchers" in {
        assert(EpollPollerBackend.isRead(0x08))
        assert(EpollPollerBackend.isWrite(0x08))
    }

end EpollPollerBackendTest
