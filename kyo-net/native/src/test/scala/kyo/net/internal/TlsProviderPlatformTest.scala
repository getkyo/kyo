package kyo.net.internal

import kyo.*
import kyo.net.Test

class TlsProviderPlatformTest extends Test:

    import AllowUnsafe.embrace.danger

    "the loaded-library list names the C library, so an empty TLS answer is not a blind one" in {
        // musl's C library is its dynamic loader, so /proc/self/maps names ld-musl-<arch>.so.1 and no libc.so there.
        val libc = LoadedLibraries.paths().filter(p => p.contains("libSystem.") || p.contains("libc.so") || p.contains("ld-musl-"))
        assert(libc.nonEmpty, s"no C library among ${LoadedLibraries.paths()}")
    }

    "a binary that carries BoringSSL loads no system libssl or libcrypto beside it" in {
        if !LoadedLibraries.boringSslLinked() then cancel("BoringSSL is not staged for this host, so this binary links the system OpenSSL")
        assert(LoadedLibraries.systemTls().isEmpty, s"system TLS libraries loaded beside BoringSSL: ${LoadedLibraries.systemTls()}")
    }

end TlsProviderPlatformTest
