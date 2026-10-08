package kyo

import kyo.net.internal.LoadedLibraries

class HttpClientTlsLinkTest extends BaseHttpTest:

    import AllowUnsafe.embrace.danger

    "a binary that carries BoringSSL loads no system libssl or libcrypto beside it" in {
        if !LoadedLibraries.boringSslLinked() then cancel("BoringSSL is not staged for this host, so this binary links the system OpenSSL")
        assert(LoadedLibraries.systemTls().isEmpty, s"system TLS libraries loaded beside BoringSSL: ${LoadedLibraries.systemTls()}")
    }

end HttpClientTlsLinkTest
