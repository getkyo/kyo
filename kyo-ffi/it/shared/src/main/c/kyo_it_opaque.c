#include <stdlib.h>

typedef struct { int value; } handle_t;

void* kyo_it_create_handle(int value) {
    handle_t* h = (handle_t*)malloc(sizeof(handle_t));
    h->value = value;
    return h;
}

int kyo_it_read_handle(void* h) {
    return ((handle_t*)h)->value;
}

void kyo_it_destroy_handle(void* h) {
    free(h);
}

void* kyo_it_null_handle() {
    return NULL;
}

void* kyo_it_null_handle_bare() {
    return NULL;
}

/* Blocking variant of kyo_it_null_handle_bare: a non-nullable Handle return that yields NULL, so the
 * binding's marshal throws FfiNullPointer. Used to verify a @Ffi.blocking marshal throw is captured into
 * the fiber result on every backend (on JS the marshal runs inside koffi's libuv completion callback). */
void* kyo_it_null_handle_bare_blocking() {
    return NULL;
}

void* kyo_it_create_handle_maybe(int value) {
    handle_t* h = (handle_t*)malloc(sizeof(handle_t));
    h->value = value;
    return h;
}
