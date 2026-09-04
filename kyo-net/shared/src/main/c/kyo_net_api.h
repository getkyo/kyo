#ifndef KYO_NET_API_H
#define KYO_NET_API_H

/* Windows exports nothing from a DLL unless each symbol says so, where ELF and Mach-O export by
 * default. MinGW's ld auto-exports and needs no attribute; MSVC, which compiles this library on
 * windows-arm64, exports only what is declared, and an undeclared symbol leaves the library
 * loadable but empty: koffi reports "Cannot find function ... in shared library" and Panama fails
 * its downcall lookup. Every entry point below the include therefore carries KYO_NET_API, as
 * kyo_aeron.h does for the same reason. */
#if defined(_WIN32)
#define KYO_NET_API __declspec(dllexport)
#else
#define KYO_NET_API
#endif

#endif /* KYO_NET_API_H */
