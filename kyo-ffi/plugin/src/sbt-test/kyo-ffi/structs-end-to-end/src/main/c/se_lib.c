#include <stdint.h>

/* NOTE: The JvmEmitter currently marshals struct parameters as POINTERS
 * (FunctionDescriptor layout `ADDRESS`). To match that ABI, the C functions
 * below take struct pointers, not by-value structs. This is consistent with
 * the layout the emitter produces and works across System V / Win64 / Darwin
 * ABIs without additional per-platform logic.
 */

/* Nested struct, corresponds to Scala `Point` / `Line` */
typedef struct { int32_t x; int32_t y; } se_point_t;
typedef struct { se_point_t a; se_point_t b; } se_line_t;

/* Packed struct, two int32 fields, intentionally uniform alignment. */
#if defined(__GNUC__) || defined(__clang__)
typedef struct __attribute__((packed)) { int32_t tag; int32_t value; } se_packed_t;
#else
typedef struct { int32_t tag; int32_t value; } se_packed_t;
#endif

/* Sum the coordinates of both ends of a nested line. */
int32_t se_line_sum(const se_line_t *line) {
    return line->a.x + line->a.y + line->b.x + line->b.y;
}

/* Interpret a packed struct: tag weights value by 10 iff tag == 1. */
int32_t se_packed_compute(const se_packed_t *p) {
    return (p->tag == 1) ? p->value * 10 : p->value;
}

/* Multi-value return, sum (C return) + product (out-param). */
int32_t se_pair(int32_t a, int32_t b, int32_t *out_product) {
    *out_product = a * b;
    return a + b;
}

/* Struct-return with a String field via the multi-value out-pointer pattern.
 * `code` is returned as the C return value; `out_message` is populated with a
 * pointer to a statically-allocated C string the JVM side reads via Panama's
 * `getString(0L)`. The statically-allocated storage means the callee retains
 * ownership, the Scala caller MUST NOT free the returned pointer.
 */
static const char *k_status_ok  = "OK";
static const char *k_status_err = "ERR";

int32_t se_get_status(int32_t code, const char **out_message) {
    *out_message = (code == 0) ? k_status_ok : k_status_err;
    return code;
}
