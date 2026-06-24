/*
 * kyo_aeron.c: fixed-arity shim over the Aeron 1.50.2 C client (aeronc.h) and
 * embedded media driver (aeronmd.h), statically linked into the kyo_aeron shared
 * library so the artifact carries no runtime libaeron dependency.
 *
 * The shim exposes publication/subscription registration as a non-blocking async start
 * plus a single-step poll (the wait loop lives in Scala Async, not a C busy-loop), keeps
 * the fragment-handler callback in C (fired
 * synchronously on the calling Scala carrier thread by aeron_subscription_poll),
 * uses the offer copy path (not tryClaim), and bundles each driver/client handle
 * with its context so the context outlives the handle close. The #if guard compiles
 * to nothing where the Aeron headers are absent, mirroring kyo_uring.c.
 */

#if __has_include(<aeronc.h>) && __has_include(<aeronmd.h>)

#include <aeronc.h>
#include <aeronmd.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#include "kyo_aeron.h"

/* Bundle structs keep each handle paired with its context.
 * The driver/client context must outlive the handle close (the context-lifetime requirement).
 *
 * The use-after-free this guards against (a NULL-deref crash in the Aeron close path):
 *
 * kyo_aeron_publication_close and kyo_aeron_subscription_close access raw Aeron
 * publication/subscription structs via aeron_publication_close / aeron_subscription_close.
 * When kyo_aeron_client_close calls aeron_close(client), the Aeron conductor on_close
 * path calls aeron_publication_delete / aeron_subscription_delete on every resource
 * that is still registered, freeing the raw structs.  If a concurrent or subsequent
 * close call (e.g. from a Kyo fiber's Sync.ensure cleanup that outlives the Topic.run
 * scope) then calls kyo_aeron_publication_close on the now-freed struct, it reads
 * pub->conductor = NULL from freed memory and crashes when the close path dereferences
 * fields reachable through that NULL conductor pointer.
 *
 * Close-vs-close protection: reference-counted client bundle + closing flag under close_mutex.
 * Add-vs-close protection: process-global registry (g_registry_mutex + linked list).
 *
 * -- Close-vs-close protection (close_mutex) --
 *
 * kyo_aeron_client_bundle carries a refcount (protected by g_registry_mutex; see
 * the add-vs-close section below):
 *   refcount = 1 initially (the client call site holds one ref)
 *   + 1 for each active publication or subscription bundle that references this client
 *
 * kyo_aeron_publication_close / kyo_aeron_subscription_close (after add* returned):
 *   1. Locks close_mutex.
 *   2. If closing=1: skip the aeron close call (Aeron already freed the struct).
 *      If closing=0: call aeron_publication_close / aeron_subscription_close
 *      safely, because the client is still open and the struct is still valid.
 *   3. Unlock close_mutex.
 *   4. Call client_bundle_release.
 *
 * -- Add-vs-close protection (g_registry_mutex + live-bundle registry) --
 *
 * kyo_aeron_async_add_publication / kyo_aeron_async_add_subscription receive a raw client pointer
 * whose bundle may have been freed by the time they dereference it.  Incrementing the
 * refcount by first locking the bundle's own close_mutex (pthread_mutex_lock(&c->close_mutex);
 * c->refcount++) would itself be a use-after-free: if client_bundle_release had already freed c,
 * &c->close_mutex points into freed memory.  Liveness must be verified before any field of c
 * is touched, which the registry guard does under a global lock.
 *
 * The registry guard:
 *   kyo_aeron_client_connect: inserts the new bundle into a global linked list under
 *     g_registry_mutex.  The list node is embedded in the bundle struct (prev/next).
 *   kyo_aeron_async_add_publication / add_subscription: acquire g_registry_mutex, scan the
 *     list to verify c is still live AND not already closing, increment c->refcount
 *     under g_registry_mutex (so no concurrent client_bundle_release can free c while
 *     we hold the global lock), release g_registry_mutex.  If c is not in the list or
 *     is closing, return NULL (signals a closed-client condition to the Scala layer,
 *     which maps it to TopicPublicationClosedException).
 *   client_bundle_release: acquire g_registry_mutex, decrement c->refcount; if 0,
 *     remove from the list, free c, release g_registry_mutex.
 *   kyo_aeron_client_close: acquire g_registry_mutex, set closing=1, release
 *     g_registry_mutex; call aeron_close + context_close; call client_bundle_release.
 *
 * This closes the add-vs-close race window: either add* holds g_registry_mutex and
 * sees the bundle in the live list (increments refcount before close can free it), or
 * client_bundle_release holds g_registry_mutex and removes/frees the bundle before
 * add* can increment (add* then returns NULL instead of dereferencing freed memory).
 *
 * Lock ordering: g_registry_mutex is always acquired BEFORE close_mutex.  Every site
 * that needs both acquires g_registry_mutex first.  No site acquires close_mutex and
 * then g_registry_mutex.  Verified per function below.
 *
 * The mutex is NOT held across slow aeron_close / aeron_publication_close calls to
 * avoid holding a global lock during potentially long conductor joins. */

typedef struct {
    aeron_driver_t*         driver;
    aeron_driver_context_t* ctx;
} kyo_aeron_driver_bundle;

/* Recording error slot for the non-exiting error handler (kyo_aeron_error_handler).
 *
 * The Aeron conductor calls the installed error handler on fatal conditions (driver timeout,
 * conductor service timeout, buffer full). The default handler calls exit(EXIT_FAILURE), which
 * kills the Native/JS process without giving the Scala layer a chance to surface a typed error.
 *
 * This struct is the recording target: allocated before aeron_context_set_error_handler (before
 * the bundle exists), stashed in b->err_slot after the bundle is malloc'd, and freed in
 * client_bundle_release when the bundle's refcount reaches 0. The slot outlives the conductor
 * thread because kyo_aeron_client_close calls aeron_close (which joins the conductor) before
 * client_bundle_release (which frees the slot).
 *
 * Synchronization: the conductor thread is the single writer; Scala carrier threads are the
 * readers (via kyo_aeron_has_client_error / kyo_aeron_client_error_msg). A brief mutex_t
 * guards the write path. The read path acquires the same mutex briefly.
 */
#define KYO_AERON_ERR_SLOT_MSG_SIZE 256

typedef struct {
    pthread_mutex_t mutex;
    volatile int    present;                      /* 1 after first error recorded, 0 initially */
    int             errcode;
    char            errmsg[KYO_AERON_ERR_SLOT_MSG_SIZE];
} kyo_aeron_error_slot;

/* Forward declarations so the client bundle can hold the heads of its per-client
 * publication- and subscription-bundle lists (the free-deferral registries; see the
 * publication / subscription bundle structs and the *_close functions below). */
struct kyo_aeron_publication_bundle_stct;
struct kyo_aeron_subscription_bundle_stct;

typedef struct kyo_aeron_client_bundle_stct {
    aeron_t*          client;
    aeron_context_t*  ctx;
    volatile int      closing;  /* set to 1 before aeron_close is invoked */
    pthread_mutex_t   close_mutex;
    int               refcount;  /* protected by g_registry_mutex */
    /* Recording error slot installed as the Aeron error handler clientd.
     * Allocated before aeron_context_set_error_handler (before the bundle exists),
     * stashed here after the bundle is malloc'd, freed in client_bundle_release. */
    kyo_aeron_error_slot* err_slot;
    /* Intrusive doubly-linked list for the global live-bundle registry. */
    struct kyo_aeron_client_bundle_stct* prev;
    struct kyo_aeron_client_bundle_stct* next;
    /* Head of the per-client publication-bundle list (free-deferral).
     * Guarded by close_mutex. Publication bundles are inserted at add-publication
     * completion (kyo_aeron_async_add_publication_get) and freed either by their own
     * kyo_aeron_publication_close (when the client is already closing) or by the
     * client-close sweep (when the caller closed the publication while the client was
     * still open and the free was deferred). See kyo_aeron_client_close. */
    struct kyo_aeron_publication_bundle_stct* pub_list_head;
    /* Head of the per-client subscription-bundle list (free-deferral), symmetric to
     * pub_list_head. Guarded by close_mutex. Subscription bundles are inserted at
     * kyo_aeron_async_add_subscription_get and freed either by their own
     * kyo_aeron_subscription_close (when the client is already closing) or by the
     * client-close sweep (when the caller closed the subscription while the client was
     * still open and the free was deferred). See kyo_aeron_client_close. */
    struct kyo_aeron_subscription_bundle_stct* sub_list_head;
} kyo_aeron_client_bundle;

/* Publication bundle: pairs the raw Aeron publication with a reference to its
 * client bundle so kyo_aeron_publication_close can check the closing flag under
 * the client mutex before accessing the (possibly freed) Aeron struct. */
typedef struct kyo_aeron_publication_bundle_stct {
    aeron_publication_t*     publication;
    kyo_aeron_client_bundle* client;
    /* Free-deferral guard: set to 1 under client->close_mutex by
     * kyo_aeron_publication_close. offer / is_connected / max_message_length check it
     * first (under close_mutex) and return the existing -4 / 0 without dereferencing the
     * dead Aeron handle, matching the JVM refcount-lingering behavior. The bundle memory
     * stays valid past close so a subsequent offer is provably safe; the free is owned by
     * the client-close sweep (caller closed while the client was open) or by
     * publication_close itself (client already closing). */
    volatile int             closed;
    /* Intrusive singly-linked list node for the per-client publication-bundle list
     * (head in kyo_aeron_client_bundle.pub_list_head). Guarded by client->close_mutex. */
    struct kyo_aeron_publication_bundle_stct* pub_next;
} kyo_aeron_publication_bundle;

/* Per-client publication-bundle list helpers (free-deferral).
 *
 * Both operate on kyo_aeron_client_bundle.pub_list_head and MUST be called with the
 * client bundle's close_mutex held (the client-close sweep traverses the list inline
 * under the same lock). The list is singly linked through pub_next: insertion is at the
 * head; removal scans for the node. This list never violates the lock order
 * (g_registry_mutex < close_mutex): every caller holds ONLY close_mutex, never
 * g_registry_mutex, while touching the list. */
static void pub_list_insert(kyo_aeron_client_bundle* c, kyo_aeron_publication_bundle* b)
{
    b->pub_next = c->pub_list_head;
    c->pub_list_head = b;
}

/* Removes b from c's publication list if present. No-op if b is not in the list
 * (e.g. it was inserted after the client-close sweep already cleared the list). */
static void pub_list_remove(kyo_aeron_client_bundle* c, kyo_aeron_publication_bundle* b)
{
    kyo_aeron_publication_bundle** link = &c->pub_list_head;
    while (*link != NULL) {
        if (*link == b) {
            *link = b->pub_next;
            b->pub_next = NULL;
            return;
        }
        link = &(*link)->pub_next;
    }
}

/* Reassembly slot sizing.
 *
 * KYO_AERON_SLOT_INITIAL_CAP: the slot starts small so a subscription that only
 *   ever sees small messages pays a small fixed off-heap cost.  64 KiB comfortably
 *   holds an unfragmented IPC message and most multi-fragment messages without a
 *   single realloc.
 * KYO_AERON_SLOT_MAX_CAP: AERON_FRAME_MAX_MESSAGE_LENGTH (aeron_udp_protocol.h) =
 *   16 MiB, the protocol-enforced hard ceiling.  No Aeron publication can send a
 *   single logical message larger than this regardless of term buffer configuration.
 *   The slot grows toward this ceiling only when a larger message arrives,
 *   matching the JVM FragmentAssembler's grow-on-demand profile.  A message that
 *   would exceed the ceiling is rejected (slot_len = -2). */
#define KYO_AERON_SLOT_INITIAL_CAP (64 * 1024)
#define KYO_AERON_SLOT_MAX_CAP     (16 * 1024 * 1024)

/* Subscription bundle: analogous to the publication bundle. Adds the fragment
 * assembler and single-slot reassembly buffer so the C fragment handler can write
 * into them synchronously. The client back-reference enables the same closing-guard
 * that prevents subscription close from accessing freed memory.
 *
 * The slot grows on demand: it starts at KYO_AERON_SLOT_INITIAL_CAP and the
 * fragment handler reallocs it (up to KYO_AERON_SLOT_MAX_CAP) when a reassembled
 * message exceeds the current capacity.  pending=1 marks a reassembled message
 * that has been written to the slot but not yet copied out to the caller (the
 * caller's dst buffer was too small on the previous poll); the next poll copies it
 * out without consuming another message from Aeron. */
typedef struct kyo_aeron_subscription_bundle_stct {
    aeron_subscription_t*       subscription;
    aeron_fragment_assembler_t* assembler;
    uint8_t*                    slot;
    int32_t                     slot_len; /* -1 = empty, -2 = overflow, >=0 = byte count */
    int32_t                     slot_cap; /* current slot capacity; grows on demand */
    int32_t                     pending;  /* 1 = slot holds a message not yet copied out */
    kyo_aeron_client_bundle*    client;
    /* Free-deferral guard, symmetric to the publication bundle's `closed`: set to 1 under
     * client->close_mutex by kyo_aeron_subscription_close. poll / is_connected check it first
     * (under close_mutex) and return 0 without dereferencing the dead Aeron handle. The bundle
     * memory stays valid past close so a subsequent poll is provably safe; the free is owned by
     * the client-close sweep (caller closed while the client was open) or by subscription_close
     * itself (client already closing). */
    volatile int                closed;
    /* Intrusive singly-linked list node for the per-client subscription-bundle list
     * (head in kyo_aeron_client_bundle.sub_list_head). Guarded by client->close_mutex. */
    struct kyo_aeron_subscription_bundle_stct* sub_next;
} kyo_aeron_subscription_bundle;
/* Single-caller contract: slot, slot_len, slot_cap, and pending are
 * accessed without synchronization. kyo_aeron_subscription_poll MUST NOT be called
 * concurrently on the same sub pointer; the Scala FfiAeronTransport upholds this by
 * giving each Topic.stream subscriber its own subscription handle. */

/* Per-client subscription-bundle list helpers (free-deferral). Same contract and lock
 * discipline as the publication helpers above: both MUST be called with the client
 * bundle's close_mutex held; the list is singly linked through sub_next with head
 * insertion. Defined here (after the struct) so the full type is visible. */
static void sub_list_insert(kyo_aeron_client_bundle* c, kyo_aeron_subscription_bundle* b)
{
    b->sub_next = c->sub_list_head;
    c->sub_list_head = b;
}

static void sub_list_remove(kyo_aeron_client_bundle* c, kyo_aeron_subscription_bundle* b)
{
    kyo_aeron_subscription_bundle** link = &c->sub_list_head;
    while (*link != NULL) {
        if (*link == b) {
            *link = b->sub_next;
            b->sub_next = NULL;
            return;
        }
        link = &(*link)->sub_next;
    }
}

/* Async add token structs.
 *
 * The add path is split into a non-blocking start and a single-step poll so the Scala
 * carrier thread is never monopolized and the fiber stays interruptible (a single C-side
 * for(;;) busy-loop until Aeron confirms the registration would do neither):
 *   kyo_aeron_async_add_publication (start): acquires the registry guard, kicks off
 *     aeron_async_add_publication, and returns a heap-allocated token immediately.
 *   kyo_aeron_async_add_publication_poll (one step): calls aeron_async_add_publication_poll
 *     once and returns > 0 (done), 0 (awaiting), or < 0 (error; token NOT freed, see ownership rules).
 *     On the > 0 return Aeron writes the completed publication pointer into the poll
 *     out-param AND frees the async handle (aeron_async_cmd_free, see aeron_client.c
 *     AERON_CLIENT_REGISTERED_MEDIA_DRIVER). We MUST capture that pointer here, at the
 *     > 0 return, and stash it in the token; the async handle is consumed and cannot be
 *     polled again.
 *   kyo_aeron_async_add_publication_get (retrieve): returns the pointer stashed by _poll
 *     and frees the token. It does NOT re-poll (the async handle was already freed).
 *   kyo_aeron_async_add_publication_free (interrupt cleanup): frees the token and
 *     releases the client refcount when the Scala Fiber is interrupted mid-poll.
 *
 * Ownership rules:
 *   After start returns a non-NULL token, the Scala side owns it.
 *   _poll > 0: caller must call _get next. _get frees the token on success (the refcount
 *              transfers to the returned bundle); on NULL (alloc failure) it closes the handle and
 *              leaves the token alive, so the caller calls _free exactly once (as for the < 0 path).
 *   _poll < 0: token still alive; Scala reads err_code/err_msg via the accessors, then
 *              calls _free exactly once (C does NOT free it). Scala MUST NOT call _get.
 *   _poll 0:   token still alive; Scala calls Async.sleep then _poll again.
 *   interrupt: Scala calls _free exactly once; C frees the token and releases refcount.
 *
 * The err_code / err_msg fields start as stubs (0 / ""). They are filled at the
 * _poll < 0 site so the Scala layer can surface a TopicRegistrationFailedException with
 * driver detail without a struct layout change. */

#define KYO_AERON_ERR_MSG_SIZE 256

typedef struct {
    aeron_async_add_publication_t* async;
    aeron_publication_t*           pub;      /* stashed by _poll at the > 0 return; read by _get */
    kyo_aeron_client_bundle*       client;
    int                            err_code;              /* 0 until _poll < 0 fills it */
    char                           err_msg[KYO_AERON_ERR_MSG_SIZE]; /* "" until _poll < 0 fills it */
} kyo_aeron_async_pub_token;

typedef struct {
    aeron_async_add_subscription_t* async;
    aeron_subscription_t*           sub;     /* stashed by _poll at the > 0 return; read by _get */
    kyo_aeron_client_bundle*        client;
    int                             err_code;
    char                            err_msg[KYO_AERON_ERR_MSG_SIZE];
} kyo_aeron_async_sub_token;

/* Process-global registry of live client bundles.
 *
 * g_registry_mutex serializes: (1) insertion at client_connect, (2) removal at
 * client_bundle_release when refcount reaches 0, (3) the liveness check + refcount
 * increment in add_publication / add_subscription.
 *
 * All callers of g_registry_mutex hold it for a short critical section only;
 * no slow Aeron API is called while the global lock is held.
 * pthread_once guarantees g_registry_mutex is initialized exactly once. */
static pthread_mutex_t  g_registry_mutex;
static pthread_once_t   g_registry_once = PTHREAD_ONCE_INIT;
static kyo_aeron_client_bundle* g_registry_head = NULL;

static void registry_init_once(void)
{
    pthread_mutex_init(&g_registry_mutex, NULL);
}

/* Called under g_registry_mutex: inserts b at the head of the list. */
static void registry_insert(kyo_aeron_client_bundle* b)
{
    b->prev = NULL;
    b->next = g_registry_head;
    if (g_registry_head != NULL)
        g_registry_head->prev = b;
    g_registry_head = b;
}

/* Called under g_registry_mutex: removes b from the list (b must be in it). */
static void registry_remove(kyo_aeron_client_bundle* b)
{
    if (b->prev != NULL)
        b->prev->next = b->next;
    else
        g_registry_head = b->next;
    if (b->next != NULL)
        b->next->prev = b->prev;
    b->prev = NULL;
    b->next = NULL;
}

/* Called under g_registry_mutex: returns 1 if c is in the live list, 0 otherwise. */
static int registry_is_live(kyo_aeron_client_bundle* c)
{
    kyo_aeron_client_bundle* cur = g_registry_head;
    while (cur != NULL) {
        if (cur == c) return 1;
        cur = cur->next;
    }
    return 0;
}

/* Decrement refcount under g_registry_mutex; free the bundle and remove it from
 * the registry when refcount reaches zero.
 *
 * Must be called once per publication/subscription bundle release AND once by
 * kyo_aeron_client_close to release the client's own reference. */
static void client_bundle_release(kyo_aeron_client_bundle* b)
{
    int should_free;
    pthread_mutex_lock(&g_registry_mutex);
    b->refcount--;
    should_free = (b->refcount == 0);
    if (should_free)
        registry_remove(b);
    pthread_mutex_unlock(&g_registry_mutex);
    if (should_free) {
        pthread_mutex_destroy(&b->close_mutex);
        /* Free the error slot BEFORE the bundle. The slot is a separate malloc
         * stashed in b->err_slot. Free under a local pointer in case b is reused. */
        if (b->err_slot != NULL) {
            pthread_mutex_destroy(&b->err_slot->mutex);
            free(b->err_slot);
        }
        free(b);
    }
}

/* Fragment handler: C-private (static), fires synchronously inside aeron_subscription_poll
 * on the calling thread. Copies the reassembled message into the shim slot.
 * No Scala upcall. The header param is unused by design;
 * the suppression avoids -Wunused-parameter. */
static void kyo_aeron_fragment_handler(
    void* clientd, const uint8_t* buffer, size_t length, aeron_header_t* header)
{
    (void)header;
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)clientd;
    /* A message exceeding the 16 MiB protocol ceiling is undeliverable: Aeron
     * cannot have sent it, so this only fires defensively.  Reject with overflow. */
    if (length > (size_t)KYO_AERON_SLOT_MAX_CAP) { b->slot_len = -2; return; }
    /* Grow the slot on demand toward the ceiling when the message does not fit the
     * current capacity.  realloc preserves no useful data here (the slot is rewritten
     * below), but keeps a single ownership pointer the close path can free. */
    if (length > (size_t)b->slot_cap) {
        int32_t new_cap = b->slot_cap;
        while ((size_t)new_cap < length) new_cap *= 2;
        if (new_cap > KYO_AERON_SLOT_MAX_CAP) new_cap = KYO_AERON_SLOT_MAX_CAP;
        uint8_t* grown = (uint8_t*)realloc(b->slot, (size_t)new_cap);
        if (grown == NULL) { b->slot_len = -2; return; } /* keep old slot; report overflow */
        b->slot     = grown;
        b->slot_cap = new_cap;
    }
    memcpy(b->slot, buffer, length);
    b->slot_len = (int32_t)length;  /* safe: length <= slot_cap <= KYO_AERON_SLOT_MAX_CAP <= INT32_MAX */
}

void* kyo_aeron_driver_start(const char* dir)
{
    aeron_driver_context_t* ctx = NULL;
    if (aeron_driver_context_init(&ctx) < 0) return NULL;
    if (dir != NULL) aeron_driver_context_set_dir(ctx, dir);
    /* Delete the Aeron directory on start so successive embedded() calls always
     * initialize from a clean state (no stale CnC file from a prior driver).
     * dir_delete_on_shutdown is intentionally NOT set: see kyo_aeron_driver_close. */
    aeron_driver_context_set_dir_delete_on_start(ctx, true);
    aeron_driver_t* driver = NULL;
    if (aeron_driver_init(&driver, ctx) < 0) {
        aeron_driver_context_close(ctx);
        return NULL;
    }
    /* aeron_driver_start(driver, false): false = no manual pump; spawns DEDICATED
     * conductor/sender/receiver C pthreads. DEDICATED is the default after
     * aeron_driver_context_init; set_threading_mode is not called. */
    if (aeron_driver_start(driver, false) < 0) {
        aeron_driver_close(driver);
        aeron_driver_context_close(ctx);
        return NULL;
    }
    kyo_aeron_driver_bundle* b = (kyo_aeron_driver_bundle*)malloc(sizeof(kyo_aeron_driver_bundle));
    if (b == NULL) {
        aeron_driver_close(driver);
        aeron_driver_context_close(ctx);
        return NULL;
    }
    b->driver = driver;
    b->ctx    = ctx;
    return b;
}

void kyo_aeron_driver_close(void* driver)
{
    kyo_aeron_driver_bundle* b = (kyo_aeron_driver_bundle*)driver;
    if (b == NULL) return;
    /* Close handle before context.
     * aeron_driver_close joins the conductor/sender/receiver pthreads.
     * aeron_driver_context_close frees the context struct after the driver is stopped. */
    aeron_driver_close(b->driver);
    aeron_driver_context_close(b->ctx);
    free(b);
}

/* Non-exiting recording error handler.
 *
 * Installed via aeron_context_set_error_handler BEFORE aeron_init. Replaces the Aeron
 * default handler (aeron_context.c) which calls exit(EXIT_FAILURE) on fatal errors.
 *
 * This handler records the first fatal error into the kyo_aeron_error_slot (clientd) under
 * a brief mutex and returns. No exit(), no abort(), no sleep/spin. The Scala layer polls
 * the slot at the offer/poll boundary and surfaces it as TopicTransportFailedException(detail).
 *
 * The handler is called by the Aeron conductor thread (single writer). Multiple Scala carrier
 * threads may read the slot concurrently via kyo_aeron_has_client_error (multi-reader). The
 * mutex guards the write path; reads are mutex-protected to observe a consistent present+errmsg.
 */
static void kyo_aeron_error_handler(void* clientd, int errcode, const char* message)
{
    kyo_aeron_error_slot* slot = (kyo_aeron_error_slot*)clientd;
    if (slot == NULL) return;
    pthread_mutex_lock(&slot->mutex);
    if (!slot->present) {
        /* Record only the first fatal error (the one that caused the conductor to terminate). */
        slot->errcode  = errcode;
        if (message != NULL) {
            strncpy(slot->errmsg, message, KYO_AERON_ERR_SLOT_MSG_SIZE - 1);
            slot->errmsg[KYO_AERON_ERR_SLOT_MSG_SIZE - 1] = '\0';
        } else {
            slot->errmsg[0] = '\0';
        }
        /* present flips 0->1 exactly once here under the mutex; errcode/errmsg are written
         * before it and never modified again while present == 1. That write-once invariant is
         * what lets kyo_aeron_client_error_msg hand back the internal errmsg pointer after it
         * releases the mutex (the bytes can no longer change). */
        slot->present = 1;
    }
    pthread_mutex_unlock(&slot->mutex);
    /* MUST NOT call exit(), abort(), or block. The Scala layer surfaces this as a typed error. */
}

void* kyo_aeron_client_connect(const char* dir)
{
    pthread_once(&g_registry_once, registry_init_once);
    aeron_context_t* ctx = NULL;
    if (aeron_context_init(&ctx) < 0) return NULL;
    if (dir != NULL) aeron_context_set_dir(ctx, dir);
    /* Allocate the error slot before installing the handler (the bundle is not
     * malloc'd yet; the slot is the clientd pointer passed to the handler). */
    kyo_aeron_error_slot* slot = (kyo_aeron_error_slot*)malloc(sizeof(kyo_aeron_error_slot));
    if (slot == NULL) {
        aeron_context_close(ctx);
        return NULL;
    }
    pthread_mutex_init(&slot->mutex, NULL);
    slot->present  = 0;
    slot->errcode  = 0;
    slot->errmsg[0] = '\0';
    /* Install the non-exiting recording handler BEFORE aeron_init (which spawns the conductor
     * thread that may call the handler). aeron_context_set_error_handler returns -1 only on
     * NULL context (ctx is non-NULL here; checked by aeron_context_init above). */
    if (aeron_context_set_error_handler(ctx, kyo_aeron_error_handler, slot) < 0) {
        pthread_mutex_destroy(&slot->mutex);
        free(slot);
        aeron_context_close(ctx);
        return NULL;
    }
    aeron_t* client = NULL;
    if (aeron_init(&client, ctx) < 0) {
        pthread_mutex_destroy(&slot->mutex);
        free(slot);
        aeron_context_close(ctx);
        return NULL;
    }
    if (aeron_start(client) < 0) {
        aeron_close(client);
        pthread_mutex_destroy(&slot->mutex);
        free(slot);
        aeron_context_close(ctx);
        return NULL;
    }
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)malloc(sizeof(kyo_aeron_client_bundle));
    if (b == NULL) {
        aeron_close(client);
        pthread_mutex_destroy(&slot->mutex);
        free(slot);
        aeron_context_close(ctx);
        return NULL;
    }
    b->client        = client;
    b->ctx           = ctx;
    b->closing       = 0;
    b->refcount      = 1; /* client call site holds one reference */
    b->err_slot      = slot; /* stash the slot for accessor and cleanup */
    b->prev          = NULL;
    b->next          = NULL;
    b->pub_list_head = NULL; /* no publications registered yet (free-deferral list) */
    b->sub_list_head = NULL; /* no subscriptions registered yet (free-deferral list) */
    pthread_mutex_init(&b->close_mutex, NULL);
    /* Register in the live-bundle registry before returning.
     * From this point forward, add_publication/add_subscription can safely
     * increment refcount under g_registry_mutex. */
    pthread_mutex_lock(&g_registry_mutex);
    registry_insert(b);
    pthread_mutex_unlock(&g_registry_mutex);
    return b;
}

void kyo_aeron_client_close(void* client)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL) return;
    /* Signal the closing transition under g_registry_mutex.
     * Any concurrent add_publication/add_subscription that holds g_registry_mutex
     * first will either:
     *   (a) see b in the registry and not yet closing, increment refcount, and
     *       proceed (so their close paths will later call client_bundle_release), or
     *   (b) be waiting for g_registry_mutex while we set closing=1 here, and then
     *       observe closing=1 and return NULL.
     * After we release g_registry_mutex, no new add* can increment the refcount
     * because closing=1 causes add* to return NULL without touching refcount.
     *
     * We do NOT remove the bundle from the registry here: it stays in the registry
     * until client_bundle_release sees refcount==0 (which happens only after all
     * publication/subscription refs have also been released).  This is safe: add*
     * observes closing=1 and returns NULL without touching the bundle further. */
    pthread_mutex_lock(&g_registry_mutex);
    b->closing = 1;
    pthread_mutex_unlock(&g_registry_mutex);
    /* Also signal under close_mutex so publication_close/subscription_close (which
     * hold a ref and use close_mutex) can observe the closing state. */
    pthread_mutex_lock(&b->close_mutex);
    /* closing=1 already set above; this acquire+release flushes the write to
     * threads that observe close_mutex, providing a happens-before for any
     * publication/subscription close that checks closing under close_mutex. */
    pthread_mutex_unlock(&b->close_mutex);
    /* Close handle before context. */
    aeron_close(b->client);
    aeron_context_close(b->ctx);
    /* free-deferral sweep: free the publication AND subscription bundles whose caller closed
     * them while the client was still open (closed == 1) and whose free was therefore deferred
     * to here. Both per-client lists (pub_list_head, sub_list_head) are swept below.
     *
     * Runs AFTER aeron_close returns: the conductor's on_close path has already freed every
     * inner aeron_publication_t / aeron_subscription_t, so the sweep MUST NOT call
     * aeron_publication_close / aeron_subscription_close on any handle; it frees only the bundle
     * struct (and, for subscriptions, the client-side assembler + slot). closing == 1 here (set
     * above), the happens-before that makes offer/poll/is_connected return the safe sentinel for
     * any bundle still held.
     *
     * Free-ownership: a bundle with closed == 1 was closed by its caller (deferral) and its
     * client refcount was already released in kyo_aeron_publication_close / _subscription_close;
     * the sweep is its sole free-owner. A bundle with closed == 0 has not yet been closed by its
     * caller; a later kyo_aeron_publication_close / _subscription_close (which will observe
     * closing == 1) is its free-owner, so the sweep leaves it in the list, alive (its still-held
     * refcount keeps this client bundle alive past client_bundle_release below). This split
     * guarantees exactly one free per bundle.
     *
     * Lock order: only close_mutex is held here; g_registry_mutex is NOT held (it was released
     * after setting closing = 1 above). The sweep never calls client_bundle_release (which takes
     * g_registry_mutex), so g_registry_mutex < close_mutex is preserved. */
    pthread_mutex_lock(&b->close_mutex);
    kyo_aeron_publication_bundle** link = &b->pub_list_head;
    while (*link != NULL) {
        kyo_aeron_publication_bundle* pb = *link;
        if (pb->closed) {
            *link = pb->pub_next; /* unlink */
            free(pb);
        } else {
            link = &pb->pub_next; /* leave for its own publication_close to free */
        }
    }
    /* Same free-deferral sweep for subscription bundles: free those whose caller closed them
     * while the client was open (closed == 1, free deferred to here), freeing the client-side
     * assembler + slot + bundle. The inner aeron_subscription_t was already freed (at the
     * caller's close while the client was open, or by the conductor on_close above), so the
     * sweep MUST NOT call aeron_subscription_close. A closed == 0 entry is left for its own
     * kyo_aeron_subscription_close (which will observe closing == 1) to free, exactly as on the
     * publication side. */
    kyo_aeron_subscription_bundle** slink = &b->sub_list_head;
    while (*slink != NULL) {
        kyo_aeron_subscription_bundle* sb = *slink;
        if (sb->closed) {
            *slink = sb->sub_next; /* unlink */
            aeron_fragment_assembler_delete(sb->assembler);
            free(sb->slot);
            free(sb);
        } else {
            slink = &sb->sub_next; /* leave for its own subscription_close to free */
        }
    }
    pthread_mutex_unlock(&b->close_mutex);
    /* Release the client call site's own reference.  If all publication and
     * subscription references have also been released, this frees the bundle. */
    client_bundle_release(b);
}

/* Async publication add (start step).
 *
 * Acquires the registry guard, kicks off aeron_async_add_publication, and returns
 * an opaque token immediately without looping. The Scala side drives the polling via
 * kyo_aeron_async_add_publication_poll.
 *
 * Returns NULL if the client bundle is not live / closing, or if
 * aeron_async_add_publication itself fails immediately. */
void* kyo_aeron_async_add_publication(void* client, const char* uri, int32_t stream_id)
{
    kyo_aeron_client_bundle* c = (kyo_aeron_client_bundle*)client;
    /* Guard the add-vs-close race: verify c is still live in the registry and not
     * closing, then atomically increment its refcount, all under g_registry_mutex.
     * If c is not in the live registry or is already closing, return NULL (signals
     * a closed-client condition; Scala maps NULL/Absent to TopicPublicationClosedException). */
    pthread_mutex_lock(&g_registry_mutex);
    if (!registry_is_live(c) || c->closing) {
        pthread_mutex_unlock(&g_registry_mutex);
        return NULL;
    }
    c->refcount++;
    pthread_mutex_unlock(&g_registry_mutex);

    /* Registry-to-close-mutex gap: after g_registry_mutex is released, a racing
     * kyo_aeron_client_close can call aeron_close(c->client) and free the inner aeron_t
     * between the registry check above and the c->client use below (a TOCTOU race).
     * Guard the c->client use under close_mutex: if closing, release the refcount and
     * return NULL (Scala maps NULL/Absent to TopicPublicationClosedException). Lock ordering
     * preserved: g_registry_mutex was acquired and released first; close_mutex is acquired
     * only now (g_registry_mutex BEFORE close_mutex, the documented order). The mutex
     * wraps only the closing read and the non-blocking aeron_async_add_publication start
     * call, never aeron_close. */
    pthread_mutex_lock(&c->close_mutex);
    if (c->closing) {
        pthread_mutex_unlock(&c->close_mutex);
        client_bundle_release(c);
        return NULL;
    }
    aeron_async_add_publication_t* async_handle = NULL;
    int add_rc = aeron_async_add_publication(&async_handle, c->client, uri, stream_id);
    pthread_mutex_unlock(&c->close_mutex);
    if (add_rc < 0) {
        client_bundle_release(c);
        return NULL;
    }
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)malloc(sizeof(kyo_aeron_async_pub_token));
    if (tok == NULL) {
        /* aeron_async_add_publication allocated async_handle internally; the conductor
         * owns and reclaims it on registration failure or cancellation.  The C Aeron
         * client does not expose a cancel-before-completion call, so we leave async_handle
         * to the conductor (the registration is cancelled by the absence of a get call).
         * This is a defensive best-effort cleanup; in practice malloc failure is not
         * expected on embedded targets. */
        client_bundle_release(c);
        return NULL;
    }
    tok->async    = async_handle;
    tok->pub      = NULL;
    tok->client   = c;
    tok->err_code = 0;
    tok->err_msg[0] = '\0';
    return tok;
}

/* Async publication poll (one step, non-blocking).
 *
 * Returns:
 *   > 0  : registration complete; call kyo_aeron_async_add_publication_get next.
 *   0    : still awaiting; call again after a Scala-side Async.sleep.
 *   < 0  : registration failed; token NOT freed internally; read err_code/err_msg via the
 *           accessor functions, then call kyo_aeron_async_add_publication_free to release.
 *
 * NOTE: no close_mutex guard is needed here. tok->async is an independent heap allocation
 * that aeron_close does not free (it frees only the conductor's pointer array, not the async
 * token structs); a concurrent close leaves the token alive (leaked), never freed under us. */
long kyo_aeron_async_add_publication_poll(void* async_token)
{
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)async_token;
    aeron_publication_t* pub = NULL;
    int r = aeron_async_add_publication_poll(&pub, tok->async);
    if (r < 0) {
        /* Registration failed. aeron_async_add_publication_poll already called
         * aeron_async_cmd_free(async) on the < 0 path (setting TLS errcode/errmsg via
         * AERON_SET_ERR before freeing), so tok->async is now dangling. Clear it, then
         * capture errcode/errmsg from TLS immediately (must happen before any subsequent
         * Aeron call on this thread that could overwrite TLS errcode/errmsg). The token
         * is NOT freed here; the Scala layer reads err_code/err_msg via the accessor
         * functions, then calls _free (kyo_aeron_async_add_publication_free) which
         * releases the client refcount and frees the token. Do NOT call
         * client_bundle_release here to avoid a double-release when _free is called. */
        tok->async = NULL;
        tok->err_code = aeron_errcode();
        const char* msg = aeron_errmsg();
        strncpy(tok->err_msg, msg ? msg : "", KYO_AERON_ERR_MSG_SIZE - 1);
        tok->err_msg[KYO_AERON_ERR_MSG_SIZE - 1] = '\0';
        return -1;
    }
    if (r > 0) {
        /* Registration succeeded. aeron_async_add_publication_poll wrote the completed
         * publication into `pub` AND freed the async handle (aeron_async_cmd_free; see the
         * AERON_CLIENT_REGISTERED_MEDIA_DRIVER case in aeron_client.c). The async handle is
         * now consumed and must never be polled again, so we stash the pointer in the token
         * for kyo_aeron_async_add_publication_get to return without a second poll. */
        tok->pub   = pub;
        tok->async = NULL;
        return 1;
    }
    /* Still awaiting (r == 0). */
    return 0;
}

/* Retrieve the completed publication handle after _poll returned > 0.
 *
 * Returns the publication pointer that _poll stashed in the token at the > 0 return,
 * wrapped in a freshly allocated bundle, and frees the token on success (the client refcount
 * transfers to the bundle). It does NOT re-poll: aeron_async_add_publication_poll already freed
 * the async handle when it returned 1, so polling tok->async again would be a use-after-free that
 * returns no pointer.
 *
 * The caller owns the returned bundle and MUST eventually call kyo_aeron_publication_close. On
 * malloc failure it closes the handle and returns NULL, leaving the token ALIVE so the caller's
 * single _free releases the refcount and frees it (no double-free). */
void* kyo_aeron_async_add_publication_get(void* async_token)
{
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)async_token;
    aeron_publication_t* pub = tok->pub; /* stashed by _poll at the > 0 return */
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)malloc(sizeof(kyo_aeron_publication_bundle));
    if (b == NULL) {
        /* Bundle alloc failed: close the handle but leave the token ALIVE so the caller's single
         * _free (the closed-client Failed arm) releases the client refcount and frees the token
         * exactly once. Freeing here too would double-free / double-release. */
        aeron_publication_close(pub, NULL, NULL);
        return NULL;
    }
    b->publication = pub;
    b->client      = tok->client;
    b->closed      = 0;       /* malloc does not zero-init; free-deferral flag starts clear */
    b->pub_next    = NULL;
    /* Register the bundle in its client's per-client publication list (free-deferral).
     * Done under close_mutex: this is the same lock the client-close sweep and
     * kyo_aeron_publication_close hold when touching the list, so insertion is mutually
     * exclusive with both. The publication bundle holds a refcount on the client bundle
     * (taken in _start, owned here), so close_mutex is valid to acquire and the client
     * bundle stays alive. Lock order preserved: g_registry_mutex was acquired and released
     * back in _start; only close_mutex is held now (g_registry_mutex BEFORE close_mutex). */
    pthread_mutex_lock(&b->client->close_mutex);
    pub_list_insert(b->client, b);
    pthread_mutex_unlock(&b->client->close_mutex);
    /* The client refcount was incremented in _start and is now owned by the publication
     * bundle (released in kyo_aeron_publication_close). The token itself is released here. */
    free(tok);
    return b;
}

/* Free the async token on fiber interrupt (ensure path).
 *
 * Called by the Scala Sync.ensure handler when the Kyo Fiber is cancelled while
 * the poll loop is still in the Awaiting state.  The Aeron conductor owns tok->async
 * and will eventually complete or fail the registration; we release the client
 * refcount and free the token. */
void kyo_aeron_async_add_publication_free(void* async_token)
{
    if (async_token == NULL) return;
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)async_token;
    client_bundle_release(tok->client);
    free(tok);
}

/* Close-mutex guard: every inner-handle deref runs under close_mutex with a closing check,
 * so a concurrent kyo_aeron_client_close (which calls aeron_close and frees the inner Aeron
 * handle) cannot free the handle out from under the deref. Either we acquire
 * close_mutex while the client is still open and the handle is valid, or we observe
 * closing=1 and return the EXISTING safe sentinel without touching the freed handle.
 * The critical section wraps ONLY the closing read and the non-blocking Aeron call; it
 * NEVER wraps aeron_close (which stays outside any held lock in kyo_aeron_client_close),
 * so the brief-critical-section rule holds. The client bundle (and its close_mutex) stays
 * alive for this call: on a concurrent client close the publication bundle's own refcount
 * holds it; on the offer-after-own-close path (where kyo_aeron_publication_close already
 * released that refcount) the client's own outstanding refcount holds it, since the runtime
 * closes only after every offer (the Scala-layer ordering: offer before closePublication
 * before client close). */
int kyo_aeron_publication_is_connected(void* pub)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    pthread_mutex_lock(&b->client->close_mutex);
    /* b->closed guards the caller's-own-close path (offer-after-own-close), the same
     * way client->closing guards the client-close path; both return the not-connected
     * sentinel without dereferencing the dead Aeron handle. */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0; /* not connected: Scala treats this as the not-connected/backpressure path */
    }
    int r = aeron_publication_is_connected(b->publication) ? 1 : 0;
    pthread_mutex_unlock(&b->client->close_mutex);
    return r;
}

long kyo_aeron_publication_offer(void* pub, const uint8_t* buffer, int32_t length)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    /* Close-mutex guard: see kyo_aeron_publication_is_connected. */
    pthread_mutex_lock(&b->client->close_mutex);
    /* b->closed (the caller's own publication_close marked this bundle closed but
     * deferred its free) returns the EXISTING -4 sentinel, matching the JVM where a closed
     * io.aeron.Publication.offer returns Publication.CLOSED (-4) without faulting. Never a
     * new sentinel value: -4 maps through AeronSentinels.Closed to TopicPublicationClosedException. */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return (long)AERON_PUBLICATION_CLOSED; /* -4 -> AeronSentinels.Closed -> TopicPublicationClosedException */
    }
    /* Raw int64_t sentinel values pass through unchanged; the Scala side maps them
     * via AeronSentinels. reserved_value_supplier and clientd are NULL. */
    long r = (long)aeron_publication_offer(
        b->publication, buffer, (size_t)length, NULL, NULL);
    pthread_mutex_unlock(&b->client->close_mutex);
    return r;
}

/* Expose maxMessageLength for the up-front oversize check in Topic.publish.
 * Uses aeron_publication_constants (the only Aeron C API to read max_message_length;
 * there is no free function). Follows the same close_mutex+closing guard as
 * kyo_aeron_publication_is_connected and kyo_aeron_publication_offer. Returns 0 when
 * closing (so any bytes.length > 0 check on the Scala side will detect oversize and
 * abort cleanly). The critical section is brief: aeron_publication_constants is a
 * non-blocking struct read with no conductor interaction. The size_t max_message_length
 * field is at most 16 MiB (16777216), well within int32_t range. */
int32_t kyo_aeron_publication_max_message_length(void* pub)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    pthread_mutex_lock(&b->client->close_mutex);
    /* b->closed guards the caller's-own-close path; 0 means "cannot send", consistent
     * with the closing-guard return value (no dereference of the dead Aeron handle). */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0;   /* closed: Scala treats 0 as "cannot send" and aborts with TopicMessageTooLargeException */
    }
    aeron_publication_constants_t constants;
    int rc = aeron_publication_constants(b->publication, &constants);
    pthread_mutex_unlock(&b->client->close_mutex);
    if (rc < 0) return 0;
    /* max_message_length is size_t; cast to int32_t is safe: the value is at most
     * 16 MiB (AERON_MAX_MESSAGE_LENGTH = 16777216), well within int32_t range. */
    return (int32_t)constants.max_message_length;
}

void kyo_aeron_publication_close(void* pub)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    if (b == NULL) return;
    /* Guard against use-after-free: if the client is already closing, the Aeron
     * conductor on_close path will call aeron_publication_delete on the raw
     * publication struct, freeing it.  Accessing it here would be a use-after-free.
     * The mutex ensures that either:
     * (a) we call aeron_publication_close while the client is still open (safe), or
     * (b) we see closing=1 and skip (the Aeron cleanup handles resource release).
     *
     * Because the client bundle's refcount was incremented in kyo_aeron_async_add_publication
     * under g_registry_mutex, the bundle is guaranteed to be alive here: the bundle
     * stays in the registry (and therefore alive) until its refcount reaches 0, which
     * requires all publication/subscription refs AND the client's own ref to be released.
     * So close_mutex is valid to acquire here. */
    pthread_mutex_lock(&b->client->close_mutex);
    /* Idempotent: a second close on an already-closed (free-deferred) bundle is a no-op, so a
     * double close never double-frees the bundle nor double-releases the client refcount. */
    if (b->closed) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return;
    }
    int closing = b->client->closing;
    if (!closing) {
        aeron_publication_close(b->publication, NULL, NULL);
    }
    /* free-deferral: mark the bundle closed so a SUBSEQUENT offer / is_connected /
     * max_message_length on this same handle returns the existing -4 / 0 under the guard
     * instead of dereferencing the now-dead inner Aeron handle (use-after-free).
     *
     * Free-ownership (decided here, under close_mutex, by the client's closing state):
     *  - client still open (closing == 0): this is the offer-after-own-close path. A
     *    later offer may legitimately run on b and MUST observe b->closed == 1 and a live b,
     *    so b must NOT be freed here. Leave b in the per-client list; the client-close sweep
     *    is the sole free-owner (it frees every list entry whose closed == 1).
     *  - client already closing (closing == 1): the client-close sweep ran (or is running)
     *    under this same close_mutex. At sweep time b->closed was still 0 (this call had not
     *    set it yet), so the sweep did NOT free b (it frees only closed == 1 entries). Hence
     *    b is alive and this call is the sole free-owner: unlink it from the list and free it
     *    after releasing the locks. No double-free: the sweep skipped it, no later sweep runs. */
    b->closed = 1;
    int free_here = closing;
    if (free_here) {
        pub_list_remove(b->client, b);
    }
    pthread_mutex_unlock(&b->client->close_mutex);
    /* Release this publication's reference to the client bundle. */
    client_bundle_release(b->client);
    if (free_here) {
        free(b);
    }
}

/* Async subscription add (start step).  Symmetric to async_add_publication. */
void* kyo_aeron_async_add_subscription(void* client, const char* uri, int32_t stream_id)
{
    kyo_aeron_client_bundle* c = (kyo_aeron_client_bundle*)client;
    /* Guard the add-vs-close race: same protocol as async_add_publication. */
    pthread_mutex_lock(&g_registry_mutex);
    if (!registry_is_live(c) || c->closing) {
        pthread_mutex_unlock(&g_registry_mutex);
        return NULL;
    }
    c->refcount++;
    pthread_mutex_unlock(&g_registry_mutex);

    /* Registry-to-close-mutex gap: symmetric to kyo_aeron_async_add_publication. Guard the
     * c->client use under close_mutex so a racing client_close cannot free the inner aeron_t
     * between the registry check and the use. Lock ordering preserved (g_registry_mutex
     * released before close_mutex acquired). */
    pthread_mutex_lock(&c->close_mutex);
    if (c->closing) {
        pthread_mutex_unlock(&c->close_mutex);
        client_bundle_release(c);
        return NULL;
    }
    aeron_async_add_subscription_t* async_handle = NULL;
    /* Image handler params are all NULL; the shim does not track image availability. */
    int add_rc = aeron_async_add_subscription(&async_handle, c->client, uri, stream_id, NULL, NULL, NULL, NULL);
    pthread_mutex_unlock(&c->close_mutex);
    if (add_rc < 0) {
        client_bundle_release(c);
        return NULL;
    }
    kyo_aeron_async_sub_token* tok = (kyo_aeron_async_sub_token*)malloc(sizeof(kyo_aeron_async_sub_token));
    if (tok == NULL) {
        /* aeron_async_add_subscription allocated async_handle internally; the conductor
         * owns and reclaims it on registration failure or cancellation.  The C Aeron
         * client does not expose a cancel-before-completion call, so we leave async_handle
         * to the conductor (the registration is cancelled by the absence of a get call).
         * This mirrors the publication async-add malloc-failure arm: defensive best-effort
         * cleanup; malloc failure is not expected on embedded targets. */
        client_bundle_release(c);
        return NULL;
    }
    tok->async    = async_handle;
    tok->sub      = NULL;
    tok->client   = c;
    tok->err_code = 0;
    tok->err_msg[0] = '\0';
    return tok;
}

/* Async subscription poll (one step, non-blocking).  Symmetric to pub poll.
 *
 * NOTE: no close_mutex guard is needed here. tok->async is an independent heap allocation
 * that aeron_close does not free (it frees only the conductor's pointer array, not the async
 * token structs); a concurrent close leaves the token alive (leaked), never freed under us. */
long kyo_aeron_async_add_subscription_poll(void* async_token)
{
    kyo_aeron_async_sub_token* tok = (kyo_aeron_async_sub_token*)async_token;
    aeron_subscription_t* sub = NULL;
    int r = aeron_async_add_subscription_poll(&sub, tok->async);
    if (r < 0) {
        /* Registration failed. aeron_async_add_subscription_poll already called
         * aeron_async_cmd_free on the < 0 path. Capture errcode/errmsg from TLS
         * immediately (before any subsequent Aeron call on this thread overwrites TLS).
         * Token is NOT freed here; Scala reads err fields via accessor functions then
         * calls _free. Do NOT call client_bundle_release here: _free handles it. */
        tok->async = NULL;
        tok->err_code = aeron_errcode();
        const char* msg = aeron_errmsg();
        strncpy(tok->err_msg, msg ? msg : "", KYO_AERON_ERR_MSG_SIZE - 1);
        tok->err_msg[KYO_AERON_ERR_MSG_SIZE - 1] = '\0';
        return -1;
    }
    if (r > 0) {
        /* Registration succeeded; aeron_async_add_subscription_poll wrote the completed
         * subscription into `sub` and freed the async handle. Stash the pointer for _get
         * (the async handle must not be polled again). */
        tok->sub   = sub;
        tok->async = NULL;
        return 1;
    }
    return 0;
}

/* Retrieve the completed subscription bundle after _poll returned > 0.
 * Returns the subscription pointer stashed by _poll; does NOT re-poll (symmetric to the
 * publication _get; the async handle was freed by aeron_async_add_subscription_poll at
 * the > 0 return). */
void* kyo_aeron_async_add_subscription_get(void* async_token)
{
    kyo_aeron_async_sub_token* tok = (kyo_aeron_async_sub_token*)async_token;
    aeron_subscription_t* sub = tok->sub; /* stashed by _poll at the > 0 return */
    /* Build the subscription bundle. */
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)malloc(sizeof(kyo_aeron_subscription_bundle));
    if (b == NULL) {
        /* Leave the token ALIVE for the caller's single _free (see the publication _get note). */
        aeron_subscription_close(sub, NULL, NULL);
        return NULL;
    }
    b->subscription = sub;
    b->client       = tok->client;
    b->closed       = 0;     /* malloc does not zero-init; free-deferral flag starts clear */
    b->sub_next     = NULL;
    /* Start the reassembly slot small; the fragment handler grows it on demand
     * (up to KYO_AERON_SLOT_MAX_CAP = 16 MiB) when a larger message arrives, so a
     * subscription that only sees small messages does not pay 16 MiB up front. */
    b->slot_cap = KYO_AERON_SLOT_INITIAL_CAP;
    b->slot     = (uint8_t*)malloc((size_t)b->slot_cap);
    if (b->slot == NULL) {
        /* Leave the token ALIVE for the caller's single _free. */
        aeron_subscription_close(sub, NULL, NULL);
        free(b);
        return NULL;
    }
    b->slot_len = -1; /* empty sentinel */
    b->pending  = 0;  /* no un-copied message held */
    if (aeron_fragment_assembler_create(&b->assembler, kyo_aeron_fragment_handler, b) < 0) {
        /* Leave the token ALIVE for the caller's single _free. */
        aeron_subscription_close(sub, NULL, NULL);
        free(b->slot);
        free(b);
        return NULL;
    }
    /* Register the bundle in its client's per-client subscription list (free-deferral),
     * under close_mutex, mirroring the publication _get insertion. The subscription bundle
     * holds a refcount on the client bundle (taken in _start), so close_mutex is valid to
     * acquire and the client bundle stays alive. Lock order preserved (only close_mutex). */
    pthread_mutex_lock(&b->client->close_mutex);
    sub_list_insert(b->client, b);
    pthread_mutex_unlock(&b->client->close_mutex);
    free(tok);
    return b;
}

/* Free the async subscription token on fiber interrupt or after a _poll < 0 error. */
void kyo_aeron_async_add_subscription_free(void* async_token)
{
    if (async_token == NULL) return;
    kyo_aeron_async_sub_token* tok = (kyo_aeron_async_sub_token*)async_token;
    client_bundle_release(tok->client);
    free(tok);
}

/* Error accessors: read the err_code / err_msg captured into the token at the _poll < 0 site.
 * These MUST be called after _poll returns < 0 and BEFORE _free. The token is still alive at
 * that point (not freed by _poll on the < 0 path). After reading, call the matching _free
 * function to release the token. */

int kyo_aeron_async_add_publication_err_code(void* async_token)
{
    if (async_token == NULL) return 0;
    return ((kyo_aeron_async_pub_token*)async_token)->err_code;
}

const char* kyo_aeron_async_add_publication_err_msg(void* async_token)
{
    if (async_token == NULL) return "";
    return ((kyo_aeron_async_pub_token*)async_token)->err_msg;
}

int kyo_aeron_async_add_subscription_err_code(void* async_token)
{
    if (async_token == NULL) return 0;
    return ((kyo_aeron_async_sub_token*)async_token)->err_code;
}

const char* kyo_aeron_async_add_subscription_err_msg(void* async_token)
{
    if (async_token == NULL) return "";
    return ((kyo_aeron_async_sub_token*)async_token)->err_msg;
}

int kyo_aeron_subscription_is_connected(void* sub)
{
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)sub;
    /* Close-mutex guard: see kyo_aeron_publication_is_connected. The client bundle and its
     * close_mutex stay alive for this call: the subscription bundle's own refcount on a
     * concurrent client close, or the client's own outstanding refcount on the
     * poll-after-own-close path (where subscription_close already released the subscription ref). */
    pthread_mutex_lock(&b->client->close_mutex);
    /* b->closed guards the caller's-own-close path the same way client->closing guards the
     * client-close path; both return not-connected without dereferencing the dead handle. */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0; /* not connected */
    }
    int r = aeron_subscription_is_connected(b->subscription) ? 1 : 0;
    pthread_mutex_unlock(&b->client->close_mutex);
    return r;
}

/* Return-value contract (read by FfiAeronTransport.pollOne):
 *   > 0   : a message of that many bytes was reassembled and copied into dst.
 *   0     : no complete message was available on this poll.
 *   -1    : the underlying aeron_subscription_poll reported an error.
 *   -2    : the message exceeds the 16 MiB protocol ceiling and is undeliverable
 *           (dropped by the overflow guard in kyo_aeron_fragment_handler).
 *   < -2  : a reassembled message is held in the slot but dst was too small; the
 *           magnitude (-return) is the exact byte count dst must be grown to.  The
 *           message is retained (pending=1); the next call with a large-enough dst
 *           copies it out WITHOUT consuming another message from Aeron, so no message
 *           is lost when the caller's reusable buffer has to grow. */
long kyo_aeron_subscription_poll(void* sub, uint8_t* dst, int32_t dst_cap)
{
    /* Single-caller: see bundle struct comment. */
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)sub;
    if (b->pending) {
        /* A message reassembled on a prior call did not fit the caller's dst.  Do
         * NOT poll Aeron again (that would consume the next message and drop this
         * one); copy out the retained message if dst is now large enough. */
        if (b->slot_len > dst_cap) return -(long)b->slot_len; /* caller must grow further */
        memcpy(dst, b->slot, (size_t)b->slot_len);
        b->pending = 0;
        return (long)b->slot_len;
    }
    /* Reset slot to empty before the poll so stale data from a prior call cannot
     * bleed through if this call receives zero fragments. */
    b->slot_len = -1;
    /* Close-mutex guard: only the aeron_subscription_poll call dereferences the inner Aeron
     * subscription handle, so only it needs the guard (the pending-copy arm above reads
     * b->slot / b->slot_len, local fields not freed by the client close). If the client is
     * closing, return 0 (no fragments -> Absent in FfiAeronTransport.pollOne -> the stream
     * loop treats it as transient and retries). The subscription bundle's refcount keeps
     * the client bundle and its close_mutex alive for this call. */
    pthread_mutex_lock(&b->client->close_mutex);
    /* b->closed (caller's own subscription_close, free deferred) returns no-fragment under the
     * guard instead of dereferencing the dead Aeron handle, the same as the closing guard. */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0; /* no fragment: maps to Absent, the existing safe sentinel */
    }
    int fragments = aeron_subscription_poll(
        b->subscription, aeron_fragment_assembler_handler, b->assembler, 1);
    pthread_mutex_unlock(&b->client->close_mutex);
    if (fragments < 0) return -1;
    if (b->slot_len == -2) return -2; /* overflow: message exceeded the 16 MiB ceiling */
    if (b->slot_len < 0) return 0;    /* no fragment received this poll */
    if (b->slot_len > dst_cap) {
        /* The reassembled message is larger than the caller's buffer.  Retain it in
         * the slot and report the required size so the caller can grow and re-copy. */
        b->pending = 1;
        return -(long)b->slot_len;
    }
    memcpy(dst, b->slot, (size_t)b->slot_len);
    return (long)b->slot_len;
}

void kyo_aeron_subscription_close(void* sub)
{
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)sub;
    if (b == NULL) return;
    /* Guard against use-after-free, symmetric to kyo_aeron_publication_close. This subscription's
     * still-held refcount on the client bundle keeps it (and its close_mutex) alive here (this
     * call releases that refcount below, via client_bundle_release). */
    pthread_mutex_lock(&b->client->close_mutex);
    /* Idempotent: a second close on an already-closed (free-deferred) bundle is a no-op, so a
     * double close never double-frees the bundle nor double-releases the client refcount. */
    if (b->closed) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return;
    }
    int closing = b->client->closing;
    if (!closing) {
        /* Client still open: close the Aeron subscription handle safely. */
        aeron_subscription_close(b->subscription, NULL, NULL);
    }
    /* free-deferral: mark closed so a SUBSEQUENT poll / is_connected on this same handle
     * returns 0 under the guard instead of dereferencing the now-dead Aeron handle.
     *
     * Free-ownership (decided here, under close_mutex, by the client's closing state),
     * identical to kyo_aeron_publication_close:
     *  - client still open (closing == 0): a poll-after-own-close may legitimately run on b and
     *    MUST observe b->closed == 1 and a live b, so b must NOT be freed here. Leave b in the
     *    per-client list; the client-close sweep is the sole free-owner.
     *  - client already closing (closing == 1): the sweep ran (or is running) under this same
     *    close_mutex and skipped b (b->closed was still 0 at sweep time), so b is alive and this
     *    call is the sole free-owner: unlink and free after releasing the lock. No double-free. */
    b->closed = 1;
    int free_here = closing;
    if (free_here) {
        sub_list_remove(b->client, b);
    }
    pthread_mutex_unlock(&b->client->close_mutex);
    /* Release this subscription's reference to the client bundle. */
    client_bundle_release(b->client);
    if (free_here) {
        /* The inner aeron_subscription_t was already freed (by aeron_subscription_close above
         * when the client was open, or by the conductor on_close when closing); free only the
         * client-side assembler + slot + bundle. */
        aeron_fragment_assembler_delete(b->assembler);
        free(b->slot);
        free(b);
    }
}

/* Error slot accessor functions.
 *
 * kyo_aeron_has_client_error: returns 1 if a fatal error was recorded, 0 otherwise.
 * kyo_aeron_client_error_msg: returns the recorded error message as a C string pointer.
 *   The returned pointer is valid until the client bundle is freed (under the bundle's
 *   refcount lifetime). The Scala binding reads it as Ffi.Borrowed[String] (no ownership transfer).
 *
 * Both acquire the slot mutex briefly to ensure a consistent read of present+errmsg.
 * Called by FfiAeronTransport.recordedFatalError at the offer/poll boundary.
 */
int kyo_aeron_has_client_error(void* client)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL || b->err_slot == NULL) return 0;
    pthread_mutex_lock(&b->err_slot->mutex);
    int p = b->err_slot->present;
    pthread_mutex_unlock(&b->err_slot->mutex);
    return p;
}

const char* kyo_aeron_client_error_msg(void* client)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL || b->err_slot == NULL) return "";
    /* Read under the slot mutex, mirroring kyo_aeron_client_error_code: the lock inherits the
     * happens-before the writer (kyo_aeron_error_handler) established at its matching unlock,
     * so the errmsg bytes are visible to this thread. errmsg is write-once under first-wins and
     * is never re-mutated while present == 1, so returning the internal pointer is safe to read
     * after the unlock; when no error is present the empty string literal is returned. */
    pthread_mutex_lock(&b->err_slot->mutex);
    const char* msg = b->err_slot->present ? b->err_slot->errmsg : "";
    pthread_mutex_unlock(&b->err_slot->mutex);
    return msg;
}

/* Read the recorded errcode from the slot.
 * Returns the errcode captured by kyo_aeron_error_handler under the slot mutex, or 0 if
 * no error has been recorded or if client/slot is NULL. No blocking, no spin; the critical
 * section is a single int read under the same mutex used by kyo_aeron_has_client_error.
 * Called by FfiAeronTransport.recordedFatalError to derive a non-empty detail when the
 * recorded message is empty (Aeron passes NULL to the handler for some fatal conditions). */
int kyo_aeron_client_error_code(void* client)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL || b->err_slot == NULL) return 0;
    pthread_mutex_lock(&b->err_slot->mutex);
    int code = b->err_slot->present ? b->err_slot->errcode : 0;
    pthread_mutex_unlock(&b->err_slot->mutex);
    return code;
}

/* Test-inject seam: fires the error handler with a synthetic errcode/errmsg.
 *
 * Always compiled in (no #ifdef); inert in production (only behavioral tests call it).
 * The runtime-armed design avoids per-platform compile-flag changes in build.sbt.
 * Calls kyo_aeron_error_handler directly to exercise the exact same recording path
 * the real conductor would take.
 */
void kyo_aeron_test_inject_error(void* client, int errcode, const char* errmsg)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL || b->err_slot == NULL) return;
    /* Reset present to 0 to allow re-injection in tests (each inject overwrites). */
    pthread_mutex_lock(&b->err_slot->mutex);
    b->err_slot->present = 0;
    pthread_mutex_unlock(&b->err_slot->mutex);
    /* Fire the handler: it acquires the mutex and records. */
    kyo_aeron_error_handler(b->err_slot, errcode, errmsg);
}

#endif
