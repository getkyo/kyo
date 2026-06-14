#include <stdint.h>

/* IT union surface (F8c #26).
 *
 * Simple int/float union, both variants are 4 bytes with 4-byte alignment so
 * the union layout is unambiguous on every target (no endianness-variable
 * padding, no alignment gap). The binding declares a matching `@Ffi.Union`
 * case class on the Scala side and passes it by pointer to the functions
 * below, which read the int view, write the int view, and read the float
 * view, all reinterpreting the same 4 bytes.
 *
 * Test strategy: write int 0x42280000 (IEEE-754 bits for 42.0f) into the
 * int view, read both views back, asInt must equal 0x42280000 and asFloat
 * must equal 42.0f. Proving cross-platform bit-reinterpretation is the
 * deliverable.
 */

typedef union {
    int32_t i;
    float   f;
} kyo_it_int_float_union;

/* Reads the union's int view from a Scala-allocated union. The Scala binding
 * passes `IntFloatUnion(asInt=X, asFloat=Y)` by pointer; the generator writes
 * the FIRST variant (`asInt`) into the shared bytes and ignores the rest.
 * C reads `u->i`, should match the `asInt` value the Scala side passed in.
 */
int32_t kyo_it_union_read_int(const kyo_it_int_float_union *u) {
    return u->i;
}

/* Reads the union's float view from the same shared bytes. The Scala binding
 * passed `asInt=0x42280000`, which are the IEEE-754 bits for 42.0f. C reads
 * `u->f`, should yield 42.0f without any explicit conversion. This is the
 * cross-platform bit-reinterpretation assertion.
 */
float kyo_it_union_read_float(const kyo_it_int_float_union *u) {
    return u->f;
}

/* #248: union with a struct variant, { int32_t i; struct { int32_t x, y; } p; }.
 * Exercises writing a case-class struct variant into a union parameter on every
 * backend: JVM/Native write the struct's fields into the union scratch segment,
 * JS writes them into the union DataView at the struct's field offsets. The union
 * is 8 bytes (max of the 4-byte int and the 8-byte point), 4-byte aligned, so the
 * layout is unambiguous across targets. */
typedef union {
    int32_t i;
    struct { int32_t x; int32_t y; } p;
} kyo_it_int_point_union;

/* Reads the x field of the struct (point) variant: the first 4 bytes. */
int32_t kyo_it_union_struct_x(const kyo_it_int_point_union *u) {
    return u->p.x;
}

/* Reads the y field of the struct (point) variant: the second 4 bytes. */
int32_t kyo_it_union_struct_y(const kyo_it_int_point_union *u) {
    return u->p.y;
}

/* Reads the int variant of the same union, proving the int variant still works
 * after the struct variant was added. */
int32_t kyo_it_union_struct_i(const kyo_it_int_point_union *u) {
    return u->i;
}

/* #248: union whose struct variant has a POINTER field, { int32_t i; struct { int32_t tag; void* h; } hh; }.
 * The pointer field (a Ffi.Handle on the Scala side) must be written into the union's bytes on every backend.
 * JVM/Native write the handle address into the union scratch segment; JS delegates struct serialization to
 * koffi's encoder, which writes the pointer's address into the union byte buffer. The handle points at a
 * kyo_it create_handle allocation (handle_t = { int value; }), so dereferencing its first int yields the value. */
typedef union {
    int32_t i;
    struct { int32_t tag; void* h; } hh;
} kyo_it_int_holder_union;

/* Reads the tag field of the holder (struct) variant. */
int32_t kyo_it_union_holder_tag(const kyo_it_int_holder_union *u) {
    return u->hh.tag;
}

/* Dereferences the handle field of the holder variant and reads its first int (the handle's stored value). */
int32_t kyo_it_union_holder_value(const kyo_it_int_holder_union *u) {
    return *(const int32_t *)(u->hh.h);
}

/* #251: an 8-byte union with a Long variant, { int64_t l; double d; }. Exercises that a Long variant writes its
 * 8 raw bytes into the union on every backend. JVM/Native store the long directly; JS must write the two 32-bit
 * halves rather than the double bit-pattern, so the C side reads back the exact long the caller passed. */
typedef union {
    int64_t l;
    double  d;
} kyo_it_long_double_union;

/* Reads the long view of the union: must equal the exact Long the caller passed via the Long variant. */
int64_t kyo_it_union_long_view(const kyo_it_long_double_union *u) {
    return u->l;
}

/* Reads the double view of the union: confirms the Double variant still marshals (a plain setFloat64 write). */
double kyo_it_union_double_view(const kyo_it_long_double_union *u) {
    return u->d;
}

/* #253: a STRUCT with a union FIELD, { int32_t tag; union { int32_t i; float f; } data; }. This is the
 * README's documented `Event(tag: Int, data: Int | Float)` shape. JVM/Native marshal the union field into
 * the struct on every backend; JS previously threw at codegen because koffiType had no UnionT case. */
typedef struct {
    int32_t tag;
    union {
        int32_t i;
        float   f;
    } data;
} kyo_it_tagged;

/* Reads the struct's plain `tag` field. */
int32_t kyo_it_tagged_tag(const kyo_it_tagged *t) {
    return t->tag;
}

/* Reads the int view of the struct's union field. */
int32_t kyo_it_tagged_int(const kyo_it_tagged *t) {
    return t->data.i;
}

/* Reads the float view of the struct's union field. */
float kyo_it_tagged_float(const kyo_it_tagged *t) {
    return t->data.f;
}

/* #253: a struct whose union field has a STRUCT variant, { int32_t label; union { int32_t i; struct {int32_t x,y;} p; } payload; }.
 * Exercises a union field whose variant is itself a struct (Int | UnionPoint), so the registered koffi union references
 * another registered struct type by name. Verifies that combination marshals on every backend. */
typedef struct {
    int32_t label;
    union {
        int32_t i;
        struct { int32_t x; int32_t y; } p;
    } payload;
} kyo_it_boxed;

/* Reads the plain `label` field. */
int32_t kyo_it_boxed_label(const kyo_it_boxed *b) {
    return b->label;
}

/* Reads the int view of the union field. */
int32_t kyo_it_boxed_int(const kyo_it_boxed *b) {
    return b->payload.i;
}

/* Reads the x field of the struct (point) variant of the union field. */
int32_t kyo_it_boxed_x(const kyo_it_boxed *b) {
    return b->payload.p.x;
}

/* Reads the y field of the struct (point) variant of the union field. */
int32_t kyo_it_boxed_y(const kyo_it_boxed *b) {
    return b->payload.p.y;
}
