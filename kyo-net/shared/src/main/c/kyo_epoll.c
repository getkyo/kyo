/*
 * kyo_epoll.c: real-symbol shim over the Linux epoll and eventfd syscalls.
 *
 * epoll and eventfd are Linux kernel features. Binding them by their libc names
 * (epoll_create1, epoll_ctl, epoll_wait, eventfd, eventfd_read, eventfd_write)
 * makes the Scala side reference symbols that exist only in a Linux libc, so a
 * Scala Native link on macOS or BSD fails with "symbol(s) not found" even though
 * the epoll backend is never selected there. The binding is emitted once, when
 * kyo is COMPILED, while the link happens on the consumer's host, so the two can
 * disagree about which platform is in play.
 *
 * Each kyo_epoll_* function below is a one-line wrapper exposing the syscall as a
 * symbol kyo itself owns and therefore always ships. The #if guard picks the real
 * syscall on Linux and a failing stub everywhere else, and because this file is
 * compiled on the machine that links the binary, the choice is made against the
 * TARGET platform rather than against whichever host published the artifact. The
 * link resolves on every platform and epoll's absence stays a runtime answer: the
 * stubs set errno to ENOSYS and return -1, so the backend probe reports the epoll
 * backend unavailable and selection falls through to kqueue.
 *
 * The Scala side gates on PosixConstants.isLinux before it calls any of these, so
 * the stubs are unreachable in practice; they exist to keep the link graph whole.
 *
 * Every entry point is defined on every target, Windows included, which is what
 * lets the binding resolve wherever the shared library is loaded. kyo_uring.c is
 * built the same way. An earlier revision wrapped this file in `#if !defined(_WIN32)`
 * and emitted nothing on Windows; the two shims then disagreed about a library they
 * share, and a Windows JVM run of EpollBindingsTest failed looking up a symbol that
 * had not been compiled. Only <unistd.h> and close(2) are POSIX-specific here, so
 * only those are guarded.
 */

#include <errno.h>

#if !defined(_WIN32)
#include <unistd.h>
#endif

#if defined(__linux__) && __has_include(<sys/epoll.h>)

#include <stdint.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>

/* epoll_create1: create an epoll set. Returns the epoll fd or -1 with errno. */
int kyo_epoll_create1(int flags) {
    return epoll_create1(flags);
}

/*
 * epoll_ctl: add, modify, or remove interest in fd. `event` is one struct
 * epoll_event laid out by the Scala side (arch-aware: packed on x86_64, naturally
 * aligned on aarch64) and passed through as the raw pointer the kernel expects.
 */
int kyo_epoll_ctl(int epfd, int op, int fd, void* event) {
    return epoll_ctl(epfd, op, fd, (struct epoll_event*)event);
}

/*
 * epoll_wait: block up to timeout ms and report ready events into the `events`
 * array. Returns the ready count, 0 on timeout, or -1 with errno.
 *
 * maxevents is signed here but the caller's array is sized from the same value,
 * so a negative would index out of bounds inside the kernel copy-out. Refuse it
 * at the C trust boundary (CWE-129) instead of letting it reach epoll_wait.
 */
int kyo_epoll_wait(int epfd, void* events, int maxevents, int timeout) {
    if (maxevents <= 0) {
        errno = EINVAL;
        return -1;
    }
    return epoll_wait(epfd, (struct epoll_event*)events, maxevents, timeout);
}

/* eventfd: create the poll-loop wakeup counter. Returns the fd or -1 with errno. */
int kyo_epoll_eventfd(int initval, int flags) {
    return eventfd((unsigned int)initval, flags);
}

/* eventfd_write: add `value` to the counter, waking a parked epoll_wait. */
int kyo_epoll_eventfd_write(int fd, long value) {
    return eventfd_write(fd, (eventfd_t)value);
}

/* eventfd_read: read and clear the counter into the caller's 8-byte buffer. */
int kyo_epoll_eventfd_read(int fd, void* value) {
    return eventfd_read(fd, (eventfd_t*)value);
}

#else

/*
 * Non-Linux: every entry point reports ENOSYS. The epoll backend's probe gates on
 * the OS before it reaches these, so a stub answering here means only that the
 * link graph stayed whole; nothing on this platform depends on the result.
 */

int kyo_epoll_create1(int flags) {
    (void)flags;
    errno = ENOSYS;
    return -1;
}

int kyo_epoll_ctl(int epfd, int op, int fd, void* event) {
    (void)epfd;
    (void)op;
    (void)fd;
    (void)event;
    errno = ENOSYS;
    return -1;
}

int kyo_epoll_wait(int epfd, void* events, int maxevents, int timeout) {
    (void)epfd;
    (void)events;
    (void)maxevents;
    (void)timeout;
    errno = ENOSYS;
    return -1;
}

int kyo_epoll_eventfd(int initval, int flags) {
    (void)initval;
    (void)flags;
    errno = ENOSYS;
    return -1;
}

int kyo_epoll_eventfd_write(int fd, long value) {
    (void)fd;
    (void)value;
    errno = ENOSYS;
    return -1;
}

int kyo_epoll_eventfd_read(int fd, void* value) {
    (void)fd;
    (void)value;
    errno = ENOSYS;
    return -1;
}

#endif

/*
 * close(2) on the epoll fd and the wake eventfd. It sits outside the Linux guard
 * because it is POSIX rather than Linux-specific: the binding reaches it through a
 * kyo-owned name for the same reason the rest of this file exists. On Windows there
 * is no epoll fd to close, so the call joins the other stubs and reports ENOSYS.
 */
#if !defined(_WIN32)

int kyo_epoll_close(int fd) {
    return close(fd);
}

#else

int kyo_epoll_close(int fd) {
    (void)fd;
    errno = ENOSYS;
    return -1;
}

#endif
