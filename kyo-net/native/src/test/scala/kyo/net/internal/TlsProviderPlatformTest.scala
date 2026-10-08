package kyo.net.internal

import kyo.*
import kyo.net.Test

class TlsProviderPlatformTest extends Test:

    import AllowUnsafe.embrace.danger

    "the loaded-library list names the C library, so an empty TLS answer is not a blind one" in {
        val libc = LoadedLibraries.paths().filter(p => p.contains("libSystem.") || p.contains("libc.so"))
        assert(libc.nonEmpty, s"no C library among ${LoadedLibraries.paths()}")
    }

    "a binary that carries BoringSSL loads no system libssl or libcrypto beside it" in {
        if !LoadedLibraries.boringSslLinked() then cancel("BoringSSL is not staged for this host, so this binary links the system OpenSSL")
        assert(LoadedLibraries.systemTls().isEmpty, s"system TLS libraries loaded beside BoringSSL: ${LoadedLibraries.systemTls()}")
    }

end TlsProviderPlatformTest
