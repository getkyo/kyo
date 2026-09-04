#ifndef KYO_NET_API_H
#define KYO_NET_API_H

/* Windows exports nothing from a DLL unless each symbol says so, where ELF and Mach-O export by
 * default. MinGW's ld auto-exports and hid this for as long as windows-x64 was the only Windows
 * pole; windows-arm64 compiles with MSVC (its MinGW is x64-only), where the library loads and
 * resolves nothing: koffi reports "Cannot find function ... in shared library" and Panama fails its
 * downcall lookup. kyo_aeron.h carries the same declaration for the same reason. */
#if defined(_WIN32)
#define KYO_NET_API __declspec(dllexport)
#else
#define KYO_NET_API
#endif

#endif /* KYO_NET_API_H */
