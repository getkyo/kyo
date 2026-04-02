/**
 * Minimal TCP + I/O multiplexing wrappers for Scala Native.
 * Avoids struct layout issues by exposing simple int/pointer functions.
 *
 * Platform detection:
 *   - macOS/BSD: kqueue (sys/event.h)
 *   - Linux: epoll (sys/epoll.h)
 */
#include <sys/types.h>
#include <sys/socket.h>
#include <sys/resource.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <netdb.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <errno.h>
#include <stdio.h>
#include <signal.h>

/* Raise the open-file-descriptor soft limit to the hard limit at startup.
 * The default macOS soft limit is 256, which is too low for tests that open
 * many concurrent connections (each needs 3 fds: socket + readKq + writeKq).
 * The hard limit is typically 10240 on macOS and unlimited/65536 on Linux. */
__attribute__((constructor))
static void kyo_init_fd_limit(void) {
    struct rlimit rl;
    if (getrlimit(RLIMIT_NOFILE, &rl) == 0) {
        rlim_t target = rl.rlim_max;
        if (target == RLIM_INFINITY || target > 65536) target = 65536;
        if (target > rl.rlim_cur) {
            rl.rlim_cur = target;
            setrlimit(RLIMIT_NOFILE, &rl);
        }
    }
}

/* Suppress SIGPIPE: on macOS set SO_NOSIGPIPE per-socket; on Linux use MSG_NOSIGNAL per write. */
static void kyo_socket_nosigpipe(int fd) {
#ifdef SO_NOSIGPIPE
    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, &one, sizeof(one));
#endif
    (void)fd;
}

#ifdef __APPLE__
#include <sys/event.h>
#endif

#ifdef __linux__
#include <sys/epoll.h>
#endif

/* ── TCP connect ────────────────────────────────────────── */

/**
 * Non-blocking TCP connect.
 * Returns: fd (>=0) on success.
 * *out_pending = 1 if connection is async (EINPROGRESS), 0 if connected immediately.
 * Returns -1 on error.
 */
int kyo_tcp_connect(const char *host, int port, int *out_pending) {
    *out_pending = 0;
    struct addrinfo hints, *res;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_INET;
    hints.ai_socktype = SOCK_STREAM;

    char port_str[8];
    snprintf(port_str, sizeof(port_str), "%d", port);

    if (getaddrinfo(host, port_str, &hints, &res) != 0)
        return -1;

    int fd = socket(res->ai_family, res->ai_socktype, res->ai_protocol);
    if (fd < 0) {
        freeaddrinfo(res);
        return -1;
    }

    /* Set non-blocking */
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    /* Set TCP_NODELAY + suppress SIGPIPE */
    int one = 1;
    setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    kyo_socket_nosigpipe(fd);

    int rc = connect(fd, res->ai_addr, res->ai_addrlen);
    freeaddrinfo(res);

    if (rc == 0) return fd;           /* connected immediately */
    if (errno == EINPROGRESS) {
        *out_pending = 1;
        return fd;                    /* async — wait for writability */
    }
    close(fd);
    return -1;
}

/* ── TCP listen ─────────────────────────────────────────── */

/** Bind + listen. Returns server fd, or -1 on error. actualPort written to *out_port. */
int kyo_tcp_listen(const char *host, int port, int backlog, int *out_port) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    int one = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    /* Non-blocking */
    int flags = fcntl(fd, F_GETFL, 0);
    fcntl(fd, F_SETFL, flags | O_NONBLOCK);

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    addr.sin_addr.s_addr = INADDR_ANY;

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) != 0) {
        close(fd);
        return -1;
    }

    if (listen(fd, backlog) != 0) {
        close(fd);
        return -1;
    }

    /* Get actual port */
    struct sockaddr_in bound;
    socklen_t len = sizeof(bound);
    getsockname(fd, (struct sockaddr *)&bound, &len);
    *out_port = ntohs(bound.sin_port);

    return fd;
}

/** Accept a connection. Returns client fd, or -1. Sets non-blocking + TCP_NODELAY. */
int kyo_tcp_accept(int server_fd) {
    int client_fd = accept(server_fd, NULL, NULL);
    if (client_fd < 0) return -1;

    int flags = fcntl(client_fd, F_GETFL, 0);
    fcntl(client_fd, F_SETFL, flags | O_NONBLOCK);

    int one = 1;
    setsockopt(client_fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    kyo_socket_nosigpipe(client_fd);

    return client_fd;
}

/** Check if a non-blocking connect succeeded. Returns 0 on success, errno on failure. */
int kyo_tcp_connect_error(int fd) {
    int error = 0;
    socklen_t len = sizeof(error);
    getsockopt(fd, SOL_SOCKET, SO_ERROR, &error, &len);
    return error;
}

/* ── TCP read/write ─────────────────────────────────────── */

int kyo_tcp_read(int fd, char *buf, int len) {
    return (int)read(fd, buf, (size_t)len);
}

int kyo_tcp_write(int fd, const char *buf, int len) {
#ifdef MSG_NOSIGNAL
    return (int)send(fd, buf, (size_t)len, MSG_NOSIGNAL);
#else
    return (int)write(fd, buf, (size_t)len);
#endif
}

/* ── Socket lifecycle ───────────────────────────────────── */

void kyo_tcp_close(int fd) {
    close(fd);
}

void kyo_tcp_shutdown(int fd) {
    shutdown(fd, SHUT_RDWR);
    close(fd);
}

int kyo_tcp_is_alive(int fd) {
    return fcntl(fd, F_GETFD) != -1 ? 1 : 0;
}

/* ── kqueue (macOS/BSD) ────────────────────────────────── */

#ifdef __APPLE__

int kyo_kqueue_create(void) {
    return kqueue();
}

/** Register one-shot interest (EV_ADD | EV_ONESHOT). filter: -1=read, -2=write. */
int kyo_kqueue_register(int kq, int fd, int filter) {
    struct kevent ev;
    EV_SET(&ev, fd, filter, EV_ADD | EV_ONESHOT, 0, 0, NULL);
    return kevent(kq, &ev, 1, NULL, 0, NULL);
}

/** Non-blocking poll (zero timeout). */
int kyo_kqueue_wait_nonblock(int kq, int *out_fds, int *out_filters, int max_events) {
    struct kevent events[64];
    int actual_max = max_events < 64 ? max_events : 64;
    struct timespec timeout = { .tv_sec = 0, .tv_nsec = 0 };
    int n = kevent(kq, NULL, 0, events, actual_max, &timeout);
    for (int i = 0; i < n; i++) {
        out_fds[i] = (int)events[i].ident;
        out_filters[i] = events[i].filter;
    }
    return n < 0 ? 0 : n;
}

/** Wait for events indefinitely (infinite timeout). Returns number of ready events. */
int kyo_kqueue_wait(int kq, int *out_fds, int *out_filters, int max_events) {
    struct kevent events[64];
    int actual_max = max_events < 64 ? max_events : 64;
    int n = kevent(kq, NULL, 0, events, actual_max, NULL); /* infinite wait */
    for (int i = 0; i < n; i++) {
        out_fds[i] = (int)events[i].ident;
        out_filters[i] = events[i].filter;
    }
    return n < 0 ? 0 : n;
}

#endif /* __APPLE__ */

/* ── epoll (Linux) ─────────────────────────────────────── */

#ifdef __linux__

int kyo_epoll_create(void) {
    return epoll_create1(0);
}

/**
 * Register interest on an epoll fd.
 * mode: 1=read (EPOLLIN), 2=write (EPOLLOUT), 3=read+write
 * Uses EPOLLONESHOT so each event fires at most once.
 * If the fd is already registered, re-arms it with EPOLL_CTL_MOD.
 */
int kyo_epoll_register(int epfd, int fd, int mode) {
    struct epoll_event ev;
    ev.data.fd = fd;
    ev.events = EPOLLONESHOT;
    if (mode & 1) ev.events |= EPOLLIN;
    if (mode & 2) ev.events |= EPOLLOUT;
    int rc = epoll_ctl(epfd, EPOLL_CTL_ADD, fd, &ev);
    if (rc < 0 && errno == EEXIST) {
        rc = epoll_ctl(epfd, EPOLL_CTL_MOD, fd, &ev);
    }
    return rc;
}

/** Remove fd from epoll. */
int kyo_epoll_deregister(int epfd, int fd) {
    return epoll_ctl(epfd, EPOLL_CTL_DEL, fd, NULL);
}

/** Non-blocking poll (zero timeout). Returns number of ready fds. */
int kyo_epoll_wait_nonblock(int epfd, int *out_fds, int *out_events, int max_events) {
    struct epoll_event events[64];
    int actual_max = max_events < 64 ? max_events : 64;
    int n = epoll_wait(epfd, events, actual_max, 0);
    for (int i = 0; i < n; i++) {
        out_fds[i] = events[i].data.fd;
        out_events[i] = (int)events[i].events;
    }
    return n < 0 ? 0 : n;
}

/** Wait for events indefinitely (infinite timeout). Returns number of ready events. */
int kyo_epoll_wait_timeout(int epfd, int *out_fds, int *out_events, int max_events) {
    struct epoll_event events[64];
    int actual_max = max_events < 64 ? max_events : 64;
    int n = epoll_wait(epfd, events, actual_max, -1); /* infinite wait */
    for (int i = 0; i < n; i++) {
        out_fds[i] = events[i].data.fd;
        out_events[i] = (int)events[i].events;
    }
    return n < 0 ? 0 : n;
}

#endif /* __linux__ */

/* ── Stubs for cross-compilation ──────────────────────── */

#ifndef __linux__
/* Epoll stubs for macOS — never called at runtime (auto-detection picks kqueue). */
int kyo_epoll_create(void) { return -1; }
int kyo_epoll_register(int epfd, int fd, int mode) { return -1; }
int kyo_epoll_deregister(int epfd, int fd) { return -1; }
int kyo_epoll_wait_nonblock(int epfd, int *out_fds, int *out_events, int max_events) { return 0; }
int kyo_epoll_wait_timeout(int epfd, int *out_fds, int *out_events, int max_events) { return 0; }
#endif

#ifndef __APPLE__
/* Kqueue stubs for Linux — never called at runtime (auto-detection picks epoll). */
int kyo_kqueue_create(void) { return -1; }
int kyo_kqueue_register(int kq, int fd, int filter) { return -1; }
int kyo_kqueue_wait_nonblock(int kq, int *out_fds, int *out_filters, int max_events) { return 0; }
int kyo_kqueue_wait(int kq, int *out_fds, int *out_filters, int max_events) { return 0; }
#endif
