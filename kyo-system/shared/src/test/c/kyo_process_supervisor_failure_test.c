/* Inject a real permission failure and reject an unbounded wait for a child the supervisor could not terminate. */
#define kill kyo_process_test_kill
#define waitpid kyo_process_test_waitpid
#define main kyo_process_supervisor_main
#include "../../main/c-process/kyo_process_supervisor.c"
#undef main
#undef waitpid
#undef kill
#include <assert.h>

extern int kill(pid_t, int);
extern pid_t waitpid(pid_t, int *, int);
static int deny_signals;

int kyo_process_test_kill(pid_t pid, int signal) {
    if (deny_signals && signal == SIGKILL) { errno = EPERM; return -1; }
    return kill(pid, signal);
}

pid_t kyo_process_test_waitpid(pid_t pid, int *status, int options) {
    /* The child is held alive by a pipe, so a blocking wait here cannot finish. This assertion exposes the bug without a timing race. */
    assert(!deny_signals || (options & WNOHANG));
    return waitpid(pid, status, options);
}

int main(void) {
    int command_input[2], ready_pipe[2], control[2];
    assert(pipe(command_input) == 0);
    assert(pipe(ready_pipe) == 0);
    assert(socketpair(AF_UNIX, SOCK_STREAM, 0, control) == 0);
    pid_t child = fork();
    assert(child >= 0);
    if (!child) {
        close(command_input[1]);
        close(ready_pipe[0]);
        close(control[0]);
        close(control[1]);
        if (setpgid(0, 0)) _exit(2);
        if (write(ready_pipe[1], "r", 1) != 1) _exit(3);
        close(ready_pipe[1]);
        char byte;
        while (read(command_input[0], &byte, 1) < 0 && errno == EINTR) {}
        _exit(0);
    }
    close(command_input[0]);
    close(ready_pipe[1]);
    char ready_byte;
    assert(read(ready_pipe[0], &ready_byte, 1) == 1 && ready_byte == 'r');
    close(ready_pipe[0]);
    struct kyo_process_event exit = {0, 0};
    deny_signals = 1;
    assert(release_child(control[0], child, &exit) == EPERM);
    struct kyo_process_event event;
    assert(recv(control[1], &event, sizeof(event), MSG_WAITALL) == sizeof(event));
    assert(event.kind == KYO_PROCESS_FAILED && event.value == EPERM);
    assert(recv(control[1], &event, sizeof(event), MSG_WAITALL) == sizeof(event));
    assert(event.kind == KYO_PROCESS_RELEASED && event.value == EPERM);
    close(control[0]);
    close(control[1]);
    /* The fixture still owns the child. EOF lets it exit normally; no cached numeric process signal is needed for cleanup. */
    deny_signals = 0;
    close(command_input[1]);
    int status;
    assert(waitpid(child, &status, 0) == child);
    assert(WIFEXITED(status) && WEXITSTATUS(status) == 0);
    puts("permission failure is reported without waiting indefinitely or claiming successful cleanup");
    return 0;
}
