package kyo.internal.email.smtp

import kyo.*
import kyo.internal.email.net.LineConnection
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.Certificate
import kyo.net.NetException
import kyo.net.NetTlsConfig

/** A scripted SMTP server on 127.0.0.1: each test writes the conversation it needs as a script over a [[SmtpTestServer.Peer]], which
  * records every line the client sent, the data section's lines included. It listens over TLS from the first byte with the localhost
  * certificate, which a config from `config` trusts through `Email.Tls.Trust.CaFile`, or in plaintext for a STARTTLS config.
  */
object SmtpTestServer:

    val User = "user@example.com"

    val Extensions = Seq("SIZE 10000000", "8BITMIME", "SMTPUTF8", "AUTH PLAIN LOGIN XOAUTH2")

    final class Server(val port: Int, log: AtomicRef[Chunk[String]], closed: Latch, accepted: AtomicInt):
        /** Every line the client sent, across its connections, in order. */
        def received(using Frame): Chunk[String] < Sync = log.get

        /** How many connections the server has accepted. */
        def connections(using Frame): Int < Sync = accepted.get

        /** Waits until a script's `untilClosed` saw the client close its connection. */
        def awaitClose(using Frame): Unit < Async = closed.await
    end Server

    final class Peer(val line: LineConnection, log: AtomicRef[Chunk[String]], closed: Latch, val index: Int):

        def send(lines: String*)(using Frame): Unit < (Async & Abort[EmailTransportException]) =
            Kyo.foreachDiscard(lines)(text => line.write("fixture", LineConnectionFixture.octets(text + "\r\n")))

        def receive(using Frame): String < (Async & Abort[EmailTransportException]) =
            line.readLine("fixture", 1 << 20).map { octets =>
                val text = LineConnectionFixture.text(octets)
                log.updateAndGet(_.append(text)).andThen(text)
            }

        /** Reads one command and sends `lines`; answers with the command. */
        def reply(lines: String*)(using Frame): String < (Async & Abort[EmailTransportException]) =
            receive.map(command => send(lines*).andThen(command))

        /** An `EHLO` answered with `extensions`, one per line after the greeting line. */
        def ehlo(extensions: Seq[String] = Extensions)(using Frame): String < (Async & Abort[EmailTransportException]) =
            val lines = "smtp.example.com greets you" +: extensions
            reply(lines.zipWithIndex.map((text, i) => if i == lines.size - 1 then s"250 $text" else s"250-$text")*)

        /** The greeting, `EHLO` answered with `extensions`, and `AUTH` accepted. */
        def ready(extensions: Seq[String] = Extensions)(using Frame): Unit < (Async & Abort[EmailTransportException]) =
            send("220 smtp.example.com ESMTP").andThen(ehlo(extensions)).andThen(reply("235 2.7.0 Authentication successful")).unit

        /** Reads the client's `QUIT` and answers it. */
        def quit(using Frame): Unit < (Async & Abort[EmailTransportException]) =
            reply("221 2.0.0 bye").unit

        /** The data section's lines up to the `.` line, which the log keeps too. */
        def data(using Frame): Chunk[String] < (Async & Abort[EmailTransportException]) =
            Loop(Chunk.empty[String]) { lines =>
                receive.map(text => if text == "." then Loop.done(lines) else Loop.continue(lines.append(text)))
            }

        def startTls(certificate: Certificate = Certificate.Localhost, adjust: NetTlsConfig => NetTlsConfig = identity)(using
            Frame
        ): Unit < (Async & Abort[EmailConnectException]) =
            LineConnectionFixture.serverTls(certificate, adjust).map(line.startTls("fixture", _, 5.seconds))

        /** Accepts whatever the client sends, `DATA` with its section, until the client closes the connection; `QUIT` is answered `221`. */
        def untilClosed(using Frame): Unit < Async =
            Abort.run[EmailTransportException] {
                Loop.foreach {
                    receive.map { command =>
                        if command == "DATA" then send("354 go ahead").andThen(data).andThen(send("250 2.0.0 queued"))
                        else if command == "QUIT" then send("221 2.0.0 bye")
                        else send("250 2.0.0 ok")
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
    )(using Frame): EmailSmtpConfig < Sync =
        LineConnectionFixture.pems(Certificate.Localhost).map { (ca, _) =>
            val chosen                  = trust.getOrElse(Email.Tls.Trust.CaFile(Path(ca)))
            val config: EmailSmtpConfig = EmailLiterals.valid(EmailSmtpConfig.init(
                "127.0.0.1",
                account,
                tls = if startTls then EmailLiterals.startTlsOf(chosen) else EmailLiterals.implicitTlsOf(chosen),
                port = Present(server.port)
            ))
            config
        }

end SmtpTestServer
