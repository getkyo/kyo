#ifndef KYO_AERON_H
#define KYO_AERON_H

#if __has_include(<aeronc.h>) && __has_include(<aeronmd.h>)

#include <stdint.h>

void*   kyo_aeron_driver_start(const char* dir);
void    kyo_aeron_driver_close(void* driver);
void*   kyo_aeron_client_connect(const char* dir);
void    kyo_aeron_client_close(void* client);

/* Async publication add: returns an opaque token on success, NULL if the client bundle is
 * closed or closing. Pass the token to _poll until it returns non-zero, then to _get for the
 * publication bundle. On _poll < 0 the token is NOT freed internally: read the driver error via
 * _err_code / _err_msg, then call _free. On interrupt (Fiber cancellation while Awaiting), call
 * _free. */
void*   kyo_aeron_async_add_publication(void* client, const char* uri, int32_t stream_id);
long    kyo_aeron_async_add_publication_poll(void* async_token);
void*   kyo_aeron_async_add_publication_get(void* async_token);
void    kyo_aeron_async_add_publication_free(void* async_token);
int         kyo_aeron_async_add_publication_err_code(void* async_token);
const char* kyo_aeron_async_add_publication_err_msg(void* async_token);

int     kyo_aeron_publication_is_connected(void* pub);
long    kyo_aeron_publication_offer(void* pub, const uint8_t* buffer, int32_t length);
int32_t kyo_aeron_publication_max_message_length(void* pub);
void    kyo_aeron_publication_close(void* pub);

/* Async subscription add: symmetric to the publication async pair.
 * On _poll returning < 0 the token is NOT freed internally; read err_code / err_msg,
 * then call _free to release. */
void*   kyo_aeron_async_add_subscription(void* client, const char* uri, int32_t stream_id);
long    kyo_aeron_async_add_subscription_poll(void* async_token);
void*   kyo_aeron_async_add_subscription_get(void* async_token);
void    kyo_aeron_async_add_subscription_free(void* async_token);
int         kyo_aeron_async_add_subscription_err_code(void* async_token);
const char* kyo_aeron_async_add_subscription_err_msg(void* async_token);

int     kyo_aeron_subscription_is_connected(void* sub);
long    kyo_aeron_subscription_poll(void* sub, uint8_t* dst, int32_t dst_cap);
void    kyo_aeron_subscription_close(void* sub);

/* Error slot accessors: read the fatal error recorded by the non-exiting handler. */
int         kyo_aeron_has_client_error(void* client);
const char* kyo_aeron_client_error_msg(void* client);
/* Read the recorded errcode (0 if no error was recorded or client/slot is NULL). Used to derive
 * a non-empty detail when the recorded message is empty (Aeron passes NULL to the handler for
 * some fatal conditions). */
int         kyo_aeron_client_error_code(void* client);

/* Test-inject seam: fires the error handler with a synthetic error (always compiled in). */
void kyo_aeron_test_inject_error(void* client, int errcode, const char* errmsg);

#else
/* Header-absent translation unit is an explicit no-op rather than silently empty. The typedef
 * is unused; it keeps the TU well-formed on hosts where the Aeron headers are not staged. */
typedef int kyo_aeron_unavailable_t;
#endif

#endif
