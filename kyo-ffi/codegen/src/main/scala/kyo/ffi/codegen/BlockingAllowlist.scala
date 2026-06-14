package kyo.ffi.codegen

/** Known-blocking POSIX/C symbols. When a method's resolved C symbol matches one of these but the method lacks `@Ffi.blocking`, the
  * generator emits a build warning.
  */
private[codegen] object BlockingAllowlist:
    val symbols: Set[String] = Set(
        "read",
        "write",
        "pread",
        "pwrite",
        "readv",
        "writev",
        "recv",
        "send",
        "recvfrom",
        "sendto",
        "recvmsg",
        "sendmsg",
        "connect",
        "accept",
        "accept4",
        "poll",
        "select",
        "epoll_wait",
        "kevent",
        "pthread_mutex_lock",
        "pthread_cond_wait",
        "pthread_join",
        "fopen",
        "fread",
        "fwrite",
        "fclose",
        "fflush",
        "sleep",
        "usleep",
        "nanosleep",
        "open",
        "close",
        "lseek",
        "getaddrinfo",
        "gethostbyname"
    )

    /** `true` iff the resolved C symbol appears in the blocking allowlist. */
    def contains(symbol: String): Boolean = symbols.contains(symbol)
end BlockingAllowlist
