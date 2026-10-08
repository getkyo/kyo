package kyo.net.fixture

import kyo.*
import kyo.net.internal.TlsProviderPlatform
import kyo.net.internal.backend.IoBackendPlatform

/** What a consumer binary selects at runtime, one `kyo-consumer <key>=<value>` line per fact, for scripts/native-consumer-check.sh.
  *
  * It lives under `kyo.net` because the registries are `private[net]`. The startup log line names only the winners, and only once a
  * transport or a TLS engine is built; a host-dependent expectation needs every candidate's probe outcome, which only the registries carry.
  */
object ConsumerReport:

    def print(app: String): Unit =
        import AllowUnsafe.embrace.danger
        given Frame = Frame.internal
        val provider = Result(TlsProviderPlatform.selected.name).getOrElse("none")
        val lines =
            Chunk(s"app=$app") ++
                IoBackendPlatform.registered.map(b => s"io.${b.name}=${b.probe.status}") ++
                Chunk(s"io_backend=${Result(IoBackendPlatform.selected.name).getOrElse("none")}") ++
                TlsProviderPlatform.registered.map(p => s"tls.${p.name}=${p.probe.status}") ++
                Chunk(s"tls_provider=$provider")
        lines.foreach(line => println(s"kyo-consumer $line"))
        val handshake = if provider == "none" then "skipped (no TLS provider is available)" else LoopbackTls.outcome()
        println(s"kyo-consumer tls_handshake=$handshake")
    end print

end ConsumerReport
