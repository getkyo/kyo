#ifndef KYO_IT_API_H
#define KYO_IT_API_H

/* Windows exports nothing from a DLL unless each symbol says so, where ELF and Mach-O export by
 * default. MinGW's ld auto-exports and needs no attribute; MSVC, which compiles this library on
 * windows-arm64, exports only what is declared, and an undeclared symbol leaves the library
 * loadable but empty. kyo_aeron.h and kyo_net_api.h carry the same declaration. */
#if defined(_WIN32)
#define KYO_IT_API __declspec(dllexport)
#else
#define KYO_IT_API
#endif

#endif /* KYO_IT_API_H */
