#ifndef KYO_PROCESS_PIPE_H
#define KYO_PROCESS_PIPE_H

struct kyo_process_pipe;

/* A blocking pipe for child-to-child connections, close-on-exec in the host. Caller owns both descriptors on success. */
int kyo_process_pipe_pair(int descriptors[2]);

/* Snapshot a Node-owned nonblocking descriptor without parking a foreign-call worker. */
int kyo_process_pipe_available_descriptor(int descriptor);

/* Transfers the descriptor on success; on failure the caller still owns it. */
struct kyo_process_pipe *kyo_process_pipe_init(int descriptor, int *error);

/* Blocking carrier-thread operation: bytes read, zero for EOF, or negative errno. */
int kyo_process_pipe_read(struct kyo_process_pipe *pipe, unsigned char *bytes, int capacity);

/* Blocking carrier-thread operation: bytes written or negative errno. Never delivers SIGPIPE to the host. */
int kyo_process_pipe_write(struct kyo_process_pipe *pipe, const unsigned char *bytes, int offset, int length);

/* Ends reads after the bytes already buffered at this call. Does not signal any writer.
 * Nonblocking: returns -EAGAIN if a reader is in its short descriptor-access section; the caller yields and retries.
 */
int kyo_process_pipe_cancel(struct kyo_process_pipe *pipe);

/* Cancels writes and discards unread input. Same nonblocking retry contract as cancel. */
int kyo_process_pipe_stop(struct kyo_process_pipe *pipe);

/* The caller must join all read/write/cancel/stop calls before releasing the native allocation. */
int kyo_process_pipe_release(struct kyo_process_pipe *pipe);

#endif
