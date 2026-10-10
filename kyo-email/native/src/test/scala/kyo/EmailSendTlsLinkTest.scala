package kyo

import kyo.net.internal.LoadedLibraries

/** SMTP and IMAP reach kyo-net's TLS shims, so this binary links whatever TLS kyo-net's in-build flags carry, beside whatever this module's
  * own Native settings add. With BoringSSL staged that has to be BoringSSL alone: a system libssl loaded next to it is a second TLS stack
  * the process never selects.
  */
class EmailSendTlsLinkTest extends kyo.test.Test[Any]:

    import AllowUnsafe.embrace.danger

    "a binary that carries BoringSSL loads no system libssl or libcrypto beside it" in {
        if !LoadedLibraries.boringSslLinked() then cancel("BoringSSL is not staged for this host, so this binary links the system OpenSSL")
        assert(LoadedLibraries.systemTls().isEmpty, s"system TLS libraries loaded beside BoringSSL: ${LoadedLibraries.systemTls()}")
    }

end EmailSendTlsLinkTest
