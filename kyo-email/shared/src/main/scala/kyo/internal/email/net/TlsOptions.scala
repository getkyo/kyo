package kyo.internal.email.net

import kyo.*
import kyo.net.NetTlsConfig

/** The kyo-net TLS configuration an [[Email.Tls]] asks for, shared by the IMAP and SMTP sessions. `LineConnection` adds the SNI name and
  * the handshake deadline, which the module owns.
  */
private[kyo] object TlsOptions:

    def netConfig(tls: Email.Tls): NetTlsConfig =
        val trusted = tls.trust match
            case Email.Tls.Trust.System       => NetTlsConfig()
            case Email.Tls.Trust.CaFile(path) => NetTlsConfig(caCertPath = Present(path.toString))
            case Email.Tls.Trust.TrustAll     => NetTlsConfig(trustAll = true)
        trusted.copy(
            minVersion = version(tls.minVersion),
            maxVersion = version(tls.maxVersion),
            certChainPath = tls.clientCertificate.map(_.chain.toString),
            privateKeyPath = tls.clientCertificate.map(_.privateKey.toString)
        )
    end netConfig

    private def version(value: Email.Tls.Version): NetTlsConfig.Version =
        value match
            case Email.Tls.Version.TLS12 => NetTlsConfig.Version.TLS12
            case Email.Tls.Version.TLS13 => NetTlsConfig.Version.TLS13

end TlsOptions
