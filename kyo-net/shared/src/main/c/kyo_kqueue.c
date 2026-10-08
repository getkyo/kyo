/*
 * kyo_kqueue.c: real-symbol shim over the macOS/BSD kqueue and kevent syscalls.
 *
 * Bound by their libc names, kqueue and kevent are symbols only a macOS or BSD
 * libc has, and the codegen decides between an @extern binding and a throwing
 * stub by probing <sys/event.h> on the host that publishes kyo, while Scala
 * Native links the binding on the consumer's host. A macOS publish then leaves a
 * Linux consumer's link with undefined kqueue and kevent, and a Linux publish
 * leaves a macOS consumer with no kqueue backend at all.
 *
 * The kyo_kqueue_* functions are symbols kyo owns and always ships. This file is
 * compiled where the binary links, so the #if picks the real syscall on the
 * target's kernel and an ENOSYS stub everywhere else, and kqueue's absence stays
 * a runtime answer the backend probe reports. It follows kyo_epoll.c.
 */
#include "kyo_net_api.h"

#include <errno.h>

#if !defined(_WIN32)
#include <unistd.h>
#endif

/* libkqueue can put a <sys/event.h> on Linux; its kevent needs -lkqueue, which nothing links. */
#if (defined(__APPLE__) || defined(__FreeBSD__) || defined(__NetBSD__) || defined(__OpenBSD__) || defined(__DragonFly__)) && \
    __has_include(<sys/event.h>)

#include <sys/event.h>
#include <sys/time.h>
#include <sys/types.h>

/* kqueue: create a kernel event queue. Returns the kqueue fd or -1 with errno. */
KYO_NET_API int kyo_kqueue(void) {
    return kqueue();
}

/*
 * kevent: apply `changelist` and collect up to `nevents` events into `eventlist`,
 * waiting at most `timeout` (NULL waits forever). Both lists are struct kevent
 * arrays laid out by the Scala side and passed through as raw pointers.
 */
KYO_NET_API int kyo_kevent(int kq, const void* changelist, int nchanges, void* eventlist, int nevents, const void* timeout) {
    return kevent(kq, (const struct kevent*)changelist, nchanges, (struct kevent*)eventlist, nevents,
                  (const struct timespec*)timeout);
}

#else

KYO_NET_API int kyo_kqueue(void) {
    errno = ENOSYS;
    return -1;
}

KYO_NET_API int kyo_kevent(int kq, const void* changelist, int nchanges, void* eventlist, int nevents, const void* timeout) {
    (void)kq;
    (void)changelist;
    (void)nchanges;
    (void)eventlist;
    (void)nevents;
    (void)timeout;
    errno = ENOSYS;
    return -1;
}

#endif

/* close(2) on the kqueue fd; POSIX rather than kqueue-specific, so only Windows stubs it. */
#if !defined(_WIN32)

KYO_NET_API int kyo_kqueue_close(int fd) {
    return close(fd);
}

#else

KYO_NET_API int kyo_kqueue_close(int fd) {
    (void)fd;
    errno = ENOSYS;
    return -1;
}

#endif
