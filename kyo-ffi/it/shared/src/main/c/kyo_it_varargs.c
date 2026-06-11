#include <stdarg.h>
#include <stdint.h>

/* IT variadic surface (F8b #25).
 *
 * kyo_it_sum_varargs(count, ...): sums `count` int arguments supplied as C varargs.
 * Used by the cross-platform variadic IT spec on JVM + JS. Scala Native can
 * not express variadic `@extern` signatures, so its side of the test uses the
 * non-variadic wrapper `kyo_it_sum_fixed_3(a, b, c)` which packages three ints
 * into a regular fixed-arity call, mirroring the workaround-pointer pattern
 * the codegen directs users to when they hit the Native reject path.
 */

int kyo_it_sum_varargs(int count, ...) {
    va_list ap;
    va_start(ap, count);
    int total = 0;
    for (int i = 0; i < count; i++) {
        total += va_arg(ap, int);
    }
    va_end(ap);
    return total;
}

/* Non-variadic three-int sum. Used by Scala Native's IT spec as the
 * workaround-pointer pattern the F8b codegen rejects variadic bindings with.
 * Named without an underscore before `3` so the default snake_case derivation
 * from `kyoItSumFixed3` produces the matching symbol. */
int kyo_it_sum_fixed3(int a, int b, int c) {
    return a + b + c;
}
