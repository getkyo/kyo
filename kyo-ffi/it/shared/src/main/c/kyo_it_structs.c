#include <stdint.h>

/* Bundled struct surface.
 *
 * NOTE: kyo-ffi marshals struct parameters as POINTERS (ADDRESS layout) on
 * JVM and as Ptr-to-struct on Scala Native. To match that ABI, every C
 * function below that takes a struct parameter accepts a pointer to the
 * struct, not a by-value struct. This mirrors the scripted `structs-end-to-end`
 * fixture; the convention is cross-platform and avoids System V / Win64 /
 * Darwin ABI variation.
 *
 * Multi-value return ("Pair") is emitted by kyo-ffi as "primitive C return
 * + one C-pointer out-param per additional field", so `kyo_it_make_pair`
 * takes an out-pointer for the second field (set to `a * b`) and returns
 * `a + b` as its int32 return value (the first field of the multi-value
 * case-class result).
 */

/* Nested struct: Circle contains a Point. */
typedef struct { double x; double y; } kyo_it_point_t;
typedef struct { kyo_it_point_t center; double radius; } kyo_it_circle_t;

/* Area of a circle, radius * radius * pi. Uses a truncated pi literal so
 * the assertion can assert exact equality with the same literal on the
 * Scala side. */
#define KYO_IT_PI 3.141592653589793

double kyo_it_circle_area(const kyo_it_circle_t *c) {
    return c->radius * c->radius * KYO_IT_PI;
}

/* Translate the center of a circle by (dx, dy) and sum center coords + radius.
 * Second assertion: verifies nested struct field reads on both inner and
 * outer struct levels. */
double kyo_it_circle_sum(const kyo_it_circle_t *c, double dx, double dy) {
    return (c->center.x + dx) + (c->center.y + dy) + c->radius;
}

/* Packed struct, uniform int32 alignment keeps the Panama packed-layout
 * path happy (see scripted `structs-end-to-end` for the rationale: mixed
 * alignment inside a packed struct triggers "Invalid alignment constraint"
 * on JVM).
 */
#if defined(__GNUC__) || defined(__clang__)
typedef struct __attribute__((packed)) { int32_t tag; int32_t value; } kyo_it_packed_t;
#else
typedef struct { int32_t tag; int32_t value; } kyo_it_packed_t;
#endif

/* Packed-struct reader: returns value unchanged when tag == 0, value * 100
 * when tag == 1. Exercises packed-struct parameter marshalling. */
int32_t kyo_it_packed_value(const kyo_it_packed_t *p) {
    return (p->tag == 1) ? p->value * 100 : p->value;
}

/* Multi-value return, C return carries `Pair.a` (first field = sum of inputs),
 * out-param carries `Pair.b` (product of inputs). kyo-ffi maps the first
 * field of a case-class return to the C return value and each subsequent
 * field to an out-pointer argument, mirroring the scripted `se_pair` fixture. */
int32_t kyo_it_make_pair(int32_t a, int32_t b, int32_t *out_b) {
    *out_b = a * b;
    return a + b;
}

/* By-value struct return (@Ffi.byValue). kyo-ffi maps a `@Ffi.byValue def f(args): S`
 * binding to the C function `void f(S* out, ...args)`: the struct out-pointer is the
 * FIRST C parameter, the function fills it and returns void, and kyo-ffi marshals the
 * filled struct back into the case class. This is the same out-pointer convention
 * struct PARAMETERS already use, so it is identical across JVM, Native, and JS.
 *
 * `kyo_it_make_circle` fills a nested Circle (Point center + radius). */
void kyo_it_make_circle(kyo_it_circle_t *out, double cx, double cy, double r) {
    out->center.x = cx;
    out->center.y = cy;
    out->radius   = r;
}

/* Single-field by-value struct return. Unlike a multi-value return (which needs at
 * least two fields), `@Ffi.byValue` allows a single field; this proves it. */
typedef struct { int32_t v; } kyo_it_box_t;

void kyo_it_make_box(kyo_it_box_t *out, int32_t v) {
    out->v = v;
}

/* @Ffi.blocking + @Ffi.byValue: the same out-pointer struct fill, dispatched as a blocking
 * call (async on a libuv worker on JS, synchronous on the carrier on JVM/Native). Validates
 * that the struct out-buffer survives the blocking dispatch boundary on every backend, the
 * JS case being the interesting one (the buffer is filled on the worker and decoded inside
 * koffi's completion callback). */
void kyo_it_make_circle_blocking(kyo_it_circle_t *out, double cx, double cy, double r) {
    out->center.x = cx;
    out->center.y = cy;
    out->radius   = r;
}
