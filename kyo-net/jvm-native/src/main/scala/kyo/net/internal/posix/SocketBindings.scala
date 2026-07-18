package kyo.net.internal.posix

import kyo.AllowUnsafe
import kyo.Chunk
import kyo.Fiber
import kyo.ffi.Buffer
import kyo.ffi.Ffi

/** POSIX socket syscalls bound through kyo-ffi.
  *
  * Every method is part of the unsafe FFI tier and takes a trailing `(using AllowUnsafe)` clause: each native call is a side effect tracked by
  * the caller.
  *
  * Every fallible call returns `Ffi.Outcome[Int]` / `Ffi.Outcome[Long]` so the driver can read `errno` (the codegen captures it after the
  * downcall on every platform). The blocking syscalls (`connect`, `accept`, `recv`, `send`, `read`, `close`) carry `@Ffi.blocking`, so the
  * generator returns a `Fiber.Unsafe[<value>, Any]` rather than a bare value: on JVM/Native the blocking downcall runs synchronously on the
  * calling carrier (kyo's `BlockingMonitor` parks/compensates) and the result is wrapped in an already-completed fiber; on JS the call is
  * dispatched on a libuv worker and the fiber is genuinely pending. The drivers consume the fiber WITHOUT blocking the carrier: on JVM/Native
  * they read the already-completed result via `done()`/`poll()` (the downcall finished inline), and on JS they attach `onComplete` so the result
  * is delivered when the libuv worker finishes. They never block on the result and never assume it is already completed without checking.
  *
  * SIGPIPE on a write to a peer-closed socket is suppressed two ways, chosen by the driver from the platform constant: on Linux `send` is
  * called with the `MSG_NOSIGNAL` flag; on macOS/BSD the socket is opted out once via `setsockopt(SO_NOSIGPIPE)`. The driver passes the
  * right flag, so `send` here is the single primitive both paths route through.
  *
  * `sockaddr_*` arguments are passed as the manually-encoded `Buffer[Byte]` from [[SockAddr]]: the kernel reads them through a
  * `struct sockaddr*` cast that a case class cannot model. `getsockname`/`accept` take the address length as a 1-element `Buffer[Int]`
  * in/out parameter, matching the `socklen_t*` C signature.
  */
private[net] trait SocketBindings extends Ffi:

    /** `int socket(int domain, int type, int protocol)`. Returns the new fd (>=0) or -1 with `errno` set. */
    def socket(domain: Int, `type`: Int, protocol: Int)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int bind(int fd, const struct sockaddr* addr, socklen_t addrlen)`. `addr` is a [[SockAddr]] encoding; 0 on success. */
    def bind(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int listen(int fd, int backlog)`. 0 on success. */
    def listen(fd: Int, backlog: Int)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int setsockopt(int fd, int level, int optname, const void* optval, socklen_t optlen)`. Used for `SO_REUSEADDR`, `TCP_NODELAY`, and
      * the macOS/BSD `SO_NOSIGPIPE` opt-out. `optval` is a small `Buffer[Byte]` holding the option value.
      */
    def setsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Int)(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int getsockopt(int fd, int level, int optname, void* optval, socklen_t* optlen)`. Used to read `SO_ERROR` after a non-blocking
      * connect. `optval` receives the value, `optlen` is the in/out length.
      */
    def getsockopt(fd: Int, level: Int, optname: Int, optval: Buffer[Byte], optlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int getsockname(int fd, struct sockaddr* addr, socklen_t* addrlen)`. Resolves the bound address, notably the ephemeral port chosen
      * when binding to port 0. `addr` is a [[SockAddr]]-sized buffer, `addrlen` is the in/out length.
      */
    def getsockname(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int getpeername(int fd, struct sockaddr* addr, socklen_t* addrlen)`. Resolves the connected peer's address. Used by the handshake-teardown
      * fd-leak test to attribute an open socket to a connection by its peer port (a connect-side client's peer is the listener), so the leak check
      * counts only the connections under test rather than the process-wide descriptor table, which other suites share under test parallelism.
      */
    def getpeername(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int fstat(int fd, struct stat* buf)`. Fills `buf` (a [[PosixStructs.Stat]]-sized `Buffer[Byte]`) with the fd's metadata; the stdio
      * pollability probe reads `st_mode` to classify the read end (regular file vs pipe vs tty). Returns 0 on success or -1 with
      * `errno`.
      */
    def fstat(fd: Int, buf: Buffer[Byte])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `int shutdown(int fd, int how)`. Half- or full-close of a connected socket. */
    def shutdown(fd: Int, how: Int)(using AllowUnsafe): Int

    /** `int connect(int fd, const struct sockaddr* addr, socklen_t addrlen)`. On a non-blocking socket returns -1 with `errno=EINPROGRESS`
      * while the handshake proceeds; the driver waits for writability and reads `SO_ERROR`. Blocking-annotated for the blocking-socket path:
      * the result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def connect(fd: Int, addr: Buffer[Byte], addrlen: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any]

    /** `int accept(int fd, struct sockaddr* addr, socklen_t* addrlen)`. Returns the accepted client fd. `addr`/`addrlen` may receive the
      * peer address; pass a zeroed buffer when the peer address is not needed. Blocking-annotated: the result is a `Fiber.Unsafe` the caller
      * must await.
      */
    @Ffi.blocking
    def accept(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Int], Any]

    /** `ssize_t recv(int fd, void* buf, size_t len, int flags)`. Returns bytes read (>0), 0 on orderly peer close (EOF), or -1 with `errno`.
      * Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def recv(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any]

    /** `ssize_t send(int fd, const void* buf, size_t len, int flags)`. Returns bytes written. The driver passes `MSG_NOSIGNAL` on Linux to
      * suppress SIGPIPE. Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def send(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any]

    /** `ssize_t send(int fd, const void* buf, size_t len, int flags)`, bound NON-blocking: returns the byte count directly rather than a
      * `Fiber.Unsafe`. The readiness driver only writes to sockets it has set non-blocking (`O_NONBLOCK`), where `send` never blocks: it
      * either accepts bytes and returns the count, or returns -1 with `EAGAIN`/`EWOULDBLOCK` when the kernel buffer is full. The driver's
      * `write` is a synchronous `IoDriver` method, so it needs the count in hand: the `@Ffi.blocking` overload dispatches on a libuv worker on
      * JS and is only resolvable asynchronously (the fiber is genuinely pending), which a synchronous `write` cannot await; this overload runs
      * the same `send` symbol as a plain synchronous downcall on every backend (Panama on JVM, `@extern` on Native, a direct koffi call on JS)
      * so the count is available immediately. Used only on a non-blocking fd; a blocking fd would freeze the event loop here.
      */
    def sendNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long]

    /** `ssize_t recv(int fd, void* buf, size_t len, int flags)`, bound NON-blocking: returns the byte count directly rather than a
      * `Fiber.Unsafe`. The readiness driver only reads from sockets it has set non-blocking (`O_NONBLOCK`), where `recv` never blocks: it
      * returns bytes (>0), 0 on orderly peer close (EOF), or -1 with `EAGAIN`/`EWOULDBLOCK` when no data is ready. Runs the same `recv` symbol
      * as a plain synchronous downcall on every backend (Panama on JVM, `@extern` on Native, a direct koffi call on JS) so the byte count is
      * available immediately. Used only on a non-blocking fd; a blocking fd would freeze the event loop here.
      */
    def recvNow(fd: Int, buf: Buffer[Byte], len: Long, flags: Int)(using AllowUnsafe): Ffi.Outcome[Long]

    /** `int accept(int fd, struct sockaddr* addr, socklen_t* addrlen)`, bound NON-blocking: returns the accepted client fd directly rather
      * than a `Fiber.Unsafe`. Used only on a listen fd set `O_NONBLOCK` and armed on the poller, where `accept` returns a valid fd (>=0) or -1
      * with `EAGAIN`/`EWOULDBLOCK` when no connection is pending. Runs the same `accept` symbol as a plain synchronous downcall.
      */
    def acceptNow(fd: Int, addr: Buffer[Byte], addrlen: Buffer[Int])(using AllowUnsafe): Ffi.Outcome[Int]

    /** `ssize_t read(int fd, void* buf, size_t count)`. The fd-generic read used by the stdio `BlockingReaderDriver` fallback
      * where the read end may be a regular file rather than a socket. Returns bytes read, 0 on EOF, -1 with `errno`. Blocking-annotated: the
      * result is a `Fiber.Unsafe` the caller must await.
      */
    @Ffi.blocking
    def read(fd: Int, buf: Buffer[Byte], count: Long)(using AllowUnsafe): Fiber.Unsafe[Ffi.Outcome[Long], Any]

    /** `int close(int fd)`. Releases the fd. Blocking-annotated: the result is a `Fiber.Unsafe` the caller must await. */
    @Ffi.blocking
    def close(fd: Int)(using AllowUnsafe): Fiber.Unsafe[Int, Any]

end SocketBindings

private[net] object SocketBindings extends Ffi.Config(
        library = "c",
        // `sendNow` is the non-blocking synchronous overload of `send`: it binds the same libc `send` symbol (the derived `send_now` is not a
        // real symbol), differing only in that it omits `@Ffi.blocking` so the count comes back inline rather than via a fiber.
        symbols = Map(("sendNow", "send"), ("recvNow", "recv"), ("acceptNow", "accept")),
        headers = Chunk("sys/socket.h", "netinet/in.h", "sys/un.h", "unistd.h", "sys/stat.h")
    )
