#define _GNU_SOURCE
#include "kyo_process_pipe.h"
#include <errno.h>
#include <stdlib.h>

#ifdef _WIN32

int kyo_process_pipe_pair(int descriptors[2]) { (void)descriptors; return -ENOSYS; }

int kyo_process_pipe_available_descriptor(int descriptor) { (void)descriptor; return -ENOSYS; }

struct kyo_process_pipe *kyo_process_pipe_init(int descriptor, int *error) {
    (void)descriptor;
    *error = ENOSYS;
    errno = ENOSYS;
    return NULL;
}

int kyo_process_pipe_read(struct kyo_process_pipe *pipe, unsigned char *bytes, int capacity) {
    (void)pipe;
    (void)bytes;
    (void)capacity;
    return -ENOSYS;
}

int kyo_process_pipe_cancel(struct kyo_process_pipe *pipe) {
    (void)pipe;
    return -ENOSYS;
}

int kyo_process_pipe_write(struct kyo_process_pipe *pipe, const unsigned char *bytes, int offset, int length) {
    (void)pipe;
    (void)bytes;
    (void)offset;
    (void)length;
    return -ENOSYS;
}

int kyo_process_pipe_stop(struct kyo_process_pipe *pipe) { (void)pipe; return -ENOSYS; }

int kyo_process_pipe_release(struct kyo_process_pipe *pipe) {
    (void)pipe;
    return -ENOSYS;
}

#else

#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <sys/ioctl.h>
#include <unistd.h>

int kyo_process_pipe_pair(int descriptors[2]) {
#ifdef __linux__
    return pipe2(descriptors, O_CLOEXEC) ? -errno : 0;
#else
    if (pipe(descriptors)) return -errno;
    if (fcntl(descriptors[0], F_SETFD, FD_CLOEXEC) || fcntl(descriptors[1], F_SETFD, FD_CLOEXEC)) {
        int error = errno;
        close(descriptors[0]);
        close(descriptors[1]);
        return -error;
    }
    return 0;
#endif
}

int kyo_process_pipe_available_descriptor(int descriptor) {
    int remaining = 0;
    int result;
    do { result = ioctl(descriptor, FIONREAD, &remaining); } while (result && errno == EINTR);
    return result ? -errno : remaining;
}

struct kyo_process_pipe {
    pthread_mutex_t mutex;
    int descriptor;
    int wake[2];
    int cancelled;
    int remaining;
};

static struct kyo_process_pipe *init_pipe(int descriptor) {
    int flags = fcntl(descriptor, F_GETFL);
    if (flags < 0) return NULL;
    struct kyo_process_pipe *owner = calloc(1, sizeof(*owner));
    if (!owner) return NULL;
    int error = pthread_mutex_init(&owner->mutex, NULL);
    if (error) { free(owner); errno = error; return NULL; }
#ifdef __linux__
    if (pipe2(owner->wake, O_NONBLOCK | O_CLOEXEC)) {
#else
    if (pipe(owner->wake)) {
#endif
        error = errno;
        pthread_mutex_destroy(&owner->mutex);
        free(owner);
        errno = error;
        return NULL;
    }
#ifndef __linux__
    for (int index = 0; index < 2; index++) {
        if (fcntl(owner->wake[index], F_SETFD, FD_CLOEXEC) ||
            fcntl(owner->wake[index], F_SETFL, O_NONBLOCK)) {
            error = errno;
            break;
        }
    }
#endif
    if (!error && fcntl(descriptor, F_SETFL, flags | O_NONBLOCK)) error = errno;
    if (error) {
        close(owner->wake[0]);
        close(owner->wake[1]);
        pthread_mutex_destroy(&owner->mutex);
        free(owner);
        errno = error;
        return NULL;
    }
    owner->descriptor = descriptor;
    return owner;
}

struct kyo_process_pipe *kyo_process_pipe_init(int descriptor, int *error) {
    struct kyo_process_pipe *owner = init_pipe(descriptor);
    *error = owner ? 0 : errno;
    return owner;
}

int kyo_process_pipe_read(struct kyo_process_pipe *owner, unsigned char *bytes, int capacity) {
    if (capacity < 0) return -EINVAL;
    if (!capacity) return 0;
    for (;;) {
        int error = pthread_mutex_lock(&owner->mutex);
        if (error) return -error;
        if (owner->cancelled && !owner->remaining) {
            pthread_mutex_unlock(&owner->mutex);
            return 0;
        }
        int length = capacity;
        if (owner->cancelled && length > owner->remaining) length = owner->remaining;
        /* Reads are nonblocking under the mutex. The wait below never holds it against cancellation. */
        ssize_t count;
        do { count = read(owner->descriptor, bytes, (size_t)length); } while (count < 0 && errno == EINTR);
        error = count < 0 ? errno : 0;
        if (count >= 0) {
            if (owner->cancelled) owner->remaining -= (int)count;
            if (!count) { owner->cancelled = 1; owner->remaining = 0; }
            pthread_mutex_unlock(&owner->mutex);
            return (int)count;
        }
        if (owner->cancelled && (error == EAGAIN || error == EWOULDBLOCK)) {
            /* Only this owner reads the descriptor, so the cancellation snapshot cannot disappear. */
            pthread_mutex_unlock(&owner->mutex);
            return -EIO;
        }
        pthread_mutex_unlock(&owner->mutex);
        if (error != EAGAIN && error != EWOULDBLOCK) return -error;
        struct pollfd descriptors[] = {
            {owner->descriptor, POLLIN, 0},
            {owner->wake[0], POLLIN, 0}
        };
        int ready;
        do { ready = poll(descriptors, 2, -1); } while (ready < 0 && errno == EINTR);
        if (ready < 0) return -errno;
        if ((descriptors[0].revents | descriptors[1].revents) & POLLNVAL) return -EBADF;
    }
}

/* Block SIGPIPE only on this carrier and consume only a signal newly generated by this write.
   A process-wide SIG_IGN would change the host's signal policy and the children it launches. */
static ssize_t write_without_sigpipe(int descriptor, const void *bytes, size_t length, int *error) {
    sigset_t blocked, previous, pending;
    sigemptyset(&blocked);
    sigaddset(&blocked, SIGPIPE);
    *error = pthread_sigmask(SIG_BLOCK, &blocked, &previous);
    if (*error) return -1;
    if (sigpending(&pending)) {
        *error = errno;
        (void)pthread_sigmask(SIG_SETMASK, &previous, NULL);
        return -1;
    }
    int existed = sigismember(&pending, SIGPIPE);
    ssize_t count;
    do { count = write(descriptor, bytes, length); } while (count < 0 && errno == EINTR);
    *error = count < 0 ? errno : 0;
    if (*error == EPIPE && !existed && !sigpending(&pending) && sigismember(&pending, SIGPIPE)) {
        int number;
        int result = sigwait(&blocked, &number);
        if (result) *error = result;
    }
    int result = pthread_sigmask(SIG_SETMASK, &previous, NULL);
    if (result && !*error) *error = result;
    return count;
}

int kyo_process_pipe_write(struct kyo_process_pipe *owner, const unsigned char *bytes, int offset, int length) {
    if (offset < 0 || length < 0) return -EINVAL;
    if (!length) return 0;
    for (;;) {
        int error = pthread_mutex_lock(&owner->mutex);
        if (error) return -error;
        if (owner->cancelled) {
            pthread_mutex_unlock(&owner->mutex);
            return -ECANCELED;
        }
        ssize_t count = write_without_sigpipe(owner->descriptor, bytes + offset, (size_t)length, &error);
        pthread_mutex_unlock(&owner->mutex);
        if (count >= 0) return error ? -error : (int)count;
        if (error != EAGAIN && error != EWOULDBLOCK) return -error;
        struct pollfd descriptors[] = {{owner->descriptor, POLLOUT, 0}, {owner->wake[0], POLLIN, 0}};
        int ready;
        do { ready = poll(descriptors, 2, -1); } while (ready < 0 && errno == EINTR);
        if (ready < 0) return -errno;
        if ((descriptors[0].revents | descriptors[1].revents) & POLLNVAL) return -EBADF;
    }
}

static int cancel_pipe(struct kyo_process_pipe *owner, int drain) {
    /* A cancellation must run even when all foreign-call workers are parked in read. */
    int error = pthread_mutex_trylock(&owner->mutex);
    if (error == EBUSY) return -EAGAIN;
    if (error) return -error;
    if (!owner->cancelled) {
        int remaining = 0;
        int result;
        do { result = drain ? ioctl(owner->descriptor, FIONREAD, &remaining) : 0; } while (result && errno == EINTR);
        if (result) error = errno;
        owner->cancelled = 1;
        owner->remaining = error ? 0 : remaining;
        /* Keep the wake byte readable for every waiting reader, including one about to enter poll. */
        unsigned char byte = 0;
        ssize_t count;
        do { count = write(owner->wake[1], &byte, 1); } while (count < 0 && errno == EINTR);
        if (count < 0 && errno != EAGAIN && errno != EWOULDBLOCK && !error) error = errno;
    } else if (!drain) owner->remaining = 0;
    pthread_mutex_unlock(&owner->mutex);
    return -error;
}

int kyo_process_pipe_cancel(struct kyo_process_pipe *owner) { return cancel_pipe(owner, 1); }
int kyo_process_pipe_stop(struct kyo_process_pipe *owner) { return cancel_pipe(owner, 0); }

int kyo_process_pipe_release(struct kyo_process_pipe *owner) {
    int error = 0;
    if (close(owner->descriptor)) error = errno;
    if (close(owner->wake[0]) && !error) error = errno;
    if (close(owner->wake[1]) && !error) error = errno;
    int result = pthread_mutex_destroy(&owner->mutex);
    if (result && !error) error = result;
    free(owner);
    return -error;
}

#endif
