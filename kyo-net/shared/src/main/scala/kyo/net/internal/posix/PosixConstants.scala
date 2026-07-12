package kyo.net.internal.posix

import kyo.internal.SystemPlatformSpecific

/** POSIX numeric constants needed by the socket / poller bindings, resolved per operating system at runtime.
  *
  * The values genuinely differ across OSes: `AF_INET6` is 30 on macOS/BSD versus 10 on Linux; `O_NONBLOCK` and the `SO_NOSIGPIPE` /
  * `MSG_NOSIGNAL` SIGPIPE-suppression mechanisms differ; the `S_IF*` file-type bits and the epoll control ops are Linux-only. A single JVM
  * artifact runs on both Linux and macOS, and a single Scala Native source set compiles for both, so these are dispatched on the running OS
  * rather than baked in as per-platform literals. The constants themselves are stable kernel ABI values, so the literal table is the source of
  * truth (kyo-ffi has no header-constant extraction surface, and adding one for a fixed handful of stable values is not warranted).
  */
private[net] object PosixConstants:

    // Resolved via SystemPlatformSpecific.osName() (the same accessor kyo.System.operatingSystem uses), not
    // java.lang.System.getProperty: on Scala.js the JVM property is empty, so osName() falls back to Node's
    // process.platform and returns the real OS name on every backend (JVM/Native via os.name, JS via process.platform).
    // This mirrors the osArch() pattern in PosixStructs.scala / EpollEvent.
    private val osName: String =
        import kyo.AllowUnsafe.embrace.danger
        // TODO this should be in kyo.internal.Platform
        SystemPlatformSpecific.osName().toLowerCase
    end osName

    /** True on macOS/BSD, where `AF_INET6` is 30 and SIGPIPE is suppressed via `SO_NOSIGPIPE` rather than `MSG_NOSIGNAL`. */
    val isMacOrBsd: Boolean = osName.contains("mac") || osName.contains("bsd") || osName.contains("darwin")

    /** True on Linux, where epoll, `MSG_NOSIGNAL`, and `AF_INET6 == 10` apply. */
    val isLinux: Boolean = osName.contains("linux")

    // --- address families (sa_family_t, host byte order) ---
    val AF_INET: Int  = 2
    val AF_UNIX: Int  = 1
    val AF_INET6: Int = if isMacOrBsd then 30 else 10

    // --- socket types / protocols ---
    val SOCK_STREAM: Int = 1
    val IPPROTO_TCP: Int = 6

    // --- fcntl ---
    val F_GETFL: Int = 3
    val F_SETFL: Int = 4
    // O_NONBLOCK is 0x0004 on macOS/BSD, 0x0800 on Linux.
    val O_NONBLOCK: Int = if isMacOrBsd then 0x0004 else 0x0800

    // --- open access modes ---
    // O_RDONLY is 0 on Linux and macOS/BSD (the standard POSIX open access mode). Used by the open
    // shim in tests to obtain a raw regular-file fd for the driver-selection routing checks.
    val O_RDONLY: Int = 0

    // --- setsockopt levels / options ---
    val SOL_SOCKET: Int   = if isMacOrBsd then 0xffff else 1
    val SO_REUSEADDR: Int = if isMacOrBsd then 0x0004 else 2
    val SO_ERROR: Int     = if isMacOrBsd then 0x1007 else 4
    val TCP_NODELAY: Int  = if isMacOrBsd then 0x0001 else 1
    // Linux-only (TCP_QUICKACK has no macOS/BSD equivalent); used only behind an isLinux gate. The macOS slot is unused (0).
    val TCP_QUICKACK: Int = if isMacOrBsd then 0 else 12
    // Send / receive buffer sizes. Used by the write-backpressure regression test to shrink the kernel buffers so a large send hits EAGAIN
    // deterministically; the driver itself does not set these.
    val SO_SNDBUF: Int = if isMacOrBsd then 0x1001 else 7
    val SO_RCVBUF: Int = if isMacOrBsd then 0x1002 else 8
    // macOS/BSD per-socket SIGPIPE suppression; absent on Linux (0 sentinel, driver uses MSG_NOSIGNAL there).
    val SO_NOSIGPIPE: Int = if isMacOrBsd then 0x1022 else 0
    // Linux send() flag suppressing SIGPIPE; absent on macOS (0 sentinel, driver uses SO_NOSIGPIPE there).
    val MSG_NOSIGNAL: Int = if isLinux then 0x4000 else 0
    // Per-call non-blocking flag for recv/send: makes a single call non-blocking regardless of the fd's O_NONBLOCK mode.
    val MSG_DONTWAIT: Int = if isMacOrBsd then 0x80 else 0x40
    // Per-call recv flag: returns the data without consuming it from the socket buffer (same value on Linux and macOS/BSD).
    val MSG_PEEK: Int = 0x2

    // --- shutdown (same values on Linux and macOS/BSD) ---
    val SHUT_RD: Int   = 0
    val SHUT_RDWR: Int = 2

    // --- errno values the drivers branch on ---
    val EINPROGRESS: Int = if isMacOrBsd then 36 else 115
    val EAGAIN: Int      = if isMacOrBsd then 35 else 11
    val EWOULDBLOCK: Int = EAGAIN
    // accept(2) resource / transient errnos. EINTR (4) and ECONNABORTED are transient: the call was interrupted or the peer aborted before
    // accept returned, so the accept loop retries (the man page says treat ECONNABORTED like EAGAIN). EMFILE (24, the per-process fd limit) and
    // ENFILE (the system-wide fd limit) are resource exhaustion: accept does NOT dequeue the pending connection, so the listen fd stays
    // read-ready and an immediate re-arm would re-fire the same error in a tight loop. The accept loop must back off on these instead. EINTR is
    // 4 on both Linux and macOS/BSD; EMFILE is 24 on both; ENFILE is 23 on both; ECONNABORTED is 53 on macOS/BSD and 103 on Linux.
    val EINTR: Int        = 4
    val EMFILE: Int       = 24
    val ENFILE: Int       = 23
    val ECONNABORTED: Int = if isMacOrBsd then 53 else 103
    // EBUSY is 16 on both Linux and macOS/BSD; the io_uring reap loop treats it as a transient retry condition.
    val EBUSY: Int = 16
    // io_uring's bounded wait returns -ETIME on a timeout with no completion; the reap loop treats it as a normal empty turn.
    // io_uring is Linux-only, so the Linux value (62) is the one the driver ever sees; the macOS/BSD value (60) is kept for completeness.
    val ETIME: Int = if isMacOrBsd then 60 else 62
    // ENOMEM is 12 on both Linux and macOS/BSD. io_uring_enter (the syscall behind kyo_uring_submit_and_wait_timeout) returns it when the
    // kernel could not allocate memory for the request right now, a momentary condition under concurrent memory pressure (many rings /
    // connections sharing a host, e.g. a full test-suite run), not a broken ring; the io_uring reap loop treats it as a transient retry
    // condition, same as EBUSY/EAGAIN/EINTR, rather than tearing the whole ring (and every connection on it) down.
    val ENOMEM: Int = 12

    // --- epoll (Linux) ---
    val EPOLL_CTL_ADD: Int = 1
    val EPOLL_CTL_DEL: Int = 2
    val EPOLL_CTL_MOD: Int = 3
    val EPOLLIN: Int       = 0x001
    val EPOLLOUT: Int      = 0x004
    // Error / hangup bits the kernel sets on a watched fd even when no read/write interest is requested. A readiness event carrying ONLY one of
    // these (peer reset / hangup) must not be dropped: the driver fails the fd's pending read/write rather than waiting for a later op to notice.
    val EPOLLERR: Int     = 0x008
    val EPOLLHUP: Int     = 0x010
    val EPOLLONESHOT: Int = 1 << 30
    // EPOLLET enables edge-triggered mode: the kernel fires readiness once per empty->ready transition (one event per state change, not
    // continuously while data is present). Combined with EPOLLRDHUP for half-close detection, this eliminates the per-event EPOLLONESHOT
    // re-arm: the fd is registered once and stays armed until explicitly deregistered.
    val EPOLLET: Int = 1 << 31
    // EPOLLRDHUP detects peer half-close (peer called shutdown(SHUT_WR) or close). It fires alongside EPOLLIN when there are still buffered
    // bytes before the EOF, so data must be drained before surfacing the half-close. Always included in the interest mask under EPOLLET so
    // the driver learns about peer half-close without a subsequent recv returning 0.
    val EPOLLRDHUP: Int = 0x2000
    // Flags for `eventfd(2)`, the epoll poll-loop wakeup fd: EFD_CLOEXEC closes it across exec, EFD_NONBLOCK makes the counter read/write
    // non-blocking (a drained counter reads EAGAIN instead of parking, which the wakeup-drain loop relies on). Linux-only (eventfd is a Linux
    // syscall); the literals are the stable Linux ABI values.
    val EFD_CLOEXEC: Int  = 0x80000
    val EFD_NONBLOCK: Int = 0x800

    // `poll(2)` event mask for the io_uring reap-loop wakeup: a multishot IORING_OP_POLL_ADD on the wake eventfd watches it for POLLIN, so a
    // cross-carrier eventfd write returns the parked reap wait. On Linux POLLIN equals EPOLLIN (0x001); kept distinct for the poll(2) call site.
    val POLLIN: Int = 0x001

    // --- kqueue (macOS/BSD) filters and flags ---
    val EVFILT_READ: Short  = -1
    val EVFILT_WRITE: Short = -2
    // EVFILT_USER is the user-triggered filter kqueue exposes for an application to wake its own blocked `kevent`. It is the kqueue poll-loop
    // wakeup mechanism (the analog of the epoll eventfd): a one-shot `NOTE_TRIGGER` change makes the parked poll return so the change FIFO is
    // drained promptly. Stable macOS/BSD ABI value.
    val EVFILT_USER: Short = -10
    val EV_ADD: Short      = 0x0001
    val EV_DELETE: Short   = 0x0002
    val EV_ENABLE: Short   = 0x0004
    // EV_DISABLE deactivates a kqueue filter without removing it from the interest list. Used to suppress spurious write-ready events after a
    // write completes: the EVFILT_WRITE filter stays registered (EV_ADD | EV_CLEAR | EV_ENABLE) and is toggled off with EV_DISABLE after the
    // write drains, then toggled back on with EV_ENABLE when the next awaitWritable call arms the fd for writing. This avoids the overhead of
    // EV_DELETE + EV_ADD per write cycle while preventing the send-buffer-full filter from firing continuously.
    val EV_DISABLE: Short = 0x0008
    // EV_CLEAR auto-resets the EVFILT_USER trigger state after the event is delivered, so one NOTE_TRIGGER wakes the poll exactly once and the
    // filter re-arms for the next wake without an explicit reset (the level-vs-edge analog of draining the epoll eventfd counter).
    val EV_CLEAR: Short   = 0x0020
    val EV_ONESHOT: Short = 0x0010
    // NOTE_TRIGGER is the fflags bit that fires an already-registered EVFILT_USER filter; the wake path encodes it into the change so the parked
    // `kevent` returns. Stable macOS/BSD ABI value.
    val NOTE_TRIGGER: Int = 0x01000000
    // kqueue per-event error / end-of-file flags. EV_ERROR marks a changelist/event error (the error code lands in the event's `data`); EV_EOF
    // is set when the peer has shut down or reset. Either on a readiness event signals the fd is dead, so the driver fails its pending op rather
    // than dropping the event.
    val EV_ERROR: Short = 0x4000
    val EV_EOF: Short   = 0x8000.toShort

    // --- file-type bits for fstat-based stdio pollability (S_ISREG / S_ISFIFO / S_ISCHR) ---
    val S_IFMT: Int  = 0xf000
    val S_IFREG: Int = 0x8000
    val S_IFIFO: Int = 0x1000
    val S_IFCHR: Int = 0x2000

    // TODO hmm generous sounds like unsafe
    /** Generous upper bound on `sizeof(struct stat)` across the supported OS/arch matrix (Linux x86_64/aarch64 are 128-144 bytes, macOS is
      * 144 bytes). [[PosixStructs.Stat]] reads only `st_mode` at the OS-specific offset, so over-allocating the buffer is safe: `fstat` fills
      * the leading prefix and the trailing slack is ignored.
      */
    val statSize: Int = 256

end PosixConstants
