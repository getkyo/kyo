/*
 * kyo_aeron.c: fixed-arity shim over the Aeron 1.50.2 C client (aeronc.h) and embedded
 * media driver (aeronmd.h), statically linked into the kyo_aeron shared library so the
 * artifact carries no runtime libaeron dependency.
 *
 * Registration is a non-blocking async start plus a single-step poll (the wait loop lives
 * in Scala Async, not a C busy-loop). The fragment handler stays in C, fired synchronously
 * on the calling Scala carrier thread by aeron_subscription_poll. Offers use the copy path,
 * not tryClaim. Each driver/client handle is bundled with its context so the context
 * outlives the handle close. The #if guard compiles to nothing where the Aeron headers are
 * absent, mirroring kyo_uring.c.
 */

#if __has_include(<aeronc.h>) && __has_include(<aeronmd.h>)

#include <aeronc.h>
#include <aeronmd.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <pthread.h>

#include "kyo_aeron.h"

/* Bundle structs pair each handle with its context, which must outlive the handle close.
 *
 * The hazard: aeron_close(client) runs the conductor on_close path, which calls
 * aeron_publication_delete / aeron_subscription_delete on every still-registered resource.
 * A publication/subscription close arriving after that (e.g. from a Kyo fiber's Sync.ensure
 * cleanup outliving the Topic.run scope) reads pub->conductor = NULL from freed memory and
 * crashes dereferencing fields reachable through it.
 *
 * Two guards:
 *
 * Close-vs-close (close_mutex): the client bundle carries a refcount, 1 for the client call
 * site plus 1 per live publication or subscription bundle. kyo_aeron_publication_close /
 * kyo_aeron_subscription_close take close_mutex, skip the aeron close call if closing == 1
 * (Aeron already freed the struct) and perform it otherwise, release the mutex, then call
 * client_bundle_release.
 *
 * Add-vs-close (g_registry_mutex + live-bundle registry): kyo_aeron_async_add_publication /
 * add_subscription receive a raw client pointer whose bundle may already be freed, so liveness
 * must be proven before any field of it is touched. Taking the bundle's own close_mutex to bump
 * the refcount would itself be a use-after-free (&c->close_mutex points into freed memory).
 * Instead a process-global intrusive list holds every live bundle: kyo_aeron_client_connect
 * inserts under g_registry_mutex; add* scans the list under g_registry_mutex and increments the
 * refcount only if the bundle is present and not closing, returning NULL otherwise (the Scala
 * layer maps that to TopicPublicationClosedException); client_bundle_release decrements under
 * the same lock and removes plus frees at zero; kyo_aeron_client_close sets closing = 1 under
 * the same lock. Either add* sees the bundle live and takes its ref before release can free it,
 * or release wins and add* returns NULL instead of dereferencing freed memory.
 *
 * Lock ordering: g_registry_mutex is ALWAYS acquired BEFORE close_mutex, never the reverse.
 *
 * Neither mutex is held across aeron_close / aeron_publication_close, which can block on
 * conductor joins. */

typedef struct {
    aeron_driver_t*         driver;
    aeron_driver_context_t* ctx;
} kyo_aeron_driver_bundle;

/* Recording error slot for the non-exiting error handler (kyo_aeron_error_handler).
 *
 * Aeron's default handler calls exit(EXIT_FAILURE) on fatal conditions (driver timeout,
 * conductor service timeout, buffer full), killing the process before the Scala layer can
 * surface a typed error. The slot is the recording target: allocated before
 * aeron_context_set_error_handler (so before the bundle exists), stashed in b->err_slot, and
 * freed in client_bundle_release at refcount 0. It outlives the conductor thread because
 * kyo_aeron_client_close calls aeron_close (which joins the conductor) before
 * client_bundle_release.
 *
 * The conductor thread is the single writer; Scala carrier threads are the readers. A brief
 * mutex guards both paths.
 */
#define KYO_AERON_ERR_SLOT_MSG_SIZE 256

typedef struct {
    pthread_mutex_t mutex;
    volatile int    present;                      /* 1 after first error recorded, 0 initially */
    int             errcode;
    char            errmsg[KYO_AERON_ERR_SLOT_MSG_SIZE];
} kyo_aeron_error_slot;

/* Forward declarations so the client bundle can hold the heads of its per-client
 * free-deferral lists. */
struct kyo_aeron_publication_bundle_stct;
struct kyo_aeron_subscription_bundle_stct;

typedef struct kyo_aeron_client_bundle_stct {
    aeron_t*          client;
    aeron_context_t*  ctx;
    volatile int      closing;  /* set to 1 before aeron_close is invoked */
    pthread_mutex_t   close_mutex;
    int               refcount;  /* protected by g_registry_mutex */
    /* Recording error slot installed as the Aeron error handler clientd. */
    kyo_aeron_error_slot* err_slot;
    /* Intrusive doubly-linked list for the global live-bundle registry. */
    struct kyo_aeron_client_bundle_stct* prev;
    struct kyo_aeron_client_bundle_stct* next;
    /* Heads of the per-client free-deferral lists, both guarded by close_mutex. Bundles are
     * inserted at add completion (_get) and freed either by their own close (when the client
     * is already closing) or by the client-close sweep (when the caller closed while the client
     * was still open and the free was deferred). See kyo_aeron_client_close. */
    struct kyo_aeron_publication_bundle_stct* pub_list_head;
    struct kyo_aeron_subscription_bundle_stct* sub_list_head;
} kyo_aeron_client_bundle;

/* Publication bundle: pairs the raw Aeron publication with its client bundle so
 * kyo_aeron_publication_close can check the closing flag under the client mutex before
 * accessing the (possibly freed) Aeron struct. */
typedef struct kyo_aeron_publication_bundle_stct {
    aeron_publication_t*     publication;
    kyo_aeron_client_bundle* client;
    /* Free-deferral guard: set to 1 under client->close_mutex by kyo_aeron_publication_close.
     * offer / is_connected / max_message_length check it first (under close_mutex) and return
     * the existing -4 / 0 without dereferencing the dead Aeron handle, matching the JVM
     * refcount-lingering behavior. The bundle memory stays valid past close so a subsequent
     * offer is provably safe; see kyo_aeron_publication_close for who owns the free. */
    volatile int             closed;
    /* Intrusive node for the per-client publication list; guarded by client->close_mutex. */
    struct kyo_aeron_publication_bundle_stct* pub_next;
} kyo_aeron_publication_bundle;

/* Per-client publication-list helpers (free-deferral). Both MUST be called with the client
 * bundle's close_mutex held and nothing else (the client-close sweep traverses the list inline
 * under the same lock), which keeps the g_registry_mutex-before-close_mutex order intact. */
static void pub_list_insert(kyo_aeron_client_bundle* c, kyo_aeron_publication_bundle* b)
{
    b->pub_next = c->pub_list_head;
    c->pub_list_head = b;
}

/* No-op if b is not in the list (e.g. inserted after the client-close sweep cleared it). */
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

/* Reassembly slot sizing. The slot starts at KYO_AERON_SLOT_INITIAL_CAP (64 KiB holds an
 * unfragmented IPC message and most multi-fragment ones without a realloc) and grows on demand
 * toward KYO_AERON_SLOT_MAX_CAP, matching the JVM FragmentAssembler's profile. That ceiling is
 * AERON_FRAME_MAX_MESSAGE_LENGTH (aeron_udp_protocol.h) = 16 MiB, the protocol-enforced limit on
 * a single logical message regardless of term buffer configuration; a message that would exceed
 * it is rejected (slot_len = -2). */
#define KYO_AERON_SLOT_INITIAL_CAP (64 * 1024)
#define KYO_AERON_SLOT_MAX_CAP     (16 * 1024 * 1024)

/* Subscription bundle: analogous to the publication bundle, plus the fragment assembler and the
 * single-slot reassembly buffer the C fragment handler writes into synchronously. The client
 * back-reference carries the same closing guard. pending = 1 marks a reassembled message written to the slot
 * but not yet copied out (the caller's dst was too small on the previous poll); the next poll
 * copies it out without consuming another message from Aeron. */
typedef struct kyo_aeron_subscription_bundle_stct {
    aeron_subscription_t*       subscription;
    aeron_fragment_assembler_t* assembler;
    uint8_t*                    slot;
    int32_t                     slot_len; /* -1 = empty, -2 = overflow, >=0 = byte count */
    int32_t                     slot_cap; /* current slot capacity; grows on demand */
    int32_t                     pending;  /* 1 = slot holds a message not yet copied out */
    kyo_aeron_client_bundle*    client;
    /* Free-deferral guard, symmetric to the publication bundle's `closed`. poll / is_connected
     * check it under close_mutex and return 0 without dereferencing the dead Aeron handle; the
     * bundle memory stays valid past close. See kyo_aeron_subscription_close for the free-owner. */
    volatile int                closed;
    /* Intrusive node for the per-client subscription list; guarded by client->close_mutex. */
    struct kyo_aeron_subscription_bundle_stct* sub_next;
} kyo_aeron_subscription_bundle;
/* Single-poller contract: slot, slot_len, slot_cap, and pending are accessed without
 * synchronization. kyo_aeron_subscription_poll MUST NOT be called concurrently on the same sub
 * pointer; the Scala FfiAeronTransport upholds this by giving each Topic.stream subscriber its
 * own subscription handle. */

/* Per-client subscription-list helpers (free-deferral). Same contract as the publication pair
 * above. Defined here (after the struct) so the full type is visible. */
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
 * The add path splits into a non-blocking start and a single-step poll so the Scala carrier
 * thread is never monopolized and the fiber stays interruptible (a C-side for(;;) busy-loop
 * until Aeron confirms the registration would do neither):
 *   _start: takes the registry guard, kicks off aeron_async_add_publication, and returns a
 *     heap-allocated token immediately.
 *   _poll: one aeron_async_add_publication_poll step, returning > 0 (done), 0 (awaiting), or
 *     < 0 (error). On the > 0 return Aeron writes the completed publication pointer into the
 *     out-param AND frees the async handle (aeron_async_cmd_free; see the
 *     AERON_CLIENT_REGISTERED_MEDIA_DRIVER case in aeron_client.c), so that pointer MUST be
 *     captured into the token there: the handle is consumed and can never be polled again.
 *   _get: returns the stashed pointer wrapped in a bundle and frees the token. Never re-polls.
 *   _free: frees the token and releases the client refcount.
 *
 * Ownership, from the moment _start returns a non-NULL token (the Scala side owns it):
 *   _poll > 0: call _get next. _get frees the token on success (the refcount transfers to the
 *              returned bundle); on NULL (alloc failure) it closes the handle and leaves the
 *              token alive, so the caller calls _free exactly once (as on the < 0 path).
 *   _poll < 0: token still alive. Read err_code / err_msg via the accessors, then call _free
 *              exactly once. C does NOT free it, and _get MUST NOT be called.
 *   _poll 0:   token still alive; Async.sleep, then _poll again.
 *   interrupt: call _free exactly once.
 *
 * err_code / err_msg start as stubs (0 / "") and are filled at the _poll < 0 site so the Scala
 * layer can surface TopicRegistrationFailedException with driver detail. */

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

/* Process-global registry of live client bundles. g_registry_mutex serializes insertion at
 * client_connect, removal at client_bundle_release when the refcount reaches 0, and the
 * liveness check plus refcount increment in add_publication / add_subscription. Every critical
 * section under it is short; no slow Aeron API runs while it is held. */
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

/* Decrement the refcount under g_registry_mutex; remove from the registry and free the bundle
 * at zero. Called once per publication/subscription bundle release and once by
 * kyo_aeron_client_close for the client's own reference. */
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
        /* The error slot is a separate malloc; free it before the bundle. */
        if (b->err_slot != NULL) {
            pthread_mutex_destroy(&b->err_slot->mutex);
            free(b->err_slot);
        }
        free(b);
    }
}

/* Fragment handler: fires synchronously inside aeron_subscription_poll on the calling thread
 * and copies the reassembled message into the shim slot. No Scala upcall. The header param is
 * unused by design; the cast suppresses -Wunused-parameter. */
static void kyo_aeron_fragment_handler(
    void* clientd, const uint8_t* buffer, size_t length, aeron_header_t* header)
{
    (void)header;
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)clientd;
    /* Aeron cannot deliver a message past the 16 MiB protocol ceiling, so this is defensive. */
    if (length > (size_t)KYO_AERON_SLOT_MAX_CAP) { b->slot_len = -2; return; }
    /* Grow toward the ceiling on demand. realloc preserves nothing useful (the slot is
     * overwritten below) but keeps a single ownership pointer for the close path to free. */
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
    b->slot_len = (int32_t)length;  /* length <= slot_cap <= KYO_AERON_SLOT_MAX_CAP <= INT32_MAX */
}

void* kyo_aeron_driver_start(const char* dir)
{
    aeron_driver_context_t* ctx = NULL;
    if (aeron_driver_context_init(&ctx) < 0) return NULL;
    if (dir != NULL) aeron_driver_context_set_dir(ctx, dir);
    /* Delete the Aeron directory on start so successive embedded() calls initialize from a
     * clean state (no stale CnC file). dir_delete_on_shutdown is deliberately NOT set. */
    aeron_driver_context_set_dir_delete_on_start(ctx, true);
    aeron_driver_t* driver = NULL;
    if (aeron_driver_init(&driver, ctx) < 0) {
        aeron_driver_context_close(ctx);
        return NULL;
    }
    /* false = no manual pump; spawns DEDICATED conductor/sender/receiver C pthreads (the
     * default after aeron_driver_context_init, so set_threading_mode is not called). */
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
    /* Close handle before context: aeron_driver_close joins the conductor/sender/receiver
     * pthreads, and only then can the context struct be freed. */
    aeron_driver_close(b->driver);
    aeron_driver_context_close(b->ctx);
    free(b);
}

/* Non-exiting recording error handler, installed via aeron_context_set_error_handler BEFORE
 * aeron_init in place of the Aeron default (aeron_context.c), which calls exit(EXIT_FAILURE)
 * on fatal errors. Records the first fatal error into the kyo_aeron_error_slot (clientd) under
 * a brief mutex and returns: no exit, no abort, no blocking. The Scala layer polls the slot at
 * the offer/poll boundary and surfaces TopicTransportFailedException(detail).
 *
 * Written by the Aeron conductor thread only, read by any number of Scala carrier threads.
 */
static void kyo_aeron_error_handler(void* clientd, int errcode, const char* message)
{
    kyo_aeron_error_slot* slot = (kyo_aeron_error_slot*)clientd;
    if (slot == NULL) return;
    pthread_mutex_lock(&slot->mutex);
    if (!slot->present) {
        /* First error only: the one that caused the conductor to terminate. */
        slot->errcode  = errcode;
        if (message != NULL) {
            strncpy(slot->errmsg, message, KYO_AERON_ERR_SLOT_MSG_SIZE - 1);
            slot->errmsg[KYO_AERON_ERR_SLOT_MSG_SIZE - 1] = '\0';
        } else {
            slot->errmsg[0] = '\0';
        }
        /* present flips 0->1 exactly once, after errcode/errmsg are written and never modified
         * again while present == 1. That write-once invariant is what lets
         * kyo_aeron_client_error_msg hand back the internal errmsg pointer after releasing the
         * mutex (the bytes can no longer change). */
        slot->present = 1;
    }
    pthread_mutex_unlock(&slot->mutex);
    /* MUST NOT exit(), abort(), or block. */
}

void* kyo_aeron_client_connect(const char* dir)
{
    pthread_once(&g_registry_once, registry_init_once);
    aeron_context_t* ctx = NULL;
    if (aeron_context_init(&ctx) < 0) return NULL;
    if (dir != NULL) aeron_context_set_dir(ctx, dir);
    /* Allocate the slot before installing the handler: the bundle does not exist yet, and the
     * slot is the clientd the handler receives. */
    kyo_aeron_error_slot* slot = (kyo_aeron_error_slot*)malloc(sizeof(kyo_aeron_error_slot));
    if (slot == NULL) {
        aeron_context_close(ctx);
        return NULL;
    }
    pthread_mutex_init(&slot->mutex, NULL);
    slot->present  = 0;
    slot->errcode  = 0;
    slot->errmsg[0] = '\0';
    /* Install BEFORE aeron_init, which spawns the conductor thread that may call the handler. */
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
    b->err_slot      = slot;
    b->prev          = NULL;
    b->next          = NULL;
    b->pub_list_head = NULL;
    b->sub_list_head = NULL;
    pthread_mutex_init(&b->close_mutex, NULL);
    /* Register before returning: from here add_publication/add_subscription can find the bundle
     * and safely increment its refcount under g_registry_mutex. */
    pthread_mutex_lock(&g_registry_mutex);
    registry_insert(b);
    pthread_mutex_unlock(&g_registry_mutex);
    return b;
}

void kyo_aeron_client_close(void* client)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL) return;
    /* Set closing under g_registry_mutex. A concurrent add* either already holds the lock, sees
     * b live and not closing, and takes a ref (its close path releases it later), or acquires
     * the lock after us, observes closing = 1, and returns NULL. Once we release, no new add*
     * can take a ref.
     *
     * b is deliberately NOT removed from the registry here: it stays until
     * client_bundle_release sees refcount == 0, which requires every publication/subscription
     * ref to have been released too. Safe, because add* stops at the closing check. */
    pthread_mutex_lock(&g_registry_mutex);
    b->closing = 1;
    pthread_mutex_unlock(&g_registry_mutex);
    /* Acquire and release close_mutex to publish closing = 1 to any publication/subscription
     * close that reads it under that mutex (the happens-before those guards rely on). */
    pthread_mutex_lock(&b->close_mutex);
    pthread_mutex_unlock(&b->close_mutex);
    /* Close handle before context. */
    aeron_close(b->client);
    aeron_context_close(b->ctx);
    /* Free-deferral sweep over both per-client lists.
     *
     * Runs AFTER aeron_close: the conductor on_close path has already freed every inner
     * aeron_publication_t / aeron_subscription_t, so the sweep MUST NOT call
     * aeron_publication_close / aeron_subscription_close. It frees only the bundle struct, plus
     * the client-side assembler and slot for subscriptions.
     *
     * Free-ownership split, guaranteeing exactly one free per bundle: closed == 1 means the
     * caller already closed the bundle (and already released its client refcount) while the
     * client was open, so the sweep is its sole free-owner. closed == 0 means the caller has not
     * closed it yet, so its later close (which will observe closing == 1) is the free-owner and
     * the sweep leaves it in the list, alive; its still-held refcount keeps this client bundle
     * alive past the client_bundle_release below.
     *
     * Only close_mutex is held here (g_registry_mutex was released above), and the sweep never
     * calls client_bundle_release, so the lock order is preserved. */
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
    /* Same split for subscription bundles, additionally freeing the assembler and slot. */
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
    /* Release the client call site's own reference; frees the bundle if it was the last. */
    client_bundle_release(b);
}

/* Async publication add (start step). Takes the registry guard, kicks off
 * aeron_async_add_publication, and returns an opaque token immediately; the Scala side drives
 * the polling. Returns NULL if the client bundle is not live / is closing, or if
 * aeron_async_add_publication itself fails immediately. */
void* kyo_aeron_async_add_publication(void* client, const char* uri, int32_t stream_id)
{
    kyo_aeron_client_bundle* c = (kyo_aeron_client_bundle*)client;
    /* Add-vs-close guard: verify liveness and take the refcount under g_registry_mutex. NULL
     * signals a closed client, which Scala maps to TopicPublicationClosedException. */
    pthread_mutex_lock(&g_registry_mutex);
    if (!registry_is_live(c) || c->closing) {
        pthread_mutex_unlock(&g_registry_mutex);
        return NULL;
    }
    c->refcount++;
    pthread_mutex_unlock(&g_registry_mutex);

    /* Registry-to-close-mutex gap: once g_registry_mutex is released, a racing
     * kyo_aeron_client_close can call aeron_close(c->client) and free the inner aeron_t before
     * the use below (TOCTOU). Re-check closing and make the non-blocking start call under
     * close_mutex, which client_close never holds across aeron_close. Lock order holds:
     * g_registry_mutex was acquired and released first. */
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
        /* The conductor owns async_handle and reclaims it on registration failure or
         * cancellation; the C Aeron client exposes no cancel-before-completion call, so leave
         * it to the conductor (absence of a get call cancels the registration). */
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
 *   > 0 : registration complete; call _get next.
 *   0   : still awaiting; call again after a Scala-side Async.sleep.
 *   < 0 : registration failed; the token is NOT freed here. Read err_code / err_msg, then _free.
 *
 * No close_mutex guard is needed: tok->async is an independent heap allocation that aeron_close
 * does not free (it frees the conductor's pointer array, not the async token structs), so a
 * concurrent close leaves the token alive (leaked), never freed under us. */
long kyo_aeron_async_add_publication_poll(void* async_token)
{
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)async_token;
    aeron_publication_t* pub = NULL;
    int r = aeron_async_add_publication_poll(&pub, tok->async);
    if (r < 0) {
        /* aeron_async_add_publication_poll already called aeron_async_cmd_free on the < 0 path
         * (after AERON_SET_ERR), so tok->async is dangling: clear it, then capture
         * errcode/errmsg from TLS before any other Aeron call on this thread can overwrite
         * them. The token stays alive for the Scala layer's single _free, so do NOT release the
         * client refcount here (that would double-release). */
        tok->async = NULL;
        tok->err_code = aeron_errcode();
        const char* msg = aeron_errmsg();
        strncpy(tok->err_msg, msg ? msg : "", KYO_AERON_ERR_MSG_SIZE - 1);
        tok->err_msg[KYO_AERON_ERR_MSG_SIZE - 1] = '\0';
        return -1;
    }
    if (r > 0) {
        /* Aeron wrote the completed publication into `pub` and freed the async handle, which
         * must never be polled again; stash the pointer for _get. */
        tok->pub   = pub;
        tok->async = NULL;
        return 1;
    }
    /* Still awaiting (r == 0). */
    return 0;
}

/* Retrieve the completed publication after _poll returned > 0: wraps the pointer _poll stashed
 * in a freshly allocated bundle and frees the token (the client refcount transfers to the
 * bundle). Never re-polls, since aeron_async_add_publication_poll already freed the async handle
 * at its > 0 return.
 *
 * The caller owns the returned bundle and MUST eventually call kyo_aeron_publication_close. On
 * malloc failure it closes the handle and returns NULL, leaving the token ALIVE for the caller's
 * single _free. */
void* kyo_aeron_async_add_publication_get(void* async_token)
{
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)async_token;
    aeron_publication_t* pub = tok->pub; /* stashed by _poll at the > 0 return */
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)malloc(sizeof(kyo_aeron_publication_bundle));
    if (b == NULL) {
        /* Leave the token ALIVE so the caller's single _free releases the client refcount and
         * frees it exactly once; freeing here too would double-free. */
        aeron_publication_close(pub, NULL, NULL);
        return NULL;
    }
    b->publication = pub;
    b->client      = tok->client;
    b->closed      = 0;       /* malloc does not zero-init */
    b->pub_next    = NULL;
    /* Insert into the client's free-deferral list under close_mutex, the same lock the
     * client-close sweep and kyo_aeron_publication_close hold when touching it. The refcount
     * taken in _start keeps the client bundle (and its mutex) alive. Lock order preserved:
     * g_registry_mutex was released back in _start, so only close_mutex is held here. */
    pthread_mutex_lock(&b->client->close_mutex);
    pub_list_insert(b->client, b);
    pthread_mutex_unlock(&b->client->close_mutex);
    /* The refcount taken in _start now belongs to the bundle (released by publication_close). */
    free(tok);
    return b;
}

/* Free the async token on fiber interrupt (the Sync.ensure path) or after a _poll < 0 error.
 * The Aeron conductor owns tok->async and will eventually complete or fail the registration on
 * its own; we release the client refcount and free the token. */
void kyo_aeron_async_add_publication_free(void* async_token)
{
    if (async_token == NULL) return;
    kyo_aeron_async_pub_token* tok = (kyo_aeron_async_pub_token*)async_token;
    client_bundle_release(tok->client);
    free(tok);
}

/* Close-mutex guard, shared by every publication and subscription accessor below: each
 * inner-handle deref runs under close_mutex with a closing check, so a concurrent
 * kyo_aeron_client_close cannot free the handle out from under it. Either we take close_mutex
 * while the client is still open and the handle is valid, or we observe closing = 1 and return
 * the EXISTING safe sentinel. The critical section wraps ONLY the flag read and the
 * non-blocking Aeron call, never aeron_close. The client bundle (and its close_mutex) stays
 * alive for the call: on a concurrent client close the publication bundle's own refcount holds
 * it; on the offer-after-own-close path (where kyo_aeron_publication_close already released
 * that refcount) the client's own outstanding refcount holds it, since the runtime closes only
 * after every offer (Scala ordering: offer, then closePublication, then client close). */
int kyo_aeron_publication_is_connected(void* pub)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    pthread_mutex_lock(&b->client->close_mutex);
    /* b->closed guards the caller's-own-close path the same way client->closing guards the
     * client-close path; both return the sentinel without touching the dead Aeron handle. */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0; /* not connected: the Scala not-connected/backpressure path */
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
    /* -4 is the EXISTING sentinel, matching the JVM where a closed io.aeron.Publication.offer
     * returns Publication.CLOSED without faulting. Never a new value: -4 maps through
     * AeronSentinels.Closed to TopicPublicationClosedException. */
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return (long)AERON_PUBLICATION_CLOSED;
    }
    /* Raw int64_t sentinels pass through unchanged; the Scala side maps them via
     * AeronSentinels. reserved_value_supplier and clientd are NULL. */
    long r = (long)aeron_publication_offer(
        b->publication, buffer, (size_t)length, NULL, NULL);
    pthread_mutex_unlock(&b->client->close_mutex);
    return r;
}

/* Exposes maxMessageLength for the up-front oversize check in Topic.publish, via
 * aeron_publication_constants (the only Aeron C API that reads max_message_length; there is no
 * free function). Same close_mutex guard as the accessors above; the call it wraps is a
 * non-blocking struct read with no conductor interaction. */
int32_t kyo_aeron_publication_max_message_length(void* pub)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    pthread_mutex_lock(&b->client->close_mutex);
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0;   /* cannot send: Scala aborts with TopicMessageTooLargeException */
    }
    aeron_publication_constants_t constants;
    int rc = aeron_publication_constants(b->publication, &constants);
    pthread_mutex_unlock(&b->client->close_mutex);
    if (rc < 0) return 0;
    /* max_message_length is size_t but at most 16 MiB (AERON_MAX_MESSAGE_LENGTH = 16777216),
     * well within int32_t range. */
    return (int32_t)constants.max_message_length;
}

void kyo_aeron_publication_close(void* pub)
{
    kyo_aeron_publication_bundle* b = (kyo_aeron_publication_bundle*)pub;
    if (b == NULL) return;
    /* The refcount taken in kyo_aeron_async_add_publication keeps the client bundle (and its
     * close_mutex) alive here, so the guard below is safe to take. */
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
    /* Mark closed so a SUBSEQUENT offer / is_connected / max_message_length on this handle
     * returns the -4 / 0 sentinel under the guard instead of dereferencing the now-dead Aeron
     * handle.
     *
     * Free-ownership, decided here under close_mutex by the client's closing state:
     *  - closing == 0: a later offer may legitimately run on b and MUST find it alive with
     *    closed == 1, so b is left in the per-client list and the client-close sweep is its
     *    sole free-owner (it frees every entry whose closed == 1).
     *  - closing == 1: the sweep ran (or is running) under this same close_mutex and skipped b,
     *    because b->closed was still 0 at sweep time. So b is alive, this call is its sole
     *    free-owner, and no later sweep runs: unlink here and free after releasing the lock. */
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

    /* Registry-to-close-mutex gap: symmetric to kyo_aeron_async_add_publication. */
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
        /* The conductor owns and reclaims async_handle; symmetric to the publication arm. */
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

/* Async subscription poll (one step, non-blocking). Symmetric to the publication poll,
 * including the reason no close_mutex guard is needed. */
long kyo_aeron_async_add_subscription_poll(void* async_token)
{
    kyo_aeron_async_sub_token* tok = (kyo_aeron_async_sub_token*)async_token;
    aeron_subscription_t* sub = NULL;
    int r = aeron_async_add_subscription_poll(&sub, tok->async);
    if (r < 0) {
        /* aeron_async_add_subscription_poll already freed the async handle on the < 0 path.
         * Capture errcode/errmsg from TLS before any other Aeron call on this thread can
         * overwrite them. The token stays alive for the Scala layer's single _free, so do NOT
         * release the client refcount here. */
        tok->async = NULL;
        tok->err_code = aeron_errcode();
        const char* msg = aeron_errmsg();
        strncpy(tok->err_msg, msg ? msg : "", KYO_AERON_ERR_MSG_SIZE - 1);
        tok->err_msg[KYO_AERON_ERR_MSG_SIZE - 1] = '\0';
        return -1;
    }
    if (r > 0) {
        /* Aeron wrote the completed subscription into `sub` and freed the async handle, which
         * must never be polled again; stash the pointer for _get. */
        tok->sub   = sub;
        tok->async = NULL;
        return 1;
    }
    return 0;
}

/* Retrieve the completed subscription bundle after _poll returned > 0. Symmetric to the
 * publication _get: returns the pointer _poll stashed, and never re-polls. */
void* kyo_aeron_async_add_subscription_get(void* async_token)
{
    kyo_aeron_async_sub_token* tok = (kyo_aeron_async_sub_token*)async_token;
    aeron_subscription_t* sub = tok->sub; /* stashed by _poll at the > 0 return */
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)malloc(sizeof(kyo_aeron_subscription_bundle));
    if (b == NULL) {
        /* Leave the token ALIVE for the caller's single _free (see the publication _get note). */
        aeron_subscription_close(sub, NULL, NULL);
        return NULL;
    }
    b->subscription = sub;
    b->client       = tok->client;
    b->closed       = 0;     /* malloc does not zero-init */
    b->sub_next     = NULL;
    /* Start the reassembly slot small; the fragment handler grows it on demand toward the
     * 16 MiB ceiling, so a subscription that only sees small messages does not pay it up front. */
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
    /* Insert into the client's free-deferral list under close_mutex, mirroring the publication
     * _get insertion. The refcount taken in _start keeps the client bundle alive. */
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

/* Error accessors for the err_code / err_msg captured into the token at the _poll < 0 site.
 * MUST be called after _poll returns < 0 and BEFORE the matching _free, while the token is
 * still alive. */

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
    /* Close-mutex guard: see kyo_aeron_publication_is_connected. */
    pthread_mutex_lock(&b->client->close_mutex);
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
 *   -2    : the message exceeds the 16 MiB protocol ceiling and is undeliverable (dropped by
 *           the overflow guard in kyo_aeron_fragment_handler).
 *   < -2  : a reassembled message is held in the slot but dst was too small; the magnitude is
 *           the exact byte count dst must grow to. The message is retained (pending = 1) and
 *           the next call with a large-enough dst copies it out WITHOUT consuming another
 *           message from Aeron, so nothing is lost while the caller's buffer grows. */
long kyo_aeron_subscription_poll(void* sub, uint8_t* dst, int32_t dst_cap)
{
    /* Single-poller: see the subscription bundle struct. */
    kyo_aeron_subscription_bundle* b = (kyo_aeron_subscription_bundle*)sub;
    if (b->pending) {
        /* A message reassembled on a prior call did not fit the caller's dst. Do NOT poll Aeron
         * again, which would consume the next message and drop this one. */
        if (b->slot_len > dst_cap) return -(long)b->slot_len; /* caller must grow further */
        memcpy(dst, b->slot, (size_t)b->slot_len);
        b->pending = 0;
        return (long)b->slot_len;
    }
    /* Reset to empty so stale data from a prior call cannot bleed through if this call
     * receives zero fragments. */
    b->slot_len = -1;
    /* Close-mutex guard: only aeron_subscription_poll dereferences the inner Aeron handle, so
     * only it needs the guard (the pending-copy arm above reads local fields the client close
     * does not free). Closing returns 0, which FfiAeronTransport.pollOne maps to Absent and the
     * stream loop treats as transient. */
    pthread_mutex_lock(&b->client->close_mutex);
    if (b->closed || b->client->closing) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return 0; /* no fragment: the existing safe sentinel */
    }
    int fragments = aeron_subscription_poll(
        b->subscription, aeron_fragment_assembler_handler, b->assembler, 1);
    pthread_mutex_unlock(&b->client->close_mutex);
    if (fragments < 0) return -1;
    if (b->slot_len == -2) return -2; /* overflow: message exceeded the 16 MiB ceiling */
    if (b->slot_len < 0) return 0;    /* no fragment received this poll */
    if (b->slot_len > dst_cap) {
        /* Larger than the caller's buffer: retain it and report the required size. */
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
    /* Symmetric to kyo_aeron_publication_close. This subscription's still-held refcount keeps
     * the client bundle (and its close_mutex) alive until the release below. */
    pthread_mutex_lock(&b->client->close_mutex);
    /* Idempotent: a second close on an already-closed (free-deferred) bundle is a no-op, so a
     * double close never double-frees the bundle nor double-releases the client refcount. */
    if (b->closed) {
        pthread_mutex_unlock(&b->client->close_mutex);
        return;
    }
    int closing = b->client->closing;
    if (!closing) {
        /* Client still open: closing the Aeron subscription handle is safe. */
        aeron_subscription_close(b->subscription, NULL, NULL);
    }
    /* Mark closed so a SUBSEQUENT poll / is_connected on this handle returns 0 under the guard
     * instead of dereferencing the now-dead Aeron handle. Free-ownership is decided exactly as
     * in kyo_aeron_publication_close: closing == 0 leaves b in the per-client list for the
     * client-close sweep to free; closing == 1 means the sweep already skipped b (closed was
     * still 0 then), so this call is the sole free-owner and unlinks and frees it. */
    b->closed = 1;
    int free_here = closing;
    if (free_here) {
        sub_list_remove(b->client, b);
    }
    pthread_mutex_unlock(&b->client->close_mutex);
    /* Release this subscription's reference to the client bundle. */
    client_bundle_release(b->client);
    if (free_here) {
        /* The inner aeron_subscription_t is already freed (by the conductor on_close), so free
         * only the client-side assembler, slot, and bundle. */
        aeron_fragment_assembler_delete(b->assembler);
        free(b->slot);
        free(b);
    }
}

/* Error slot accessors, called by FfiAeronTransport.recordedFatalError at the offer/poll
 * boundary. Both take the slot mutex briefly for a consistent read of present + errmsg. The
 * pointer kyo_aeron_client_error_msg returns is valid for the client bundle's refcount
 * lifetime; the Scala binding reads it as Ffi.Borrowed[String], with no ownership transfer.
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
    /* The lock inherits the happens-before the writer (kyo_aeron_error_handler) established at
     * its matching unlock, so the errmsg bytes are visible here. errmsg is write-once under
     * first-wins and is never re-mutated while present == 1, so the internal pointer is safe to
     * read after the unlock. */
    pthread_mutex_lock(&b->err_slot->mutex);
    const char* msg = b->err_slot->present ? b->err_slot->errmsg : "";
    pthread_mutex_unlock(&b->err_slot->mutex);
    return msg;
}

/* Read the recorded errcode, or 0 if no error was recorded or client/slot is NULL.
 * FfiAeronTransport.recordedFatalError uses it to derive a non-empty detail when the recorded
 * message is empty (Aeron passes NULL to the handler for some fatal conditions). */
int kyo_aeron_client_error_code(void* client)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL || b->err_slot == NULL) return 0;
    pthread_mutex_lock(&b->err_slot->mutex);
    int code = b->err_slot->present ? b->err_slot->errcode : 0;
    pthread_mutex_unlock(&b->err_slot->mutex);
    return code;
}

/* Test-inject seam: fires kyo_aeron_error_handler with a synthetic errcode/errmsg, exercising
 * the exact recording path the real conductor takes. Always compiled in (runtime-armed rather
 * than behind a compile flag, which avoids per-platform build.sbt changes); inert in production.
 */
void kyo_aeron_test_inject_error(void* client, int errcode, const char* errmsg)
{
    kyo_aeron_client_bundle* b = (kyo_aeron_client_bundle*)client;
    if (b == NULL || b->err_slot == NULL) return;
    /* Reset present so each inject overwrites the last. */
    pthread_mutex_lock(&b->err_slot->mutex);
    b->err_slot->present = 0;
    pthread_mutex_unlock(&b->err_slot->mutex);
    kyo_aeron_error_handler(b->err_slot, errcode, errmsg);
}

#endif
