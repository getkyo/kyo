/*
 * kyo_uring.c: real-symbol shim over liburing's static-inline helpers.
 *
 * Most of liburing's hot path (io_uring_get_sqe, every io_uring_prep_*, the
 * set_data64 / cqe_get_data64 / cqe_seen accessors, peek_cqe, and the wait
 * helpers) is `static inline` in <liburing.h>: it has no exported symbol, so it
 * is unreachable by Panama's SymbolLookup.libraryLookup on the JVM or by Scala
 * Native's @link. Each kyo_uring_* function below is a one-line wrapper that
 * calls the inline helper and exposes it as a real exported symbol the
 * IoUringBindings trait can resolve. liburing is statically linked into this
 * shim, so the four directly-bound exports (io_uring_queue_init / queue_exit /
 * submit / wait_cqes) resolve here too and the shipped artifact carries no
 * runtime liburing dependency.
 *
 * macOS safety: io_uring is a Linux kernel feature and liburing exists only on
 * Linux. The #if guard below compiles this translation unit to nothing on every
 * other platform (and on a Linux host that lacks the liburing headers), so the
 * shared library still builds with zero liburing symbols referenced and the C
 * compiler needs no -luring. On those hosts IoUringBindings header-gates to
 * stubs and the io_uring backend probe reports unavailable.
 */

#if defined(__linux__) && __has_include(<liburing.h>)

#include <liburing.h>
#include <stddef.h>
#include <stdint.h>
#include <sys/socket.h>

/* ---- ring sizing ---- */

/* sizeof(struct io_uring): byte size the caller allocates for the ring Buffer. */
long kyo_uring_sizeof(void) {
    return (long)sizeof(struct io_uring);
}

/* ---- SQE acquisition + preparation ---- */

/* io_uring_get_sqe: next free SQE, or NULL (Absent) when the submission queue is full. */
struct io_uring_sqe* kyo_uring_get_sqe(struct io_uring* ring) {
    return io_uring_get_sqe(ring);
}

/*
 * Non-negativity guard at the C trust boundary (CWE-190 / CWE-195 / CWE-805): the
 * length args below are signed (int nbytes / long len) on the Scala side but the
 * io_uring_prep_* helpers take an unsigned (unsigned / size_t). A negative length
 * would wrap to a huge unsigned at the cast and become an out-of-bounds kernel
 * read/write at submission. So each prep_* function checks the length BEFORE the
 * cast and refuses to prepare the SQE when it is negative, returning -1 (rejected)
 * instead of 0 (prepared). The Scala caller (IoUringDriver) maps a non-zero return
 * to an OBSERVABLE failure (fails the read/write promise) rather than letting it pass
 * silently: a bare drop here would leave the ring waiting on a CQE that never comes.
 * The non-negative path is byte-for-byte the prior behavior. Today's callers always
 * pass a positive length, so this only guards a future signedness bug.
 */

int kyo_uring_prep_read(struct io_uring_sqe* sqe, int fd, void* buf, int nbytes, long offset) {
    if (nbytes < 0) return -1;
    io_uring_prep_read(sqe, fd, buf, (unsigned)nbytes, (__u64)offset);
    return 0;
}

int kyo_uring_prep_write(struct io_uring_sqe* sqe, int fd, void* buf, int nbytes, long offset) {
    if (nbytes < 0) return -1;
    io_uring_prep_write(sqe, fd, buf, (unsigned)nbytes, (__u64)offset);
    return 0;
}

int kyo_uring_prep_recv(struct io_uring_sqe* sqe, int fd, void* buf, long len, int flags) {
    if (len < 0) return -1;
    io_uring_prep_recv(sqe, fd, buf, (size_t)len, flags);
    return 0;
}

int kyo_uring_prep_send(struct io_uring_sqe* sqe, int fd, void* buf, long len, int flags) {
    if (len < 0) return -1;
    io_uring_prep_send(sqe, fd, buf, (size_t)len, flags);
    return 0;
}

void kyo_uring_prep_accept(struct io_uring_sqe* sqe, int fd, void* addr, void* addrlen, int flags) {
    io_uring_prep_accept(sqe, fd, (struct sockaddr*)addr, (socklen_t*)addrlen, flags);
}

void kyo_uring_prep_connect(struct io_uring_sqe* sqe, int fd, void* addr, int addrlen) {
    io_uring_prep_connect(sqe, fd, (struct sockaddr*)addr, (socklen_t)addrlen);
}

/* io_uring_sqe_set_data64: store the per-op key the completion is matched against. */
void kyo_uring_sqe_set_data64(struct io_uring_sqe* sqe, long data) {
    io_uring_sqe_set_data64(sqe, (__u64)data);
}

/* ---- completion reaping ---- */

/*
 * Bounded wait: wrap io_uring_wait_cqes with a __kernel_timespec so the
 * carrier is returned each timeoutNs cycle. The ready CQE pointer is written into
 * the 1-element cqePtr buffer. Returns 0 on a ready CQE, -ETIME on timeout,
 * -errno otherwise (the negated return io_uring_wait_cqes already yields).
 */
int kyo_uring_wait_cqe_timeout(struct io_uring* ring, void* cqePtr, long timeoutNs) {
    struct __kernel_timespec ts;
    ts.tv_sec  = (long long)(timeoutNs / 1000000000L);
    ts.tv_nsec = (long long)(timeoutNs % 1000000000L);
    struct io_uring_cqe* cqe = NULL;
    int ret = io_uring_wait_cqes(ring, &cqe, 1, &ts, NULL);
    *((struct io_uring_cqe**)cqePtr) = cqe;
    return ret;
}

/*
 * io_uring_peek_cqe: drain the next already-ready CQE into cqePtr without
 * blocking. Returns 0 when one was placed, nonzero (-EAGAIN) when the completion
 * queue is empty.
 */
int kyo_uring_peek_cqe(struct io_uring* ring, void* cqePtr) {
    struct io_uring_cqe* cqe = NULL;
    int ret = io_uring_peek_cqe(ring, &cqe);
    *((struct io_uring_cqe**)cqePtr) = cqe;
    return ret;
}

/* io_uring_cqe_get_data64: read back the key set with kyo_uring_sqe_set_data64. */
long kyo_uring_cqe_get_data64(long cqe) {
    return (long)io_uring_cqe_get_data64((struct io_uring_cqe*)cqe);
}

/* CQE result: >= 0 is the byte count or accepted fd, < 0 is -errno. */
int kyo_uring_cqe_res(long cqe) {
    return ((struct io_uring_cqe*)cqe)->res;
}

/* io_uring_cqe_seen: advance the completion queue past cqe. */
void kyo_uring_cqe_seen(struct io_uring* ring, long cqe) {
    io_uring_cqe_seen(ring, (struct io_uring_cqe*)cqe);
}

/* ---- availability probe ---- */

/*
 * One-shot availability probe for the io_uring backend's isAvailable: set up a ring at the default
 * production depth (256, the floor IoUringDriver.init uses) then tear it down. Returns 1 when
 * io_uring_queue_init succeeds (the kernel supports io_uring and the runtime is not sandboxed away from
 * it), 0 otherwise. Marshalled as a platform int (1/0) the Boolean binding reads back.
 *
 * Probing at production depth (not a token depth of 2) is deliberate: on some sandboxes (containers
 * with a restrictive RLIMIT_MEMLOCK or seccomp policy) a tiny ring initializes but a production-depth
 * ring does not. A depth-2 probe would then report io_uring available and selection would build the
 * real driver only for its production-depth queue_init to fail, so the probe must exercise the depth
 * the driver actually uses.
 */
int kyo_uring_probe_available(int depth) {
    struct io_uring ring;
    int ret = io_uring_queue_init(depth, &ring, 0);
    if (ret != 0) return 0;
    io_uring_queue_exit(&ring);
    return 1;
}

#else

/*
 * Non-Linux (or Linux without liburing headers): empty translation unit. ISO C
 * forbids a file with no external declarations, so emit one harmless typedef to
 * keep the compiler quiet. No liburing symbol is referenced, so the shared
 * library links with no -luring on these hosts (macOS local build, etc.).
 */
typedef int kyo_uring_unavailable_t;

#endif
