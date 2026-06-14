/* kyo-ffi integration test: C enum mapping.
 * All "enum" values are plain ints at the C level.
 */

int kyo_it_color_value(int color) { return color; }
int kyo_it_color_get(int index)   { return index; }
int kyo_it_next_color(int color)  { return color + 1; }
