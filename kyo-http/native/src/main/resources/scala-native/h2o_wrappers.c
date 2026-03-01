/*
 * C bridge for libh2o — async HTTP server with event loop.
 *
 * h2o runs a single-threaded event loop. The handler callback fires on the
 * event loop thread, extracts request data, and returns 0 (async). Scala
 * launches a fiber to process the request. When the fiber completes, it
 * enqueues the response and wakes the event loop via a pipe. The event loop
 * drains the queue and calls h2o_send().
 *
 * Streaming responses use h2o's generator (proceed/stop callbacks) with a
 * two-state machine coordinating between h2o pull and fiber push.
 *
 * Multiple servers are supported — no global state. Each server's callbacks
 * receive the server pointer via socket data fields or custom handler structs.
 */

#define H2O_USE_LIBUV 0
#include <h2o.h>
#include <h2o/socket/evloop.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <arpa/inet.h>
#include <fcntl.h>
#include <signal.h>

/* ── Forward declaration ───────────────────────────────────────────── */

typedef struct kyo_h2o_server kyo_h2o_server;

/* ── Callback function pointer types (set from Scala) ──────────────── */

/* Handler callback: receives server + h2o_req_t*, returns 0.
 * Scala extracts request data and launches a fiber. */
typedef int (*kyo_h2o_handler_fn)(kyo_h2o_server *server, h2o_req_t *req);

/* Response pipe callback: called when response pipe is readable.
 * Scala drains the response queue and sends responses. */
typedef void (*kyo_h2o_drain_fn)(kyo_h2o_server *server);

/* Generator proceed callback: called when h2o is ready for more data.
 * The stream_id identifies the StreamContext on the Scala side. */
typedef void (*kyo_h2o_proceed_fn)(int stream_id);

/* Generator stop callback: called when client disconnects mid-stream. */
typedef void (*kyo_h2o_stop_fn)(int stream_id);

/* ── Server struct ─────────────────────────────────────────────────── */

struct kyo_h2o_server {
    h2o_globalconf_t config;
    h2o_context_t ctx;
    h2o_accept_ctx_t accept_ctx;
    h2o_hostconf_t *hostconf;
    h2o_socket_t *listener;
    h2o_socket_t *response_sock;
    int response_pipe[2];        /* [0]=read (h2o), [1]=write (Scala) */
    int listen_fd;
    int actual_port;
    volatile int running;

    /* Scala callbacks */
    kyo_h2o_handler_fn handler_fn;
    kyo_h2o_drain_fn drain_fn;
    kyo_h2o_proceed_fn proceed_fn;
    kyo_h2o_stop_fn stop_fn;
};

/* ── Custom handler struct (carries server pointer) ────────────────── */

typedef struct {
    h2o_handler_t super;
    kyo_h2o_server *server;
} kyo_h2o_handler;

/* ── Streaming generator struct ────────────────────────────────────── */

typedef struct {
    h2o_generator_t super;
    kyo_h2o_server *server;
    h2o_req_t *req;
    int stream_id;
} kyo_h2o_generator;

/* ── Generator callbacks ───────────────────────────────────────────── */

static void generator_proceed(h2o_generator_t *self, h2o_req_t *req) {
    kyo_h2o_generator *gen = (kyo_h2o_generator *)self;
    if (gen->server->proceed_fn) {
        gen->server->proceed_fn(gen->stream_id);
    }
}

static void generator_stop(h2o_generator_t *self, h2o_req_t *req) {
    kyo_h2o_generator *gen = (kyo_h2o_generator *)self;
    if (gen->server->stop_fn) {
        gen->server->stop_fn(gen->stream_id);
    }
}

/* ── Accept callback ───────────────────────────────────────────────── */

static void on_accept(h2o_socket_t *listener, const char *err) {
    if (err != NULL) return;
    kyo_h2o_server *server = (kyo_h2o_server *)listener->data;
    if (server == NULL) return;

    h2o_socket_t *sock = h2o_evloop_socket_accept(listener);
    if (sock == NULL) return;

    h2o_accept(&server->accept_ctx, sock);
}

/* ── Handler callback ──────────────────────────────────────────────── */

static int on_req(h2o_handler_t *self, h2o_req_t *req) {
    kyo_h2o_handler *h = (kyo_h2o_handler *)self;
    kyo_h2o_server *server = h->server;
    if (server && server->handler_fn) {
        return server->handler_fn(server, req);
    }
    /* No handler registered — 500 */
    req->res.status = 500;
    req->res.reason = "Internal Server Error";
    h2o_send_inline(req, H2O_STRLIT("No handler"));
    return 0;
}

/* ── Response pipe callback ────────────────────────────────────────── */

static void on_response_pipe(h2o_socket_t *sock, const char *err) {
    (void)err;
    kyo_h2o_server *server = (kyo_h2o_server *)sock->data;
    if (server == NULL) return;

    /* Drain the pipe (just a wakeup signal) */
    char buf[64];
    while (read(server->response_pipe[0], buf, sizeof(buf)) > 0)
        ;

    /* Call Scala to drain the response queue */
    if (server->drain_fn) {
        server->drain_fn(server);
    }
}

/* ── Create listener socket ────────────────────────────────────────── */

static int create_listener(const char *host, int port, int backlog) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;

    int reuseaddr = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &reuseaddr, sizeof(reuseaddr));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons((uint16_t)port);
    if (host && strcmp(host, "0.0.0.0") != 0) {
        inet_pton(AF_INET, host, &addr.sin_addr);
    } else {
        addr.sin_addr.s_addr = INADDR_ANY;
    }

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(fd);
        return -1;
    }

    if (listen(fd, backlog) < 0) {
        close(fd);
        return -1;
    }

    return fd;
}

static int get_bound_port(int fd) {
    struct sockaddr_in addr;
    socklen_t len = sizeof(addr);
    if (getsockname(fd, (struct sockaddr *)&addr, &len) < 0) return 0;
    return ntohs(addr.sin_port);
}

/* ── Public API ────────────────────────────────────────────────────── */

kyo_h2o_server *kyo_h2o_start(const char *host, int port,
                               int max_body_size, int backlog) {
    /* Ignore SIGPIPE (standard for servers) */
    signal(SIGPIPE, SIG_IGN);

    kyo_h2o_server *server = (kyo_h2o_server *)calloc(1, sizeof(kyo_h2o_server));
    if (!server) return NULL;

    /* Initialize h2o config */
    h2o_config_init(&server->config);
    server->config.max_request_entity_size = (size_t)max_body_size;

    /* Register host and catch-all path */
    server->hostconf = h2o_config_register_host(
        &server->config,
        h2o_iovec_init(H2O_STRLIT("default")),
        65535
    );

    h2o_pathconf_t *pathconf = h2o_config_register_path(
        server->hostconf, "/", 0
    );

    /* Use custom handler struct that carries the server pointer */
    kyo_h2o_handler *handler = (kyo_h2o_handler *)h2o_create_handler(
        pathconf, sizeof(kyo_h2o_handler));
    handler->super.on_req = on_req;
    handler->server = server;

    /* Create listener socket */
    server->listen_fd = create_listener(host, port, backlog);
    if (server->listen_fd < 0) {
        h2o_config_dispose(&server->config);
        free(server);
        return NULL;
    }
    server->actual_port = get_bound_port(server->listen_fd);

    /* Create response pipe (non-blocking read end) */
    if (pipe(server->response_pipe) < 0) {
        close(server->listen_fd);
        h2o_config_dispose(&server->config);
        free(server);
        return NULL;
    }
    /* Make read end non-blocking */
    {
        int flags = fcntl(server->response_pipe[0], F_GETFL, 0);
        fcntl(server->response_pipe[0], F_SETFL, flags | O_NONBLOCK);
    }

    /* Initialize context with event loop */
    h2o_context_init(&server->ctx, h2o_evloop_create(), &server->config);

    /* Register listener with event loop, store server in socket data */
    server->listener = h2o_evloop_socket_create(
        server->ctx.loop,
        server->listen_fd,
        H2O_SOCKET_FLAG_DONT_READ
    );
    server->listener->data = server;
    h2o_socket_read_start(server->listener, on_accept);

    /* Register response pipe with event loop, store server in socket data */
    server->response_sock = h2o_evloop_socket_create(
        server->ctx.loop,
        server->response_pipe[0],
        H2O_SOCKET_FLAG_DONT_READ
    );
    server->response_sock->data = server;
    h2o_socket_read_start(server->response_sock, on_response_pipe);

    /* Initialize accept context (plaintext) */
    memset(&server->accept_ctx, 0, sizeof(server->accept_ctx));
    server->accept_ctx.ctx = &server->ctx;
    server->accept_ctx.hosts = server->config.hosts;

    server->running = 1;
    return server;
}

/* Run one iteration of the event loop. Returns 0 if still running, 1 if stopped. */
int kyo_h2o_evloop_run_once(kyo_h2o_server *server) {
    if (!server->running) return 1;
    h2o_evloop_run(server->ctx.loop, INT32_MAX);
    return server->running ? 0 : 1;
}

void kyo_h2o_stop(kyo_h2o_server *server) {
    server->running = 0;
    /* Wake event loop by writing to response pipe */
    char c = 0;
    write(server->response_pipe[1], &c, 1);
}

void kyo_h2o_destroy(kyo_h2o_server *server) {
    if (server->listener) {
        h2o_socket_read_stop(server->listener);
        h2o_socket_close(server->listener);
    }
    if (server->response_sock) {
        h2o_socket_read_stop(server->response_sock);
        h2o_socket_close(server->response_sock);
    }
    close(server->listen_fd);
    close(server->response_pipe[0]);
    close(server->response_pipe[1]);
    h2o_context_dispose(&server->ctx);
    h2o_config_dispose(&server->config);
    free(server);
}

int kyo_h2o_port(kyo_h2o_server *server) {
    return server->actual_port;
}

int kyo_h2o_response_fd(kyo_h2o_server *server) {
    return server->response_pipe[1];
}

/* ── Callback registration ─────────────────────────────────────────── */

void kyo_h2o_set_handler(kyo_h2o_server *server, kyo_h2o_handler_fn fn) {
    server->handler_fn = fn;
}

void kyo_h2o_set_drain(kyo_h2o_server *server, kyo_h2o_drain_fn fn) {
    server->drain_fn = fn;
}

void kyo_h2o_set_proceed(kyo_h2o_server *server, kyo_h2o_proceed_fn fn) {
    server->proceed_fn = fn;
}

void kyo_h2o_set_stop(kyo_h2o_server *server, kyo_h2o_stop_fn fn) {
    server->stop_fn = fn;
}

/* ── Request accessors ─────────────────────────────────────────────── */

const char *kyo_h2o_req_method(h2o_req_t *req) {
    return req->method.base;
}

int kyo_h2o_req_method_len(h2o_req_t *req) {
    return (int)req->method.len;
}

const char *kyo_h2o_req_path(h2o_req_t *req) {
    return req->path.base;
}

int kyo_h2o_req_path_len(h2o_req_t *req) {
    return (int)req->path.len;
}

int kyo_h2o_req_query_at(h2o_req_t *req) {
    return (int)req->input.query_at;
}

int kyo_h2o_req_header_count(h2o_req_t *req) {
    return (int)req->headers.size;
}

const char *kyo_h2o_req_header_name(h2o_req_t *req, int index) {
    return req->headers.entries[index].name->base;
}

int kyo_h2o_req_header_name_len(h2o_req_t *req, int index) {
    return (int)req->headers.entries[index].name->len;
}

const char *kyo_h2o_req_header_value(h2o_req_t *req, int index) {
    return req->headers.entries[index].value.base;
}

int kyo_h2o_req_header_value_len(h2o_req_t *req, int index) {
    return (int)req->headers.entries[index].value.len;
}

const char *kyo_h2o_req_body(h2o_req_t *req) {
    return req->entity.base;
}

int kyo_h2o_req_body_len(h2o_req_t *req) {
    return (int)req->entity.len;
}

/* ── Buffered response ─────────────────────────────────────────────── */

void kyo_h2o_send_buffered(h2o_req_t *req, int status,
                            const char **header_names, int *header_name_lens,
                            const char **header_values, int *header_value_lens,
                            int header_count,
                            const char *body, int body_len) {
    static h2o_generator_t generator = {NULL, NULL};

    req->res.status = status;
    req->res.reason = "OK";

    /* Add response headers */
    for (int i = 0; i < header_count; i++) {
        h2o_add_header_by_str(
            &req->pool, &req->res.headers,
            header_names[i], header_name_lens[i],
            0, /* don't check for existing */
            NULL,
            header_values[i], header_value_lens[i]
        );
    }

    h2o_start_response(req, &generator);

    if (body && body_len > 0) {
        /* Copy body to pool memory so it outlives this call */
        char *body_copy = h2o_mem_alloc_pool(&req->pool, body_len);
        memcpy(body_copy, body, body_len);
        h2o_iovec_t buf = h2o_iovec_init(body_copy, body_len);
        h2o_send(req, &buf, 1, H2O_SEND_STATE_FINAL);
    } else {
        h2o_send(req, NULL, 0, H2O_SEND_STATE_FINAL);
    }
}

/* ── Error response (no headers, no body) ──────────────────────────── */

void kyo_h2o_send_error(h2o_req_t *req, int status,
                         const char **header_names, int *header_name_lens,
                         const char **header_values, int *header_value_lens,
                         int header_count) {
    static h2o_generator_t generator = {NULL, NULL};

    req->res.status = status;
    req->res.reason = "Error";

    for (int i = 0; i < header_count; i++) {
        h2o_add_header_by_str(
            &req->pool, &req->res.headers,
            header_names[i], header_name_lens[i],
            0, NULL,
            header_values[i], header_value_lens[i]
        );
    }

    h2o_start_response(req, &generator);
    h2o_send(req, NULL, 0, H2O_SEND_STATE_FINAL);
}

/* ── Streaming response ────────────────────────────────────────────── */

kyo_h2o_generator *kyo_h2o_start_streaming(
    kyo_h2o_server *server, h2o_req_t *req, int status,
    const char **header_names, int *header_name_lens,
    const char **header_values, int *header_value_lens,
    int header_count, int stream_id
) {
    kyo_h2o_generator *gen = h2o_mem_alloc_pool(
        &req->pool, sizeof(kyo_h2o_generator));
    gen->super.proceed = generator_proceed;
    gen->super.stop = generator_stop;
    gen->server = server;
    gen->req = req;
    gen->stream_id = stream_id;

    req->res.status = status;
    req->res.reason = "OK";

    for (int i = 0; i < header_count; i++) {
        h2o_add_header_by_str(
            &req->pool, &req->res.headers,
            header_names[i], header_name_lens[i],
            0, NULL,
            header_values[i], header_value_lens[i]
        );
    }

    h2o_start_response(req, &gen->super);
    /* Initial empty send to flush headers and start the generator cycle */
    h2o_send(req, NULL, 0, H2O_SEND_STATE_IN_PROGRESS);
    return gen;
}

void kyo_h2o_send_chunk(h2o_req_t *req, kyo_h2o_generator *gen,
                         const char *data, int len, int is_final) {
    h2o_send_state_t state = is_final
        ? H2O_SEND_STATE_FINAL
        : H2O_SEND_STATE_IN_PROGRESS;

    if (data && len > 0) {
        /* Copy to pool so buffer outlives this call */
        char *copy = h2o_mem_alloc_pool(&req->pool, len);
        memcpy(copy, data, len);
        h2o_iovec_t buf = h2o_iovec_init(copy, len);
        h2o_send(req, &buf, 1, state);
    } else {
        h2o_send(req, NULL, 0, state);
    }
}

/* ── Wake event loop from any thread ───────────────────────────────── */

void kyo_h2o_wake(kyo_h2o_server *server) {
    char c = 1;
    write(server->response_pipe[1], &c, 1);
}
