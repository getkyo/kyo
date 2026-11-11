#ifndef KYO_PROCESS_LAUNCH_H
#define KYO_PROCESS_LAUNCH_H

struct kyo_process_launch;
struct kyo_process_pipe;

/* Blocking preparation. On failure returns NULL and writes errno; no resources transfer. */
struct kyo_process_launch *kyo_process_launch_init(const char *temporary_directory, int *error);

/* Borrowed strings remain valid until release. Launch the executable through the host runtime's
 * normal process API, passing endpoint as argv[1]. The runtime owns and reaps that supervisor;
 * only the supervisor owns the command, so a host waitpid(-1) cannot reap the command. */
const char *kyo_process_launch_executable(struct kyo_process_launch *launch);
const char *kyo_process_launch_endpoint(struct kyo_process_launch *launch);

/* Transfers the nonblocking listener to a platform readiness API (Node net.Server). */
int kyo_process_launch_take_listener(struct kyo_process_launch *launch);

/* Nonblocking accept for the JVM/Native readiness path. Returns an owned descriptor or negative errno. */
int kyo_process_launch_accept(struct kyo_process_launch *launch);

/* One blocking acquisition, after launching the helper. Returns its command pid or negative errno.
 * cancel is nonblocking and may run concurrently; all other operations require joining acquisition.
 * Untransferred descriptors remain owned by launch on every failure path. */
int kyo_process_launch_await(struct kyo_process_launch *launch, int streams_mask);
int kyo_process_launch_cancel(struct kyo_process_launch *launch);
/* Wake acquisition when the runtime-owned helper exits. Unlike cancellation, drain a queued
 * connection/reply first so an exec failure keeps its original errno. May race acquisition. */
int kyo_process_launch_helper_exited(struct kyo_process_launch *launch);
/* After joining acquisition, close untransferred control/listener descriptors to request cleanup.
 * The allocation remains live for a helper-exit watcher; join that watcher before release. */
int kyo_process_launch_disconnect(struct kyo_process_launch *launch);
int kyo_process_launch_take_control(struct kyo_process_launch *launch);
int kyo_process_launch_take_stream(struct kyo_process_launch *launch, int stream);

/* JVM/Native stream ownership transfer. Slots 0/1/2 are stdio, 3 is control. On allocation failure
 * the descriptor stays in launch; on success the returned pipe owns it. Join before releasing launch. */
struct kyo_process_pipe *kyo_process_launch_take_pipe(struct kyo_process_launch *launch, int slot, int *error);

/* Join all native calls first. Closes a listener not transferred above, removes both private directories,
 * and frees the allocation. Close an accepted control connection separately to request command cleanup. */
int kyo_process_launch_release(struct kyo_process_launch *launch);

#endif
