/*
 * kyo_net_resolve.c: blocking hostname resolution shim for kyo-net.
 *
 * The recursive `struct addrinfo` linked list that getaddrinfo fills (and the `struct addrinfo **`
 * out-parameter it takes) cannot be modeled by the kyo-ffi codegen: its TypeValidator rejects a
 * struct that can reach itself through a chain of struct fields. Binding getaddrinfo directly is
 * therefore impossible. This shim keeps the whole recursive structure INTERNAL to C and exposes a
 * flat, codegen-friendly signature: the caller passes a host string and a family hint and receives
 * the first matching raw address copied into a plain byte buffer plus the address family.
 *
 * getaddrinfo blocks (it consults /etc/hosts, the resolver, and the network), so the Scala binding
 * is annotated @Ffi.blocking: on Native the downcall runs on a scheduler carrier the BlockingMonitor
 * compensates for, so the fiber suspends and no carrier is permanently starved.
 *
 * Guarded on POSIX: netdb.h is part of POSIX and present on Linux, macOS, and BSD. The guard keeps
 * the translation unit empty on non-POSIX hosts (e.g. Windows) so the build does not fail where
 * netdb.h is absent.
 */

/* getaddrinfo, freeaddrinfo, struct addrinfo, and the EAI_* codes are POSIX.1-2001. On glibc they are
 * declared in <netdb.h> only when a feature-test macro requests at least that level, so it must be
 * defined before any system header is included. Without it a strict ISO-C clang on Linux (e.g. on
 * ubuntu:noble) treats getaddrinfo/freeaddrinfo/EAI_NONAME as implicit declarations and fails the
 * build; macOS/BSD declare them unconditionally, which is why this was latent until a Linux build. */
#ifndef _POSIX_C_SOURCE
#define _POSIX_C_SOURCE 200112L
#endif

#if !defined(_WIN32)

#include <netdb.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <string.h>

/*
 * copy_addr: copy the raw network-order address bytes out of one addrinfo node (4 bytes for AF_INET,
 * 16 for AF_INET6) into out_addr and record the family in out_family. Returns 1 if the node was a
 * usable A/AAAA result (and was copied), 0 otherwise.
 */
static int copy_addr(const struct addrinfo* cur, unsigned char* out_addr, int* out_family) {
    if (cur->ai_family == AF_INET) {
        struct sockaddr_in* sin = (struct sockaddr_in*)cur->ai_addr;
        memcpy(out_addr, &sin->sin_addr, 4);
        *out_family = AF_INET;
        return 1;
    } else if (cur->ai_family == AF_INET6) {
        struct sockaddr_in6* sin6 = (struct sockaddr_in6*)cur->ai_addr;
        memcpy(out_addr, &sin6->sin6_addr, 16);
        *out_family = AF_INET6;
        return 1;
    }
    return 0;
}

/*
 * kyo_net_resolve: resolve `host` to one A (IPv4) or AAAA (IPv6) address, matching JVM behaviour.
 *
 *   host        NUL-terminated hostname (or a numeric literal getaddrinfo accepts).
 *   family_hint AF_INET, AF_INET6, or AF_UNSPEC (0): the PREFERRED family, advisory only. getaddrinfo
 *               is always asked with AF_UNSPEC so the resolver returns whatever families the host has;
 *               we then prefer the first result whose family equals family_hint, and fall back to the
 *               first A/AAAA of any family when the hint family is absent (or the hint is AF_UNSPEC).
 *               Not forcing the hint into the getaddrinfo hints is what lets a v6-only host resolve on
 *               Native, matching JVM's InetAddress.getByName (which ignores any family preference and
 *               returns the resolver's first answer) rather than failing EAI_* for a missing A record.
 *   out_addr    receives the raw network-order address bytes: 4 for AF_INET (sin_addr), 16 for
 *               AF_INET6 (sin6_addr). The caller must size it for at least 16 bytes.
 *   out_family  receives the resolved address family (AF_INET or AF_INET6).
 *
 * Returns 0 on success (out_addr / out_family filled), or the non-zero getaddrinfo error code (an
 * EAI_* value) when resolution fails. A successful getaddrinfo with no AF_INET/AF_INET6 result (it
 * cannot normally happen for a TCP/IP socktype) returns EAI_NONAME so the caller fails cleanly rather
 * than reading an uninitialized buffer. freeaddrinfo is always called on a successful getaddrinfo,
 * so the recursive list never leaks.
 */
int kyo_net_resolve(const char* host, int family_hint, unsigned char* out_addr, int* out_family) {
    struct addrinfo hints;
    struct addrinfo* res = NULL;
    struct addrinfo* cur = NULL;
    int rc;

    memset(&hints, 0, sizeof(hints));
    /* AF_UNSPEC: never restrict the resolver to family_hint, so a v6-only (or v4-only) host still
     * resolves and Native matches JVM. family_hint is applied below as a preference among results. */
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;     /* TCP: one result per address rather than one per socktype */

    rc = getaddrinfo(host, NULL, &hints, &res);
    if (rc != 0) {
        return rc;
    }

    /* First pass: honour the caller's preferred family if it is present among the results. */
    if (family_hint == AF_INET || family_hint == AF_INET6) {
        for (cur = res; cur != NULL; cur = cur->ai_next) {
            if (cur->ai_family == family_hint && copy_addr(cur, out_addr, out_family)) {
                freeaddrinfo(res);
                return 0;
            }
        }
    }

    /* Fallback: the preferred family was absent (or the hint was AF_UNSPEC), so take the first usable
     * A/AAAA of any family, whichever the resolver returned first. */
    for (cur = res; cur != NULL; cur = cur->ai_next) {
        if (copy_addr(cur, out_addr, out_family)) {
            freeaddrinfo(res);
            return 0;
        }
    }

    freeaddrinfo(res);
    return EAI_NONAME; /* no usable A/AAAA result */
}

#else

/*
 * Non-POSIX (Windows): empty translation unit. ISO C forbids a file with no external declarations,
 * so emit one harmless typedef to keep the compiler quiet.
 */
typedef int kyo_net_resolve_unavailable_t;

#endif
