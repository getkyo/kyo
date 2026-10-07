package kyo

import kyo.EmailInvalidConfigException.Setting
import kyo.EmailInvalidConfigException.Violation
import kyo.EmailLiterals.*
import kyo.internal.email.net.LineConnectionFixture

class EmailImapConfigTest extends kyo.test.Test[Any]:

    private val password = "correct-horse-battery-staple"
    private val token    = "ya29.a0AfH6SMBx-access-token"
    private val auth     = passwordAccountOf("ada@example.com", passwordOf(password))
    private val base     = valid(EmailImapConfig.init("imap.example.com", auth))

    private def violationOf(checked: Result[EmailInvalidConfigException, EmailImapConfig]): Maybe[Violation] =
        checked match
            case Result.Failure(invalid) => Present(invalid.violation)
            case _                       => Absent

    private def tls(min: Email.Tls.Version, max: Email.Tls.Version): Email.Tls =
        Email.Tls.Implicit(Email.Tls.Trust.System, min, max, Absent)

    "port" - {
        "defaults to 993 for implicit TLS" in {
            assert(base.resolvedPort == 993)
        }
        "defaults to 143 for STARTTLS" in {
            assert(valid(EmailImapConfig.init("imap.example.com", auth, tls = Email.Tls.StartTls)).resolvedPort == 143)
        }
        "uses an explicit port over the default" in {
            assert(valid(EmailImapConfig.init("imap.example.com", auth, port = Present(1993))).resolvedPort == 1993)
        }
    }

    "validation" - {
        "init fails on an empty host" in {
            assert(violationOf(EmailImapConfig.init("", auth)) == Present(Violation.EmptyHost))
        }
        "init fails on a port below 1" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, port = Present(0))) == Present(Violation.PortOutOfRange(0)))
        }
        "init fails on a port above 65535" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, port = Present(65536))) ==
                Present(Violation.PortOutOfRange(65536)))
        }
        "init fails on a zero connect timeout" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, connectTimeout = Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.ConnectTimeout, Duration.Zero)))
        }
        "init fails on a zero command timeout" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, commandTimeout = Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.CommandTimeout, Duration.Zero)))
        }
        "init keeps any maxResponseLength: 0 bytes, 1 byte, and past Int.MaxValue bytes" in {
            val sizes = Seq(0.bytes, 1.bytes, (Int.MaxValue.toLong + 1).bytes)
            assert(sizes.map(size => valid(EmailImapConfig.init("imap.example.com", auth, maxResponseLength = size)).maxResponseLength) ==
                sizes)
        }
        "accepts infinite timeouts" in {
            val infinite =
                valid(EmailImapConfig.init(
                    "imap.example.com",
                    auth,
                    connectTimeout = Duration.Infinity,
                    commandTimeout = Duration.Infinity
                ))
            assert(infinite.connectTimeout == Duration.Infinity && infinite.commandTimeout == Duration.Infinity)
        }
        "accepts an IDLE renewal of exactly 29 minutes" in {
            assert(valid(EmailImapConfig.init("imap.example.com", auth, idleRenewal = EmailImapConfig.maxIdleRenewal)).idleRenewal ==
                29.minutes)
        }
        "init fails on an IDLE renewal past 29 minutes" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, idleRenewal = 30.minutes)) ==
                Present(Violation.DurationOutOfRange(Setting.IdleRenewal, 30.minutes)))
        }
        "init fails on a zero IDLE renewal" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, idleRenewal = Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.IdleRenewal, Duration.Zero)))
        }
        "init fails on an infinite poll interval" in {
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, pollInterval = Duration.Infinity)) ==
                Present(Violation.DurationOutOfRange(Setting.PollInterval, Duration.Infinity)))
        }
        "accepts a poll interval of exactly 29 minutes" in {
            assert(valid(EmailImapConfig.init("imap.example.com", auth, pollInterval = EmailImapConfig.maxPollInterval)).pollInterval ==
                29.minutes)
        }
        "init fails on a poll interval past 29 minutes, naming the range" in {
            EmailImapConfig.init("imap.example.com", auth, pollInterval = 30.minutes) match
                case Result.Failure(ex) =>
                    assert(ex.violation == Violation.DurationOutOfRange(Setting.PollInterval, 30.minutes))
                    assert(ex.getMessage.contains("pollInterval"))
                    assert(ex.getMessage.contains("at most 29 minutes"))
                case other => fail(s"expected a violation, got $other")
        }
        "init fails on a TLS minVersion above maxVersion, in either mode, and accepts one version" in {
            val range = Present(Violation.TlsVersionRange(Email.Tls.Version.TLS13, Email.Tls.Version.TLS12))
            assert(violationOf(EmailImapConfig.init(
                "imap.example.com",
                auth,
                tls = tls(Email.Tls.Version.TLS13, Email.Tls.Version.TLS12)
            )) ==
                range)
            val startTls = Email.Tls.StartTls(Email.Tls.Trust.System, Email.Tls.Version.TLS13, Email.Tls.Version.TLS12, Absent)
            assert(violationOf(EmailImapConfig.init("imap.example.com", auth, tls = startTls)) == range)
            val only = tls(Email.Tls.Version.TLS13, Email.Tls.Version.TLS13)
            assert(valid(EmailImapConfig.init("imap.example.com", auth, tls = only)).tls == only)
        }
        "a setter checks the field it sets as init does" in {
            assert(violationOf(base.host("")) == Present(Violation.EmptyHost))
            assert(violationOf(base.port(Present(0))) == Present(Violation.PortOutOfRange(0)))
            assert(violationOf(base.tls(tls(Email.Tls.Version.TLS13, Email.Tls.Version.TLS12))) ==
                Present(Violation.TlsVersionRange(Email.Tls.Version.TLS13, Email.Tls.Version.TLS12)))
            assert(violationOf(base.connectTimeout(Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.ConnectTimeout, Duration.Zero)))
            assert(violationOf(base.commandTimeout(Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.CommandTimeout, Duration.Zero)))
            assert(violationOf(base.idleRenewal(30.minutes)) == Present(Violation.DurationOutOfRange(Setting.IdleRenewal, 30.minutes)))
            assert(violationOf(base.pollInterval(30.minutes)) == Present(Violation.DurationOutOfRange(Setting.PollInterval, 30.minutes)))
        }
        "a setter replaces only its field" in {
            val changed = valid(base.host("mail.example.com").flatMap(_.commandTimeout(1.day)).flatMap(_.port(Present(1993))))
            assert(changed.host == "mail.example.com" && changed.commandTimeout == 1.day && changed.port == Present(1993))
            assert((changed.account eq base.account) && changed.tls == base.tls && changed.pollInterval == base.pollInterval)
            val past = base.maxResponseLength((Int.MaxValue.toLong + 1).bytes)
            assert(past.maxResponseLength == (Int.MaxValue.toLong + 1).bytes && past.host == base.host)
            val never = base.reconnect(Schedule.never)
            assert(never.reconnect == Schedule.never && never.host == base.host)
            val oauth = base.account(oauth2AccountOf("grace@example.com", tokenOf(token)))
            assert(oauth.account.user == "grace@example.com" && oauth.host == base.host)
        }
        "names the setting and its range in the message" in {
            EmailImapConfig.init("imap.example.com", auth, idleRenewal = 30.minutes) match
                case Result.Failure(ex) =>
                    assert(ex.getMessage.contains("idleRenewal"))
                    assert(ex.getMessage.contains("at most 29 minutes"))
                case other => fail(s"expected a violation, got $other")
        }
    }

    "renders no secret" - {
        "with a password" in {
            val rendered = base.toString
            assert(rendered.contains("imap.example.com"))
            assert(!rendered.contains(password))
        }
        "with an OAuth token computation" in {
            val computation = tokenOf(token): Email.OAuthToken < (Async & Abort[EmailTokenException])
            val rendered    = valid(EmailImapConfig.init("imap.example.com", oauth2AccountOf("ada@example.com", computation))).toString
            computation.map { issued =>
                assert(issued.value == token)
                assert(!rendered.contains(token))
            }
        }
        "with a client certificate: its paths, and no line of the key" in {
            LineConnectionFixture.clientCertificate.map { certificate =>
                val withCertificate =
                    Email.Tls.Implicit(Email.Tls.Trust.System, Email.Tls.Version.TLS12, Email.Tls.Version.TLS13, Present(certificate))
                val rendered = valid(EmailImapConfig.init("imap.example.com", auth, tls = withCertificate)).toString
                val body     = kyo.net.TlsTestCertShared.keyPem.linesIterator.filter(l => l.nonEmpty && !l.startsWith("-----")).toList
                assert(body.nonEmpty)
                assert(rendered.contains(certificate.privateKey.toString) && rendered.contains(certificate.chain.toString))
                assert(!body.exists(rendered.contains))
            }
        }
    }

end EmailImapConfigTest
