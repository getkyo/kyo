#include <stdint.h>

/* MSVC exports nothing from a DLL unless it is asked to, where MinGW's ld auto-exports every
   symbol. windows-arm64 compiles with cl (its MinGW is x64-only), so without this the library
   loads and resolves no symbol. kyo_aeron.h carries the same declaration for the same reason. */
#if defined(_WIN32)
#define ROUND_TRIP_API __declspec(dllexport)
#else
#define ROUND_TRIP_API
#endif

ROUND_TRIP_API int32_t round_trip_add(int32_t a, int32_t b) {
    return a + b;
}
