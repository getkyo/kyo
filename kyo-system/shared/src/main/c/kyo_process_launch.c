#define _GNU_SOURCE
#include "kyo_process_launch.h"
#include "kyo_process_pipe.h"
#include <errno.h>
#include <stdlib.h>

#ifdef _WIN32

struct kyo_process_launch *kyo_process_launch_init(const char *temporary_directory, int *error) {
    (void)temporary_directory;
    *error = ENOSYS;
    return NULL;
}
const char *kyo_process_launch_executable(struct kyo_process_launch *launch) { (void)launch; return ""; }
const char *kyo_process_launch_endpoint(struct kyo_process_launch *launch) { (void)launch; return ""; }
int kyo_process_launch_take_listener(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }
int kyo_process_launch_accept(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }
int kyo_process_launch_await(struct kyo_process_launch *launch, int streams_mask) { (void)launch; (void)streams_mask; return -ENOSYS; }
int kyo_process_launch_cancel(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }
int kyo_process_launch_helper_exited(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }
int kyo_process_launch_disconnect(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }
int kyo_process_launch_take_control(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }
int kyo_process_launch_take_stream(struct kyo_process_launch *launch, int stream) { (void)launch; (void)stream; return -ENOSYS; }
struct kyo_process_pipe *kyo_process_launch_take_pipe(struct kyo_process_launch *launch, int slot, int *error) {
    (void)launch; (void)slot; *error = ENOSYS; return NULL;
}
int kyo_process_launch_release(struct kyo_process_launch *launch) { (void)launch; return -ENOSYS; }

#else

#include "kyo_process_supervisor_image.h"
#include "kyo_process_protocol.h"
#include <fcntl.h>
#include <poll.h>
#include <stdatomic.h>
#include <stdio.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

struct kyo_process_launch {
    int listener;
    int control;
    int wake[2];
    int streams[3];
    int acquired;
    atomic_int interruption;
    char control_directory[sizeof("/tmp/kyo-process-XXXXXX")];
    char endpoint[sizeof(((struct sockaddr_un *)0)->sun_path)];
    char *image_directory;
    char *executable;
};

static int private_descriptor(int descriptor) {
    if (descriptor < 0) return -errno;
    if (descriptor < 3) {
        int duplicate = fcntl(descriptor, F_DUPFD_CLOEXEC, 3);
        int error = errno;
        close(descriptor);
        if (duplicate < 0) return -error;
        descriptor = duplicate;
    }
    int flags = fcntl(descriptor, F_GETFL);
    if (flags < 0 || fcntl(descriptor, F_SETFL, flags | O_NONBLOCK) || fcntl(descriptor, F_SETFD, FD_CLOEXEC)) {
        int error = errno;
        close(descriptor);
        return -error;
    }
    return descriptor;
}

int kyo_process_launch_release(struct kyo_process_launch *launch) {
    int error = 0;
    if (launch->listener >= 0 && close(launch->listener)) error = errno;
    if (launch->control >= 0 && close(launch->control) && !error) error = errno;
    for (int index = 0; index < 3; index++)
        if (launch->streams[index] >= 0 && close(launch->streams[index]) && !error) error = errno;
    for (int index = 0; index < 2; index++)
        if (launch->wake[index] >= 0 && close(launch->wake[index]) && !error) error = errno;
    if (launch->endpoint[0] && unlink(launch->endpoint) && errno != ENOENT && !error) error = errno;
    if (launch->control_directory[0] && rmdir(launch->control_directory) && !error) error = errno;
    if (launch->executable && unlink(launch->executable) && errno != ENOENT && !error) error = errno;
    if (launch->image_directory && rmdir(launch->image_directory) && !error) error = errno;
    free(launch->executable);
    free(launch->image_directory);
    free(launch);
    return -error;
}

struct kyo_process_launch *kyo_process_launch_init(const char *temporary_directory, int *error) {
    *error = 0;
    struct kyo_process_launch *launch = calloc(1, sizeof(*launch));
    if (!launch) { *error = ENOMEM; return NULL; }
    launch->listener = -1;
    launch->control = -1;
    launch->wake[0] = launch->wake[1] = -1;
    atomic_init(&launch->interruption, 0);
    for (int index = 0; index < 3; index++) launch->streams[index] = -1;
    char control_directory[] = "/tmp/kyo-process-XXXXXX";
    if (!mkdtemp(control_directory)) { *error = errno; goto failed; }
    memcpy(launch->control_directory, control_directory, sizeof(control_directory));
    int count = snprintf(launch->endpoint, sizeof(launch->endpoint), "%s/control", control_directory);
    if (count < 0 || (size_t)count >= sizeof(launch->endpoint)) { *error = ENAMETOOLONG; goto failed; }
#ifdef __linux__
    launch->listener = socket(AF_UNIX, SOCK_STREAM | SOCK_CLOEXEC | SOCK_NONBLOCK, 0);
#else
    launch->listener = socket(AF_UNIX, SOCK_STREAM, 0);
#endif
    launch->listener = private_descriptor(launch->listener);
    if (launch->listener < 0) { *error = -launch->listener; goto failed; }
    struct sockaddr_un address = {0};
    address.sun_family = AF_UNIX;
    memcpy(address.sun_path, launch->endpoint, strlen(launch->endpoint) + 1);
    if (bind(launch->listener, (struct sockaddr *)&address, sizeof(address)) || listen(launch->listener, 1)) {
        *error = errno;
        goto failed;
    }
    if (pipe(launch->wake)) { *error = errno; goto failed; }
    for (int index = 0; index < 2; index++) {
        launch->wake[index] = private_descriptor(launch->wake[index]);
        if (launch->wake[index] < 0) { *error = -launch->wake[index]; goto failed; }
    }
    /* The short control path is independent of TMPDIR's length. The executable honors TMPDIR so
       an application can choose an executable filesystem when /tmp is mounted noexec. */
    const char *root = temporary_directory[0] ? temporary_directory : "/tmp";
    char *image_directory = NULL;
    if (asprintf(&image_directory, "%s/kyo-process-XXXXXX", root) < 0) { *error = ENOMEM; goto failed; }
    if (!mkdtemp(image_directory)) { *error = errno; free(image_directory); goto failed; }
    launch->image_directory = image_directory;
    if (asprintf(&launch->executable, "%s/supervisor", image_directory) < 0) {
        launch->executable = NULL;
        *error = ENOMEM;
        goto failed;
    }
    int file = open(launch->executable, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
    if (file < 0) { *error = errno; goto failed; }
    size_t offset = 0;
    while (offset < sizeof(kyo_process_supervisor_image)) {
        ssize_t bytes = write(file, kyo_process_supervisor_image + offset, sizeof(kyo_process_supervisor_image) - offset);
        if (bytes < 0 && errno == EINTR) continue;
        if (bytes <= 0) { *error = bytes ? errno : EIO; break; }
        offset += (size_t)bytes;
    }
    if (!*error && fchmod(file, 0500)) *error = errno;
    if (close(file) && !*error) *error = errno;
    if (*error) goto failed;
    return launch;
failed:
    (void)kyo_process_launch_release(launch);
    return NULL;
}

const char *kyo_process_launch_executable(struct kyo_process_launch *launch) { return launch->executable; }
const char *kyo_process_launch_endpoint(struct kyo_process_launch *launch) { return launch->endpoint; }

int kyo_process_launch_take_listener(struct kyo_process_launch *launch) {
    int descriptor = launch->listener;
    if (descriptor < 0) return -EBADF;
    launch->listener = -1;
    return descriptor;
}

int kyo_process_launch_accept(struct kyo_process_launch *launch) {
    int descriptor;
#ifdef __linux__
    descriptor = accept4(launch->listener, NULL, NULL, SOCK_CLOEXEC | SOCK_NONBLOCK);
#else
    descriptor = accept(launch->listener, NULL, NULL);
#endif
    return private_descriptor(descriptor);
}

static int wake_acquisition(struct kyo_process_launch *launch) {
    unsigned char byte = 0;
    ssize_t count;
    do { count = write(launch->wake[1], &byte, 1); } while (count < 0 && errno == EINTR);
    /* A full pipe is already readable. Leave it readable for acquisition's next poll. */
    return count >= 0 || errno == EAGAIN || errno == EWOULDBLOCK ? 0 : -errno;
}

int kyo_process_launch_cancel(struct kyo_process_launch *launch) {
    atomic_store(&launch->interruption, 1);
    return wake_acquisition(launch);
}

int kyo_process_launch_helper_exited(struct kyo_process_launch *launch) {
    int running = 0;
    atomic_compare_exchange_strong(&launch->interruption, &running, 2);
    return wake_acquisition(launch);
}

int kyo_process_launch_disconnect(struct kyo_process_launch *launch) {
    int error = 0;
    if (launch->listener >= 0) {
        if (close(launch->listener)) error = errno;
        launch->listener = -1;
    }
    if (launch->control >= 0) {
        if (close(launch->control) && !error) error = errno;
        launch->control = -1;
    }
    return -error;
}

static int wait_input(struct kyo_process_launch *launch, int fd) {
    struct pollfd descriptors[] = {{fd, POLLIN, 0}, {launch->wake[0], POLLIN, 0}};
    int count;
    do { count = poll(descriptors, 2, -1); } while (count < 0 && errno == EINTR);
    if (count < 0) return -errno;
    int interruption = atomic_load(&launch->interruption);
    if (interruption == 1) return -ECANCELED;
    if (descriptors[0].revents & POLLNVAL) return -EBADF;
    if (!descriptors[0].revents && interruption == 2) return -ECHILD;
    return 0;
}

int kyo_process_launch_await(struct kyo_process_launch *launch, int streams_mask) {
    if (launch->acquired || launch->control >= 0) return -EALREADY;
    if (launch->listener < 0) return -EBADF;
    if (streams_mask < 0 || streams_mask > 15 || ((streams_mask & 8) && (streams_mask & 4))) return -EINVAL;
    int error;
    for (;;) {
        error = wait_input(launch, launch->listener);
        if (error) return error;
        int descriptor = kyo_process_launch_accept(launch);
        if (descriptor == -EAGAIN || descriptor == -EWOULDBLOCK || descriptor == -EINTR) continue;
        if (descriptor < 0) return descriptor;
        launch->control = descriptor;
        break;
    }
    struct kyo_process_event event;
    size_t offset = 0;
    int stream_index = 0;
    while (offset < sizeof(event)) {
        error = wait_input(launch, launch->control);
        if (error) return error;
        struct iovec data = {(unsigned char *)&event + offset, sizeof(event) - offset};
        union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(3 * sizeof(int))]; } ancillary = {0};
        struct msghdr message = {0};
        message.msg_iov = &data;
        message.msg_iovlen = 1;
        message.msg_control = ancillary.bytes;
        message.msg_controllen = sizeof(ancillary.bytes);
#ifdef __linux__
        ssize_t count = recvmsg(launch->control, &message, MSG_CMSG_CLOEXEC);
#else
        ssize_t count = recvmsg(launch->control, &message, 0);
#endif
        if (count < 0 && (errno == EINTR || errno == EAGAIN || errno == EWOULDBLOCK)) continue;
        if (count <= 0) return count ? -errno : -ECONNRESET;
        error = message.msg_flags & MSG_CTRUNC ? EPROTO : 0;
        for (struct cmsghdr *header = CMSG_FIRSTHDR(&message); header; header = CMSG_NXTHDR(&message, header)) {
            if (header->cmsg_level != SOL_SOCKET || header->cmsg_type != SCM_RIGHTS) { error = EPROTO; continue; }
            size_t length = header->cmsg_len - CMSG_LEN(0);
            if (length % sizeof(int)) error = EPROTO;
            for (size_t index = 0; index + sizeof(int) <= length; index += sizeof(int)) {
                int descriptor;
                memcpy(&descriptor, (unsigned char *)CMSG_DATA(header) + index, sizeof(descriptor));
                while (stream_index < 3 && !(streams_mask & (1 << stream_index))) stream_index++;
                if (stream_index == 3 || error) { close(descriptor); error = EPROTO; continue; }
                descriptor = private_descriptor(descriptor);
                if (descriptor < 0) { error = -descriptor; continue; }
                launch->streams[stream_index++] = descriptor;
            }
        }
        if (error) return -error;
        offset += (size_t)count;
    }
    if (event.kind == KYO_PROCESS_FAILED && event.value > 0) return -event.value;
    if (event.kind != KYO_PROCESS_READY || event.value <= 0) return -EPROTO;
    for (int index = 0; index < 3; index++)
        if ((streams_mask & (1 << index)) && launch->streams[index] < 0) return -EPROTO;
    launch->acquired = 1;
    return event.value;
}

int kyo_process_launch_take_control(struct kyo_process_launch *launch) {
    if (!launch->acquired || launch->control < 0) return -EBADF;
    int descriptor = launch->control;
    launch->control = -1;
    return descriptor;
}

int kyo_process_launch_take_stream(struct kyo_process_launch *launch, int stream) {
    if (stream < 0 || stream > 2) return -EINVAL;
    if (!launch->acquired || launch->streams[stream] < 0) return -EBADF;
    int descriptor = launch->streams[stream];
    launch->streams[stream] = -1;
    return descriptor;
}

struct kyo_process_pipe *kyo_process_launch_take_pipe(struct kyo_process_launch *launch, int slot, int *error) {
    *error = 0;
    if (slot < 0 || slot > 3) { *error = EINVAL; return NULL; }
    int *descriptor = slot == 3 ? &launch->control : &launch->streams[slot];
    if (!launch->acquired || *descriptor < 0) { *error = EBADF; return NULL; }
    struct kyo_process_pipe *owner = kyo_process_pipe_init(*descriptor, error);
    if (owner) *descriptor = -1;
    return owner;
}

#endif
