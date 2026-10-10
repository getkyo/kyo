package kyo.net.internal

import kyo.*
import kyo.net.NetTlsConfig
import kyo.net.NetTlsConfigException
import kyo.net.Test
import kyo.net.internal.backend.CapabilityOutcome

/** [[SslLibProvider]]'s shared config-failure typing, at the one seam [[BoringSslProviderConfiguredPemTest]] does not already cover: a
  * malformed-PEM `createEngine` failure is [[NetTlsConfigException]], DISJOINT from `Closed`. A caller folding only `Abort[Closed]` must not
  * silently catch this typed leaf; it panics instead, proving the two failure families never
  * alias. Also the capability probe over the shim bodies this host actually compiled.
  */
class SslLibProviderTest extends Test:

    import AllowUnsafe.embrace.danger

    private def unreadablePath(): String =
        val f = java.io.File.createTempFile("kyo-net-tls-lib-missing", ".pem")
        discard(f.delete())
        f.getAbsolutePath + "-does-not-exist"
    end unreadablePath

    "a malformed configured PEM path throws NetTlsConfigException carrying the path and the read cause" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        val path = unreadablePath()
        val ex   = intercept[NetTlsConfigException] {
            val engine = BoringSslProvider.createEngine(NetTlsConfig(caCertPath = Present(path)), "localhost", isServer = false)
            engine.free()
        }
        assert(ex.getMessage.contains(path), s"message must name the unreadable path, got ${ex.getMessage}")
        assert(
            ex.getMessage.contains("could not be read"),
            s"message must state the read-failure reason, got ${ex.getMessage}"
        )
    }

    "Abort.run[Closed] does not catch the malformed-PEM failure (it panics instead, proving the two families are disjoint)" in {
        if !TlsRealEngines.boringSslAvailable() then cancel("BoringSSL not staged for this host")
        val path = unreadablePath()
        Abort.run[Closed] {
            Abort.catching[Closed] {
                val engine = BoringSslProvider.createEngine(NetTlsConfig(caCertPath = Present(path)), "localhost", isServer = false)
                engine.free()
            }
        }.map { outcome =>
            outcome match
                case Result.Panic(ex: NetTlsConfigException) => succeed
                case other                                   => fail(s"expected a Panic wrapping NetTlsConfigException, got $other")
        }
    }

    "each native provider's probe reports the body its shim was compiled to" in {
        // The real shims on this host: a stub body must surface as CompiledStub, and a real body never may, whatever its SSL_CTX probe
        // says. A provider whose library this platform does not build at all (system OpenSSL on the JVM) fails to load and is not this
        // leaf's subject.
        val providers = Chunk[SslLibProvider](BoringSslProvider, SystemOpenSslProvider)
        val checked   = providers.filter { provider =>
            val stub =
                try Present(provider.lib.compiledStub())
                catch case _: Throwable => Absent
            stub.foreach { isStub =>
                if isStub then assert(provider.doProbe == provider.compiledStubOutcome, s"${provider.name}: got ${provider.doProbe}")
                else
                    provider.doProbe match
                        case _: CapabilityOutcome.CompiledStub => fail(s"${provider.name} compiled its real body but probed as a stub")
                        case _                                 => ()
            }
            stub.nonEmpty
        }
        if checked.isEmpty then cancel("no native TLS shim loads on this host")
        succeed
    }

end SslLibProviderTest
