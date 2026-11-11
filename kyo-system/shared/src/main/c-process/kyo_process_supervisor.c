#define _GNU_SOURCE
#include "kyo_process_protocol.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <poll.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/wait.h>
#include <unistd.h>

#ifdef __APPLE__
#include <libproc.h>
#include <sys/proc.h>
#include <sys/proc_info.h>
#endif

extern char **environ;
static int notification = -1;

static void child_changed(int number) {
    (void)number;
    int saved = errno;
    unsigned char byte = 0;
    ssize_t result = write(notification, &byte, 1);
    (void)result;
    errno = saved;
}

static int emit(int control, int kind, int value) {
    struct kyo_process_event event = {kind, value};
    const unsigned char *bytes = (const unsigned char *)&event;
    size_t remaining = sizeof(event);
    while (remaining) {
        ssize_t count = write(control, bytes, remaining);
        if (count < 0) {
            if (errno == EINTR) continue;
            return errno;
        }
        if (!count) return EIO;
        bytes += count;
        remaining -= (size_t)count;
    }
    return 0;
}

/* The runtime owns the helper, but these pipe ends belong to the command's caller. Hand them over
   before any ordinary control events; a runtime reaper must never close them behind a reader. */
static int ready(int control, pid_t child, const int *streams, int count) {
    if (!count) return emit(control, KYO_PROCESS_READY, child);
    struct kyo_process_event event = {KYO_PROCESS_READY, child};
    struct iovec data = {&event, sizeof(event)};
    union { struct cmsghdr alignment; unsigned char bytes[CMSG_SPACE(3 * sizeof(int))]; } ancillary = {0};
    struct msghdr message = {0};
    message.msg_iov = &data;
    message.msg_iovlen = 1;
    message.msg_control = ancillary.bytes;
    message.msg_controllen = CMSG_SPACE((size_t)count * sizeof(int));
    struct cmsghdr *rights = CMSG_FIRSTHDR(&message);
    rights->cmsg_level = SOL_SOCKET;
    rights->cmsg_type = SCM_RIGHTS;
    rights->cmsg_len = CMSG_LEN((size_t)count * sizeof(int));
    memcpy(CMSG_DATA(rights), streams, (size_t)count * sizeof(int));
    ssize_t bytes;
    do { bytes = sendmsg(control, &message, 0); } while (bytes < 0 && errno == EINTR);
    if (bytes < 0) return errno;
    if (!bytes) return EIO;
    /* A stream send may be short. Ancillary rights were sent once with its first byte. */
    size_t offset = (size_t)bytes;
    while (offset < sizeof(event)) {
        do { bytes = write(control, (unsigned char *)&event + offset, sizeof(event) - offset); } while (bytes < 0 && errno == EINTR);
        if (bytes <= 0) return bytes ? errno : EIO;
        offset += (size_t)bytes;
    }
    return 0;
}

static int private_pipe(int descriptors[2]) {
    if (pipe(descriptors)) return errno;
    for (int index = 0; index < 2; index++) {
        if (descriptors[index] < 3) {
            int duplicate = fcntl(descriptors[index], F_DUPFD_CLOEXEC, 3);
            int error = errno;
            close(descriptors[index]);
            descriptors[index] = duplicate;
            if (duplicate < 0) { close(descriptors[1 - index]); return error; }
        } else if (fcntl(descriptors[index], F_SETFD, FD_CLOEXEC)) {
            int error = errno;
            close(descriptors[0]);
            close(descriptors[1]);
            return error;
        }
    }
    return 0;
}

/* This executable has one thread. Enumerate its actual descriptors, without an fd-number bound or a spawn-time snapshot. */
static int close_inherited(int control) {
#ifdef __APPLE__
    const char *path = "/dev/fd";
#else
    const char *path = "/proc/self/fd";
#endif
    DIR *directory = opendir(path);
    if (!directory) return errno;
    int listing = dirfd(directory);
    int error = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (!entry) { error = errno; break; }
        char *end;
        long fd = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end || fd < 3 || fd > INT_MAX || fd == control || fd == listing) continue;
        if (close((int)fd)) { error = errno; break; }
    }
    if (closedir(directory) && !error) error = errno;
    return error;
}

static int observe(pid_t child, struct kyo_process_event *exit) {
    if (exit->kind) return 0;
    siginfo_t info = {0};
    int result;
    do { result = waitid(P_PID, child, &info, WEXITED | WNOWAIT | WNOHANG); } while (result && errno == EINTR);
    if (result) return errno;
    if (!info.si_pid) return 0;
    if (info.si_code == CLD_EXITED) {
        exit->kind = KYO_PROCESS_EXITED;
        exit->value = info.si_status;
    } else if (info.si_code == CLD_KILLED || info.si_code == CLD_DUMPED) {
        exit->kind = KYO_PROCESS_SIGNALED;
        exit->value = info.si_status;
    }
    return 0;
}

/* The leader remains unreaped throughout these observations, so the group id cannot be recycled. No listed pid is signalled. */
static int group_running(pid_t group) {
#ifdef __APPLE__
    int capacity = proc_listpids(PROC_PGRP_ONLY, (uint32_t)group, NULL, 0);
    if (capacity < 0) return -errno;
    if (capacity < (int)sizeof(pid_t)) capacity = (int)sizeof(pid_t);
    pid_t *pids = NULL;
    int count;
    for (;;) {
        if (capacity > INT_MAX / 2) { free(pids); return -EOVERFLOW; }
        capacity *= 2;
        pid_t *larger = realloc(pids, (size_t)capacity);
        if (!larger) { free(pids); return -ENOMEM; }
        pids = larger;
        count = proc_listpids(PROC_PGRP_ONLY, (uint32_t)group, pids, capacity);
        if (count < 0) { int error = errno; free(pids); return -error; }
        if (count < capacity) break;
    }
    int result = 0;
    for (int index = 0; index < count / (int)sizeof(pid_t); index++) {
        if (!pids[index]) continue;
        struct proc_bsdinfo info;
        int bytes = proc_pidinfo(pids[index], PROC_PIDTBSDINFO, 0, &info, sizeof(info));
        if (!bytes && (errno == ESRCH || errno == ENOENT)) continue;
        if (bytes != sizeof(info)) { result = bytes ? -EIO : -errno; break; }
        if (info.pbi_pgid == (uint32_t)group && info.pbi_status != SZOMB) { result = 1; break; }
    }
    free(pids);
    return result;
#else
    DIR *directory = opendir("/proc");
    if (!directory) return -errno;
    int result = 0;
    for (;;) {
        errno = 0;
        struct dirent *entry = readdir(directory);
        if (!entry) { if (errno) result = -errno; break; }
        char *end;
        long pid = strtol(entry->d_name, &end, 10);
        if (end == entry->d_name || *end || pid <= 0 || pid > INT_MAX) continue;
        char path[sizeof("/proc//stat") + sizeof(int) * CHAR_BIT];
        int size = snprintf(path, sizeof(path), "/proc/%ld/stat", pid);
        if (size < 0 || (size_t)size >= sizeof(path)) { result = -EOVERFLOW; break; }
        FILE *file = fopen(path, "r");
        if (!file) {
            if (errno == ENOENT || errno == ESRCH) continue;
            result = -errno;
            break;
        }
        char *line = NULL;
        size_t capacity = 0;
        /* comm can contain newlines and parentheses; read through EOF and parse after its final ')'. */
        ssize_t length = getdelim(&line, &capacity, '\0', file);
        int error = length < 0 ? (errno ? errno : EIO) : 0;
        if (fclose(file) && !error) error = errno;
        /* The process may be reaped after fopen succeeded. A stale proc file then fails its first
           read with ESRCH; this is the same absent member as a process that vanished before open. */
        if (error == ENOENT || error == ESRCH) { free(line); continue; }
        if (!error) {
            const char *fields = strrchr(line, ')');
            char state;
            long parent, pgrp;
            if (!fields || sscanf(fields + 1, " %c %ld %ld", &state, &parent, &pgrp) != 3) error = EIO;
            else if (pgrp == group && state != 'Z' && state != 'X') result = 1;
        }
        free(line);
        if (error) result = -error;
        if (result) break;
    }
    if (closedir(directory) && !result) result = -errno;
    return result;
#endif
}

static int kill_group(pid_t group) {
    if (!kill(-group, SIGKILL) || errno == ESRCH) return 0;
    int error = errno;
    /* XNU skips zombies in killpg and returns EPERM when none remain eligible.
       Verify termination instead of suppressing a permission failure on a live member. */
    if (error == EPERM) {
        int running = group_running(group);
        if (running <= 0) return -running;
    }
    return error;
}

static int release_child(int control, pid_t child, struct kyo_process_event *exit) {
    int error = kill_group(child);
    if (!exit->kind && kill(child, SIGKILL) && errno != ESRCH && !error) error = errno;
    while (!error) {
        int running = group_running(child);
        if (running < 0) { error = -running; break; }
        if (!running) break;
        /* Repeat against the still-owned group, including a fork that raced its first signal. There is no success timeout. */
        error = kill_group(child);
        if (error) break;
        int result;
        do { result = poll(NULL, 0, 5); } while (result < 0 && errno == EINTR);
        if (result < 0) error = errno;
    }
    if (error) {
        /* A failed kill or membership query does not establish termination. Do not block in waitpid on a potentially live child,
           and do not acknowledge successful cleanup. Exiting this isolated supervisor releases its remaining kernel ownership. */
        (void)emit(control, KYO_PROCESS_FAILED, error);
        (void)emit(control, KYO_PROCESS_RELEASED, error);
        return error;
    }
    int status;
    pid_t result;
    do { result = waitpid(child, &status, 0); } while (result < 0 && errno == EINTR);
    if (result != child) { if (!error) error = errno; }
    else if (!exit->kind) {
        exit->kind = WIFEXITED(status) ? KYO_PROCESS_EXITED : KYO_PROCESS_SIGNALED;
        exit->value = WIFEXITED(status) ? WEXITSTATUS(status) : WTERMSIG(status);
        (void)emit(control, exit->kind, exit->value);
    }
    (void)emit(control, KYO_PROCESS_RELEASED, error);
    return error;
}

/* argv: control endpoint (or inherited descriptor), cwd, stdio mask, executable, arguments.
   Bits 0/1/2 request owned stdin/stdout/stderr pipes; bit 3 redirects stderr to stdout. */
int main(int argc, char **argv) {
    if (argc < 5) return 2;
    char *mask_end;
    long streams_mask = strtol(argv[3], &mask_end, 10);
    if (mask_end == argv[3] || *mask_end || streams_mask < 0 || streams_mask > 15 ||
        ((streams_mask & 8) && (streams_mask & 4))) return 2;
    int control;
    if (argv[1][0] == '/') {
        struct sockaddr_un address = {0};
        address.sun_family = AF_UNIX;
        size_t length = strlen(argv[1]);
        if (length >= sizeof(address.sun_path)) return 2;
        memcpy(address.sun_path, argv[1], length + 1);
        control = socket(AF_UNIX, SOCK_STREAM, 0);
        if (control < 0 || connect(control, (struct sockaddr *)&address, sizeof(address))) return 2;
        if (control < 3) {
            int duplicate = fcntl(control, F_DUPFD_CLOEXEC, 3);
            close(control);
            if (duplicate < 0) return 2;
            control = duplicate;
        }
    } else {
        char *end;
        long descriptor = strtol(argv[1], &end, 10);
        if (end == argv[1] || *end || descriptor < 3 || descriptor > INT_MAX) return 2;
        control = (int)descriptor;
    }
    struct sigaction handler = {0};
    sigemptyset(&handler.sa_mask);
    handler.sa_handler = SIG_IGN;
    if (sigaction(SIGPIPE, &handler, NULL)) return 2;
    int error = close_inherited(control);
    if (!error && fcntl(control, F_SETFD, FD_CLOEXEC)) error = errno;
    if (!error && argv[2][0] && chdir(argv[2])) error = errno;
    int changes[2] = {-1, -1};
    if (!error) error = private_pipe(changes);
    if (!error) {
        for (int index = 0; index < 2; index++) {
            if (fcntl(changes[index], F_SETFD, FD_CLOEXEC) || fcntl(changes[index], F_SETFL, O_NONBLOCK)) {
                error = errno;
                break;
            }
        }
    }
    if (!error) {
        notification = changes[1];
        handler.sa_handler = child_changed;
        handler.sa_flags = SA_NOCLDSTOP;
        if (sigaction(SIGCHLD, &handler, NULL)) error = errno;
    }
    if (error) { (void)emit(control, KYO_PROCESS_FAILED, error); return 2; }

    posix_spawnattr_t attr;
    posix_spawn_file_actions_t actions;
    error = posix_spawnattr_init(&attr);
    if (error) { (void)emit(control, KYO_PROCESS_FAILED, error); return 2; }
    error = posix_spawn_file_actions_init(&actions);
    if (error) { posix_spawnattr_destroy(&attr); (void)emit(control, KYO_PROCESS_FAILED, error); return 2; }
    sigset_t defaults, mask;
    sigemptyset(&defaults);
    sigaddset(&defaults, SIGPIPE);
    sigemptyset(&mask);
    error = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP | POSIX_SPAWN_SETSIGDEF | POSIX_SPAWN_SETSIGMASK);
    if (!error) error = posix_spawnattr_setpgroup(&attr, 0);
    if (!error) error = posix_spawnattr_setsigdefault(&attr, &defaults);
    if (!error) error = posix_spawnattr_setsigmask(&attr, &mask);
    if (!error) error = posix_spawn_file_actions_addclose(&actions, control);
    int callers[3], child_ends[3], count = 0;
    for (int fd = 0; fd < 3 && !error; fd++) {
        if (streams_mask & (1 << fd)) {
            int ends[2];
            error = private_pipe(ends);
            if (error) break;
            callers[count] = ends[fd == 0 ? 1 : 0];
            child_ends[count] = ends[fd == 0 ? 0 : 1];
            error = posix_spawn_file_actions_adddup2(&actions, child_ends[count], fd);
            count++;
        } else if (fd == 2 && (streams_mask & 8)) {
            error = posix_spawn_file_actions_adddup2(&actions, 1, 2);
        } else if (fcntl(fd, F_GETFD) >= 0) {
            error = posix_spawn_file_actions_adddup2(&actions, fd, fd);
        } else if (errno != EBADF) error = errno;
    }
    pid_t child;
    if (!error) error = posix_spawnp(&child, argv[4], &actions, &attr, &argv[4], environ);
    posix_spawn_file_actions_destroy(&actions);
    posix_spawnattr_destroy(&attr);
    for (int index = 0; index < count; index++) close(child_ends[index]);
    if (error) {
        for (int index = 0; index < count; index++) close(callers[index]);
        (void)emit(control, KYO_PROCESS_FAILED, error);
        return 2;
    }
    /* Only the command retains the stream ends, so its ordinary exit can produce EOF. */
    for (int fd = 0; fd < 3; fd++) (void)close(fd);
    struct kyo_process_event exit = {0, 0};
    error = ready(control, child, callers, count);
    for (int index = 0; index < count; index++) close(callers[index]);
    if (error) return release_child(control, child, &exit) ? 1 : 0;
    struct pollfd descriptors[] = {{control, POLLIN, 0}, {changes[0], POLLIN, 0}};
    for (;;) {
        int previous = exit.kind;
        error = observe(child, &exit);
        if (error) { (void)emit(control, KYO_PROCESS_FAILED, error); break; }
        if (!previous && exit.kind && emit(control, exit.kind, exit.value)) break;
        int ready;
        do { ready = poll(descriptors, 2, -1); } while (ready < 0 && errno == EINTR);
        if (ready < 0) { error = errno; (void)emit(control, KYO_PROCESS_FAILED, error); break; }
        if (descriptors[0].revents) {
            unsigned char request;
            ssize_t count;
            do { count = read(control, &request, 1); } while (count < 0 && errno == EINTR);
            if (count <= 0 || request == KYO_PROCESS_RELEASE) break;
            if (request == KYO_PROCESS_POLL) {
                error = observe(child, &exit);
                if (!error) error = emit(control, exit.kind ? exit.kind : KYO_PROCESS_RUNNING, exit.value);
            } else if (request == KYO_PROCESS_TERMINATE || request == KYO_PROCESS_KILL) {
                error = observe(child, &exit);
                if (!error && !exit.kind && kill(child, request == KYO_PROCESS_TERMINATE ? SIGTERM : SIGKILL) && errno != ESRCH)
                    error = errno;
            } else error = EPROTO;
            if (error) { (void)emit(control, KYO_PROCESS_FAILED, error); break; }
        }
        if (descriptors[1].revents) {
            unsigned char bytes[64];
            while (read(changes[0], bytes, sizeof(bytes)) > 0) {}
        }
    }
    int cleanup = release_child(control, child, &exit);
    return error || cleanup ? 1 : 0;
}
