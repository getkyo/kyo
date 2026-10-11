#ifndef _WIN32

// Scala Native compiles C as strict ISO C, under which glibc hides sigaction and SA_RESTART.
#define _POSIX_C_SOURCE 200809L

#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <string.h>
#include <unistd.h>

// Only async-signal-safe calls may run in the handler: write(2) and errno, nothing else. The write end is
// non-blocking, so a full pipe drops the byte rather than wedging the interrupted thread; the kernel coalesces
// a pending signal of the same number the same way.
static volatile sig_atomic_t kyo_signal_write_fd = -1;

static void kyo_signal_handler(int sig) {
    int saved = errno;
    unsigned char b = (unsigned char)sig;
    ssize_t r = write(kyo_signal_write_fd, &b, 1);
    (void)r;
    errno = saved;
}

static int kyo_signal_cloexec(int fd) {
    int flags = fcntl(fd, F_GETFD);
    return flags < 0 ? -1 : fcntl(fd, F_SETFD, flags | FD_CLOEXEC);
}

int kyo_signal_open(void) {
    int fds[2];
    if (pipe(fds) != 0) {
        return -1;
    }
    int flags = fcntl(fds[1], F_GETFL);
    if (flags < 0 || fcntl(fds[1], F_SETFL, flags | O_NONBLOCK) != 0 || kyo_signal_cloexec(fds[0]) != 0 ||
        kyo_signal_cloexec(fds[1]) != 0) {
        close(fds[0]);
        close(fds[1]);
        return -1;
    }
    kyo_signal_write_fd = fds[1];
    return fds[0];
}

int kyo_signal_install(int sig) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = kyo_signal_handler;
    sigemptyset(&action.sa_mask);
    action.sa_flags = SA_RESTART;
    return sigaction(sig, &action, NULL);
}

int kyo_signal_reset(int sig) {
    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_handler = SIG_DFL;
    sigemptyset(&action.sa_mask);
    return sigaction(sig, &action, NULL);
}

int kyo_signal_wait(int fd) {
    unsigned char b;
    for (;;) {
        ssize_t r = read(fd, &b, 1);
        if (r == 1) {
            return b;
        }
        if (r < 0 && errno == EINTR) {
            continue;
        }
        return -1;
    }
}

#else

int kyo_signal_open(void) { return -1; }

int kyo_signal_install(int sig) {
    (void)sig;
    return -1;
}

int kyo_signal_reset(int sig) {
    (void)sig;
    return -1;
}

int kyo_signal_wait(int fd) {
    (void)fd;
    return -1;
}

#endif
