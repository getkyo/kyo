/* kyo-ffi integration test: C enum mapping.
 * All "enum" values are plain ints at the C level.
 */
#include "kyo_it_api.h"

KYO_IT_API int kyo_it_color_value(int color) { return color; }
KYO_IT_API int kyo_it_color_get(int index)   { return index; }
KYO_IT_API int kyo_it_next_color(int color)  { return color + 1; }
