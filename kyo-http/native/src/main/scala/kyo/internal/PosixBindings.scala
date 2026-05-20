package kyo.internal

import scala.scalanative.unsafe.*

/** Scala Native FFI bindings to `kyo_tcp.c` and the platform poller helpers (`kyo_epoll.c` / `kyo_kqueue.c`).
  *
  * All socket functions are thin non-blocking wrappers around the POSIX socket API. They avoid Scala Native struct layout concerns by
  * passing all parameters and results as primitive integers or pointers. Return value conventions:
  *   - fd functions: return ≥0 on success, -1 on error
  *   - read (`kyo_tcp_read`): returns bytes read (>0), 0 on EOF, -1 on error/EAGAIN
  *   - write (`kyo_tcp_write`): returns bytes written (>0), 0 on EAGAIN, -1 on error
  *
  * Epoll / kqueue registration functions return the underlying syscall return code (<0 on failure). Poll functions are marked `@blocking`
  * so the Kyo scheduler can park the OS thread while the kernel waits for events.
  */
private[kyo] object PosixBindings:

    /** Non-blocking TCP connect. Returns fd (>=0) on success, -1 on error. *outPending=1 if async. */
    @extern @name("kyo_tcp_connect")
    def tcpConnect(host: CString, port: CInt, outPending: Ptr[CInt]): CInt = extern

    /** Bind + listen. Returns server fd, writes actual port to out_port. */
    @extern @name("kyo_tcp_listen")
    def tcpListen(host: CString, port: CInt, backlog: CInt, outPort: Ptr[CInt]): CInt = extern

    /** Accept. Returns client fd (non-blocking, TCP_NODELAY set). */
    @extern @name("kyo_tcp_accept")
    def tcpAccept(serverFd: CInt): CInt = extern

    /** Check if non-blocking connect succeeded. Returns 0 on success, errno on failure. */
    @extern @name("kyo_tcp_connect_error")
    def tcpConnectError(fd: CInt): CInt = extern

    /** Non-blocking Unix domain socket connect. Returns fd (>=0), -1 on error. *outPending=1 if async. */
    @extern @name("kyo_unix_connect")
    def unixConnect(path: CString, outPending: Ptr[CInt]): CInt = extern

    /** Bind + listen on a Unix domain socket. Returns server fd, -1 on error. *outPort always 0. */
    @extern @name("kyo_unix_listen")
    def unixListen(path: CString, backlog: CInt, outPort: Ptr[CInt]): CInt = extern

    /** Read up to len bytes. Returns bytes read, 0 on EOF, -1 on error/EAGAIN. */
    @extern @name("kyo_tcp_read")
    def tcpRead(fd: CInt, buf: Ptr[Byte], len: CInt): CInt = extern

    /** Write len bytes. Returns bytes written, -1 on error. */
    @extern @name("kyo_tcp_write")
    def tcpWrite(fd: CInt, buf: Ptr[Byte], len: CInt): CInt = extern

    @extern @name("kyo_tcp_close")
    def tcpClose(fd: CInt): Unit = extern

    @extern @name("kyo_tcp_shutdown")
    def tcpShutdown(fd: CInt): Unit = extern

    @extern @name("kyo_tcp_is_alive")
    def tcpIsAlive(fd: CInt): CInt = extern

    @extern @name("kyo_kqueue_create")
    def kqueueCreate(): CInt = extern

    /** Register one-shot interest. filter: -1=read, -2=write. */
    @extern @name("kyo_kqueue_register")
    def kqueueRegister(kq: CInt, fd: CInt, filter: CInt): CInt = extern

    /** Wait for events with 100ms timeout. Blocks until events or timeout. */
    @extern @blocking @name("kyo_kqueue_wait")
    def kqueueWait(kq: CInt, outFds: Ptr[CInt], outFilters: Ptr[CInt], maxEvents: CInt): CInt = extern

    /** Non-blocking poll (zero timeout). */
    @extern @name("kyo_kqueue_wait_nonblock")
    def kqueueWaitNonBlock(kq: CInt, outFds: Ptr[CInt], outFilters: Ptr[CInt], maxEvents: CInt): CInt = extern

    // ── epoll (Linux) ───────────────────────────────────────

    @extern @name("kyo_epoll_create")
    def epollCreate(): CInt = extern

    /** Register interest. mode: 1=read, 2=write, 3=read+write. Uses EPOLLONESHOT. */
    @extern @name("kyo_epoll_register")
    def epollRegister(epfd: CInt, fd: CInt, mode: CInt): CInt = extern

    /** Remove fd from epoll. */
    @extern @name("kyo_epoll_deregister")
    def epollDeregister(epfd: CInt, fd: CInt): CInt = extern

    /** Non-blocking poll (zero timeout). Returns number of ready fds. */
    @extern @name("kyo_epoll_wait_nonblock")
    def epollWaitNonBlock(epfd: CInt, outFds: Ptr[CInt], outEvents: Ptr[CInt], maxEvents: CInt): CInt = extern

    /** Wait for events with 100ms timeout. */
    @extern @blocking @name("kyo_epoll_wait_timeout")
    def epollWaitTimeout(epfd: CInt, outFds: Ptr[CInt], outEvents: Ptr[CInt], maxEvents: CInt): CInt = extern

end PosixBindings
