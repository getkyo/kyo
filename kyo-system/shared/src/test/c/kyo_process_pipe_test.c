#include "kyo_process_pipe.h"
#include <assert.h>
#include <errno.h>
#include <dirent.h>
#include <fcntl.h>
#include <poll.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <string.h>
#include <sys/resource.h>
#include <sys/wait.h>
#include <unistd.h>

/* Compile kyo_process_pipe.c with -Dpoll=kyo_process_pipe_test_poll, and this test without that define.
 * Link both units with -pthread. The rendezvous delegates to the real poll after announcing the wait.
 */
static int waiting = -1;
int kyo_process_pipe_test_poll(struct pollfd *descriptors, nfds_t count, int timeout) {
    if (waiting >= 0) {
        assert(write(waiting, "w", 1) == 1);
    }
    return poll(descriptors, count, timeout);
}

static struct kyo_process_pipe *init_pipe(int descriptor) {
    int error = -1;
    struct kyo_process_pipe *owner = kyo_process_pipe_init(descriptor, &error);
    assert(error == (owner ? 0 : errno));
    return owner;
}

struct read_call { struct kyo_process_pipe *owner; int result; };
static void *read_pipe(void *argument) {
    struct read_call *call = argument;
    unsigned char byte;
    call->result = kyo_process_pipe_read(call->owner, &byte, 1);
    return NULL;
}

static void *write_pipe(void *argument) {
    struct read_call *call = argument;
    call->result = kyo_process_pipe_write(call->owner, (const unsigned char *)"x", 0, 1);
    return NULL;
}

static void cancellation_wakes_a_writer_without_closing_its_readers_descriptor(void) {
    int data[2], rendezvous[2];
    assert(pipe(data) == 0 && pipe(rendezvous) == 0);
    struct kyo_process_pipe *owner = init_pipe(data[1]);
    assert(owner);
    unsigned char bytes[4096];
    memset(bytes, 'b', sizeof(bytes));
    while (write(data[1], bytes, sizeof(bytes)) > 0) {}
    assert(errno == EAGAIN || errno == EWOULDBLOCK);
    waiting = rendezvous[1];
    struct read_call call = {owner, -1};
    pthread_t writer;
    assert(pthread_create(&writer, NULL, write_pipe, &call) == 0);
    char byte;
    assert(read(rendezvous[0], &byte, 1) == 1 && byte == 'w');
    assert(kyo_process_pipe_stop(owner) == 0);
    assert(pthread_join(writer, NULL) == 0);
    waiting = -1;
    assert(call.result == -ECANCELED);
    assert(kyo_process_pipe_release(owner) == 0);
    assert(read(data[0], &byte, 1) == 1 && byte == 'b');
    close(data[0]);
    close(rendezvous[0]);
    close(rendezvous[1]);
}

static void a_closed_reader_reports_epipe_without_signalling_the_host(void) {
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        assert(signal(SIGPIPE, SIG_DFL) != SIG_ERR);
        int data[2];
        assert(pipe(data) == 0);
        struct kyo_process_pipe *owner = init_pipe(data[1]);
        assert(owner);
        close(data[0]);
        assert(kyo_process_pipe_write(owner, (const unsigned char *)"x", 0, 1) == -EPIPE);
        struct sigaction disposition;
        assert(sigaction(SIGPIPE, NULL, &disposition) == 0 && disposition.sa_handler == SIG_DFL);
        assert(signal(SIGPIPE, SIG_IGN) != SIG_ERR);
        assert(kyo_process_pipe_write(owner, (const unsigned char *)"x", 0, 1) == -EPIPE);
        assert(sigaction(SIGPIPE, NULL, &disposition) == 0 && disposition.sa_handler == SIG_IGN);
        assert(signal(SIGPIPE, SIG_DFL) != SIG_ERR);
        sigset_t blocked, previous, pending;
        sigemptyset(&blocked);
        sigaddset(&blocked, SIGPIPE);
        assert(pthread_sigmask(SIG_BLOCK, &blocked, &previous) == 0);
        assert(raise(SIGPIPE) == 0);
        assert(kyo_process_pipe_write(owner, (const unsigned char *)"x", 0, 1) == -EPIPE);
        assert(sigpending(&pending) == 0 && sigismember(&pending, SIGPIPE));
        int number;
        assert(sigwait(&blocked, &number) == 0 && number == SIGPIPE);
        assert(pthread_sigmask(SIG_SETMASK, &previous, NULL) == 0);
        assert(kyo_process_pipe_release(owner) == 0);
        _exit(0);
    }
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
}

static void cancellation_leaves_writer_alive(void) {
    int data[2], control[2], ready[2];
    assert(pipe(data) == 0 && pipe(control) == 0 && pipe(ready) == 0);
    pid_t writer = fork();
    assert(writer >= 0);
    if (!writer) {
        close(data[0]);
        close(control[1]);
        close(ready[0]);
        char command;
        if (write(ready[1], "r", 1) != 1) _exit(2);
        if (read(control[0], &command, 1) != 1 || command != 'x') _exit(3);
        close(data[1]);
        _exit(0);
    }
    close(data[1]);
    close(control[0]);
    close(ready[1]);
    char byte;
    assert(read(ready[0], &byte, 1) == 1 && byte == 'r');
    close(ready[0]);
    struct kyo_process_pipe *owner = init_pipe(data[0]);
    assert(owner);
    int rendezvous[2];
    assert(pipe(rendezvous) == 0);
    waiting = rendezvous[1];
    struct read_call call = {owner, -1};
    pthread_t reader;
    assert(pthread_create(&reader, NULL, read_pipe, &call) == 0);
    assert(read(rendezvous[0], &byte, 1) == 1 && byte == 'w');
    assert(kyo_process_pipe_cancel(owner) == 0);
    assert(pthread_join(reader, NULL) == 0);
    waiting = -1;
    assert(call.result == 0);
    int status;
    assert(waitpid(writer, &status, WNOHANG) == 0);
    assert(kyo_process_pipe_cancel(owner) == 0);
    assert(kyo_process_pipe_release(owner) == 0);
    assert(fcntl(data[0], F_GETFD) == -1 && errno == EBADF);
    assert(write(control[1], "x", 1) == 1);
    assert(waitpid(writer, &status, 0) == writer && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    close(control[1]);
    close(rendezvous[0]);
    close(rendezvous[1]);
}

static void cancellation_drains_only_buffered_output(void) {
    int data[2];
    assert(pipe(data) == 0);
    struct kyo_process_pipe *owner = init_pipe(data[0]);
    assert(owner);
    assert(write(data[1], "before", 6) == 6);
    assert(kyo_process_pipe_cancel(owner) == 0);
    assert(write(data[1], "after", 5) == 5);
    unsigned char bytes[16] = {0};
    assert(kyo_process_pipe_read(owner, bytes, 2) == 2);
    assert(kyo_process_pipe_read(owner, bytes + 2, 14) == 4);
    assert(memcmp(bytes, "before", 6) == 0);
    assert(kyo_process_pipe_read(owner, bytes + 6, 10) == 0);
    assert(kyo_process_pipe_cancel(owner) == 0);
    assert(kyo_process_pipe_read(owner, bytes + 6, 10) == 0);
    assert(kyo_process_pipe_release(owner) == 0);
    close(data[1]);
}

static void ordinary_eof_and_empty_reads(void) {
    int data[2];
    assert(pipe(data) == 0);
    struct kyo_process_pipe *owner = init_pipe(data[0]);
    assert(owner);
    unsigned char bytes[8] = {0};
    assert(kyo_process_pipe_read(owner, bytes, 0) == 0);
    assert(kyo_process_pipe_read(owner, bytes, -1) == -EINVAL);
    assert(write(data[1], "output", 6) == 6);
    close(data[1]);
    assert(kyo_process_pipe_read(owner, bytes, 8) == 6);
    assert(memcmp(bytes, "output", 6) == 0);
    assert(kyo_process_pipe_read(owner, bytes, 8) == 0);
    assert(kyo_process_pipe_cancel(owner) == 0);
    assert(kyo_process_pipe_release(owner) == 0);
    assert(init_pipe(-1) == NULL && errno == EBADF);
}

static void cancellation_wakes_every_reader(void) {
    int data[2], rendezvous[2];
    assert(pipe(data) == 0 && pipe(rendezvous) == 0);
    struct kyo_process_pipe *owner = init_pipe(data[0]);
    assert(owner);
    waiting = rendezvous[1];
    struct read_call calls[] = {{owner, -1}, {owner, -1}};
    pthread_t readers[2];
    for (int index = 0; index < 2; index++)
        assert(pthread_create(&readers[index], NULL, read_pipe, &calls[index]) == 0);
    char byte;
    for (int index = 0; index < 2; index++)
        assert(read(rendezvous[0], &byte, 1) == 1 && byte == 'w');
    assert(kyo_process_pipe_cancel(owner) == 0);
    for (int index = 0; index < 2; index++) {
        assert(pthread_join(readers[index], NULL) == 0);
        assert(calls[index].result == 0);
    }
    waiting = -1;
    assert(kyo_process_pipe_release(owner) == 0);
    close(data[1]);
    close(rendezvous[0]);
    close(rendezvous[1]);
}

static int descriptor_count(void) {
    DIR *directory = opendir("/dev/fd");
    assert(directory);
    int count = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)))
        if (entry->d_name[0] != '.') count++;
    assert(closedir(directory) == 0);
    return count;
}

static void failed_initialization_preserves_the_callers_descriptor(void) {
    int data[2];
    assert(pipe(data) == 0);
    int before = descriptor_count();
    int flags = fcntl(data[0], F_GETFL);
    assert(flags >= 0);
    struct rlimit original;
    assert(getrlimit(RLIMIT_NOFILE, &original) == 0);
    struct rlimit exhausted = original;
    exhausted.rlim_cur = 0;
    assert(setrlimit(RLIMIT_NOFILE, &exhausted) == 0);
    struct kyo_process_pipe *owner = init_pipe(data[0]);
    int error = errno;
    assert(setrlimit(RLIMIT_NOFILE, &original) == 0);
    assert(owner == NULL && error == EMFILE);
    assert(fcntl(data[0], F_GETFL) == flags);
    assert(descriptor_count() == before);
    assert(write(data[1], "x", 1) == 1);
    char byte;
    assert(read(data[0], &byte, 1) == 1 && byte == 'x');
    close(data[0]);
    close(data[1]);
}

static void pipeline_descriptors_are_blocking_and_close_on_exec(void) {
    int data[2];
    assert(kyo_process_pipe_pair(data) == 0);
    for (int i = 0; i < 2; i++) {
        assert(fcntl(data[i], F_GETFD) & FD_CLOEXEC);
        assert(!(fcntl(data[i], F_GETFL) & O_NONBLOCK));
    }
    assert(write(data[1], "pipe", 4) == 4);
    char bytes[4];
    assert(read(data[0], bytes, 4) == 4);
    assert(memcmp(bytes, "pipe", 4) == 0);
    close(data[0]);
    close(data[1]);
}

int main(void) {
    int before = descriptor_count();
    cancellation_leaves_writer_alive();
    cancellation_drains_only_buffered_output();
    ordinary_eof_and_empty_reads();
    cancellation_wakes_every_reader();
    failed_initialization_preserves_the_callers_descriptor();
    cancellation_wakes_a_writer_without_closing_its_readers_descriptor();
    a_closed_reader_reports_epipe_without_signalling_the_host();
    pipeline_descriptors_are_blocking_and_close_on_exec();
    assert(descriptor_count() == before);
    puts("8 process pipe tests passed; no descriptor leak");
    return 0;
}
