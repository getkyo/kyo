#include <stdlib.h>
#include <string.h>

/* Struct with pointer-typed fields: opaque handle, C string, function pointer. */
typedef struct {
    void*       handle;
    const char* label;
    void        (*callback)(int);
} tagged_resource_t;

/* Read the opaque handle from a struct and dereference it as an int* to return the stored value. */
int kyo_it_struct_read_handle(tagged_resource_t* res) {
    if (!res->handle) return -1;
    return *((int*)res->handle);
}

/* Read the label string from a struct and return its length. */
int kyo_it_struct_label_len(tagged_resource_t* res) {
    if (!res->label) return -1;
    return (int)strlen(res->label);
}

/* Fire the callback stored in a struct with the given value. */
void kyo_it_struct_fire_callback(tagged_resource_t* res, int value) {
    if (res->callback) res->callback(value);
}
