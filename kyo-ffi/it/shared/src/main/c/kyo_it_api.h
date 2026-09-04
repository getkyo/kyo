#ifndef KYO_IT_API_H
#define KYO_IT_API_H

/* Windows exports nothing from a DLL unless each symbol says so, where ELF and Mach-O export by
 * default. MinGW's ld auto-exports, which is what windows-x64 relied on; windows-arm64 compiles
 * with MSVC (its MinGW is x64-only), where the library loads and resolves nothing and every
 * binding lookup fails. kyo_aeron.h and kyo_net_api.h carry the same declaration. */
#if defined(_WIN32)
#define KYO_IT_API __declspec(dllexport)
#else
#define KYO_IT_API
#endif

#endif /* KYO_IT_API_H */
