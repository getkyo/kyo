package kyo.internal.email.imap

import kyo.*
import kyo.internal.email.net.LineConnection
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.Certificate
import kyo.net.NetException
import kyo.net.NetTlsConfig

/** A scripted IMAP server on 127.0.0.1: each test writes the conversation it needs as a script over a [[ImapTestServer.Peer]], which
  * records every line the client sent. It listens over TLS from the first byte with the localhost certificate, which a config from `config`
  * trusts through `Email.Tls.Trust.CaFile`, or in plaintext for a STARTTLS config.
  */
object ImapTestServer:

    val User = "user@example.com"

    val Capabilities = "IMAP4rev1 AUTH=PLAIN SASL-IR"

    final class Server(val port: Int, log: AtomicRef[Chunk[String]], closed: Latch, accepted: AtomicInt):
        /** Every line the client sent, across its connections, in order. */
        def received(using Frame): Chunk[String] < Sync = log.get

        /** How many connections the server has accepted. */
        def connections(using Frame): Int < Sync = accepted.get

        /** Waits until a script's `untilClosed` saw the client close its connection. */
        def awaitClose(using Frame): Unit < Async = closed.await
    end Server

    /** One accepted connection; `index` counts the server's connections from 1, so a script can tell a reconnect from the first. */
    final class Peer(val line: LineConnection, log: AtomicRef[Chunk[String]], closed: Latch, val index: Int):

        def send(text: String)(using Frame): Unit < (Async & Abort[EmailTransportException]) =
            line.write("fixture", LineConnectionFixture.octets(text + "\r\n"))

        def receive(using Frame): String < (Async & Abort[EmailTransportException]) =
            line.readLine("fixture", 1 << 20).map { octets =>
                val text = LineConnectionFixture.text(octets)
                log.updateAndGet(_.append(text)).andThen(text)
            }

        /** One command line, as its tag and the rest. */
        def command(using Frame): (String, String) < (Async & Abort[EmailTransportException]) =
            receive.map { text =>
                val space = text.indexOf(' ')
                if space < 0 then (text, "") else (text.take(space), text.drop(space + 1))
            }

        /** Answers the next command `OK` with `code`, returning the command. */
        def ok(code: String = "")(using Frame): String < (Async & Abort[EmailTransportException]) =
            command.map((tag, rest) => send(s"$tag OK ${if code.isEmpty then "" else s"[$code] "}done").andThen(rest))

        def startTls(certificate: Certificate = Certificate.Localhost, adjust: NetTlsConfig => NetTlsConfig = identity)(using
            Frame
        ): Unit < (Async & Abort[EmailConnectException]) =
            LineConnectionFixture.serverTls(certificate, adjust).map(line.startTls("fixture", _, 5.seconds))

        /** The greeting advertising `capabilities`, then `OK` to the authentication advertising `authenticated`. */
        def ready(capabilities: String = Capabilities, authenticated: String = "IMAP4rev1")(using
            Frame
        ): Unit < (Async & Abort[EmailTransportException]) =
            send(s"* OK [CAPABILITY $capabilities] ready").andThen(ok(s"CAPABILITY $authenticated").unit)

        /** Reads one command and sends `lines`, each `TAG` in them replaced by the command's tag; answers with the command. */
        def answer(lines: String*)(using Frame): String < (Async & Abort[EmailTransportException]) =
            command.map((tag, rest) => Kyo.foreachDiscard(lines)(line => send(line.replace("TAG", tag))).andThen(rest))

        /** A `SELECT` of a mailbox with UIDVALIDITY `validity` and UIDNEXT `uidNext`. */
        def select(validity: Long, uidNext: Long = 10)(using Frame): String < (Async & Abort[EmailTransportException]) =
            answer(
                "* 3 EXISTS",
                s"* OK [UIDVALIDITY $validity] UIDs valid",
                s"* OK [UIDNEXT $uidNext] next",
                "TAG OK [READ-WRITE] selected"
            )

        /** An `IDLE` (RFC 2177): the continuation, then `lines`, then the completion once the client sends `DONE`. */
        def idle(lines: String*)(using Frame): Unit < (Async & Abort[EmailTransportException]) =
            command.map { (tag, _) =>
                send("+ idling")
                    .andThen(Kyo.foreachDiscard(lines)(send))
                    .andThen(receive)
                    .andThen(send(s"$tag OK IDLE terminated"))
            }

        /** Answers `LOGOUT` as RFC 9051 has it and any other command `OK`, until the client closes the connection. */
        def untilClosed(using Frame): Unit < Async =
            Abort.run[EmailTransportException] {
                Loop.foreach {
                    command.map { (tag, rest) =>
                        if rest == "LOGOUT" then send("* BYE logging out").andThen(send(s"$tag OK LOGOUT completed"))
                        else send(s"$tag OK done")
                    }.andThen(Loop.continue)
                }
            }.andThen(closed.release)
    end Peer

    /** Listens, over TLS with `certificate` and `adjust` unless `tls` is false, and runs `script` for each connection. */
    def serve(tls: Boolean = true, certificate: Certificate = Certificate.Localhost, adjust: NetTlsConfig => NetTlsConfig = identity)(
        script: Peer => Any < (Async & Abort[LineConnectionFixture.Failure])
    )(using Frame): Server < (Async & Scope & Abort[NetException]) =
        for
            log         <- AtomicRef.init(Chunk.empty[String])
            closed      <- Latch.init(1)
            connections <- AtomicInt.init
            server      <-
                (if tls then LineConnectionFixture.serverTls(certificate, adjust).map(Maybe(_)) else Maybe.empty[NetTlsConfig]): Maybe[
                    NetTlsConfig
                ] < Sync
            port <- LineConnectionFixture.listen(server)(line =>
                connections.incrementAndGet.map(index => script(new Peer(line, log, closed, index)))
            )
        yield new Server(port, log, closed, connections)

    /** A config for `server`, trusting the localhost certificate through a CA file. */
    def config(
        server: Server,
        account: Email.Account,
        startTls: Boolean = false,
        trust: Maybe[Email.Tls.Trust] = Absent
    )(using Frame): EmailImapConfig < Sync =
        LineConnectionFixture.pems(Certificate.Localhost).map { (ca, _) =>
            val chosen                  = trust.getOrElse(Email.Tls.Trust.CaFile(Path(ca)))
            val config: EmailImapConfig = EmailLiterals.valid(EmailImapConfig.init(
                "127.0.0.1",
                account,
                tls = if startTls then EmailLiterals.startTlsOf(chosen) else EmailLiterals.implicitTlsOf(chosen),
                port = Present(server.port)
            ))
            config
        }

end ImapTestServer
