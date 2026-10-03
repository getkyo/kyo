package kyo

import kyo.EmailInvalidConfigException.Setting
import kyo.EmailInvalidConfigException.Violation
import kyo.EmailLiterals.*

class EmailSmtpConfigTest extends kyo.test.Test[Any]:

    private val password = "correct-horse-battery-staple"
    private val token    = "ya29.a0AfH6SMBx-access-token"
    private val auth     = passwordAccountOf("ada@example.com", passwordOf(password))
    private val base     = valid(EmailSmtpConfig.init("smtp.example.com", auth))

    private def violationOf(checked: Result[EmailInvalidConfigException, EmailSmtpConfig]): Maybe[Violation] =
        checked match
            case Result.Failure(invalid) => Present(invalid.violation)
            case _                       => Absent

    "port" - {
        "defaults to 465 for implicit TLS" in {
            assert(base.resolvedPort == 465)
        }
        "defaults to 587 for STARTTLS" in {
            assert(valid(EmailSmtpConfig.init("smtp.example.com", auth, tls = Email.Tls.StartTls)).resolvedPort == 587)
        }
        "uses an explicit port over the default" in {
            assert(valid(EmailSmtpConfig.init("smtp.example.com", auth, port = Present(2525))).resolvedPort == 2525)
        }
    }

    "clientName" - {
        val accepted = Chunk(
            "localhost",
            "mail.example.com",
            "a-1.example-2.org",
            "x",
            "[127.0.0.1]",
            "[255.255.255.255]",
            "[IPv6:2001:db8::1]",
            "[IPv6:2001:0db8:0000:0000:0000:0000:0000:0001]",
            "[IPv6:::1]",
            "[IPv6:::]",
            "[IPv6:::ffff:192.0.2.1]",
            "[ipv6:::1]",
            "[IPv6:1:2:3:4:5:6:192.0.2.1]",
            "[x-tag:some-content]"
        )
        val rejected = Chunk(
            "",
            "mail.example.com\r\nMAIL FROM:<x@y>",
            "-mail.example.com",
            "mail-.example.com",
            "mail..example.com",
            "mail.example.com.",
            "mail_server.example.com",
            "mäil.example.com",
            "[]",
            "[127.0.0.256]",
            "[127.0.0]",
            "[1.2.3.4.5]",
            "[IPv6:2001:db8::1::2]",
            "[IPv6:1:2:3:4:5:6:7]",
            "[IPv6:1:2:3:4:5:6:7:8:9]",
            "[IPv6:12345::1]",
            "[IPv6:1:2:3:4:5::192.0.2.1]",
            "[IPv6:1:2:3:4:5:6:7::]",
            "[x-tag:]",
            "[-tag:content]",
            "[x-tag:con[tent]",
            "[١٢٧.٠.٠.١]",
            "[１２７.0.0.1]",
            "[IPv6:2001:db8::١]",
            "[İPv6:::1]",
            "[ıPv6:::1]",
            "m١il.example.com",
            "127.0.0.1]"
        )
        accepted.foreach { name =>
            s"accepts ${name.replace("\r\n", "\\r\\n")}" in {
                assert(valid(EmailSmtpConfig.init("smtp.example.com", auth, clientName = name)).clientName == name)
            }
        }
        rejected.foreach { name =>
            s"rejects '${name.replace("\r\n", "\\r\\n")}'" in {
                assert(violationOf(EmailSmtpConfig.init("smtp.example.com", auth, clientName = name)) ==
                    Present(Violation.InvalidClientName(name)))
            }
        }
        "renders a rejected name escaped" in {
            EmailSmtpConfig.init("smtp.example.com", auth, clientName = "a\r\nb") match
                case Result.Failure(ex) =>
                    assert(ex.getMessage.contains("""clientName a\r\nb is neither a domain nor an address literal"""))
                case other => fail(s"expected a violation, got $other")
        }
    }

    "validation" - {
        "init fails on an empty host" in {
            assert(violationOf(EmailSmtpConfig.init("", auth)) == Present(Violation.EmptyHost))
        }
        "init fails on a port outside 1 to 65535" in {
            assert(violationOf(EmailSmtpConfig.init("smtp.example.com", auth, port = Present(-1))) == Present(Violation.PortOutOfRange(-1)))
        }
        "init fails on a zero connect timeout" in {
            assert(violationOf(EmailSmtpConfig.init("smtp.example.com", auth, connectTimeout = Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.ConnectTimeout, Duration.Zero)))
        }
        "init fails on a zero command timeout" in {
            assert(violationOf(EmailSmtpConfig.init("smtp.example.com", auth, commandTimeout = Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.CommandTimeout, Duration.Zero)))
        }
        "init fails on a TLS minVersion above maxVersion" in {
            val above = Email.Tls.StartTls(Email.Tls.Trust.System, Email.Tls.Version.TLS13, Email.Tls.Version.TLS12, Absent)
            assert(violationOf(EmailSmtpConfig.init("smtp.example.com", auth, tls = above)) ==
                Present(Violation.TlsVersionRange(Email.Tls.Version.TLS13, Email.Tls.Version.TLS12)))
        }
        "a setter checks the field it sets as init does" in {
            assert(violationOf(base.host("")) == Present(Violation.EmptyHost))
            assert(violationOf(base.port(Present(-1))) == Present(Violation.PortOutOfRange(-1)))
            assert(violationOf(base.clientName("a b")) == Present(Violation.InvalidClientName("a b")))
            assert(violationOf(base.connectTimeout(Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.ConnectTimeout, Duration.Zero)))
            assert(violationOf(base.commandTimeout(Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.CommandTimeout, Duration.Zero)))
            val above = Email.Tls.Implicit(Email.Tls.Trust.System, Email.Tls.Version.TLS13, Email.Tls.Version.TLS12, Absent)
            assert(violationOf(base.tls(above)) == Present(Violation.TlsVersionRange(Email.Tls.Version.TLS13, Email.Tls.Version.TLS12)))
        }
        "a setter replaces only its field" in {
            val changed = valid(base.host("mail.example.com").flatMap(_.clientName("mail.example.com")))
            assert(changed.host == "mail.example.com" && changed.clientName == "mail.example.com")
            assert((changed.account eq base.account) && changed.tls == base.tls && changed.commandTimeout == base.commandTimeout)
            val oauth = base.account(oauth2AccountOf("grace@example.com", tokenOf(token)))
            assert(oauth.account.user == "grace@example.com" && oauth.host == base.host)
        }
    }

    "retry" - {
        "is off by default, with a 60 second retryMaxDelay" in {
            assert(base.retry == Absent)
            assert(base.retryMaxDelay == 60.seconds)
        }
        "init and the setters take a schedule and a retryMaxDelay" in {
            val schedule = Schedule.fixed(10.seconds)
            val built    = valid(EmailSmtpConfig.init("smtp.example.com", auth, retry = Present(schedule), retryMaxDelay = 2.minutes))
            assert(built.retry == Present(schedule) && built.retryMaxDelay == 2.minutes)
            val set = valid(base.retry(Present(schedule)).retryMaxDelay(5.seconds))
            assert(set.retry == Present(schedule) && set.retryMaxDelay == 5.seconds && set.host == base.host)
            assert(set.retry(Absent).retry == Absent)
        }
        "a non-positive retryMaxDelay is a violation" in {
            assert(violationOf(EmailSmtpConfig.init("smtp.example.com", auth, retryMaxDelay = Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.RetryMaxDelay, Duration.Zero)))
            assert(violationOf(base.retryMaxDelay(Duration.Zero)) ==
                Present(Violation.DurationOutOfRange(Setting.RetryMaxDelay, Duration.Zero)))
        }
    }

    "renders no secret" - {
        "with a password" in {
            val rendered = base.toString
            assert(rendered.contains("smtp.example.com"))
            assert(!rendered.contains(password))
        }
        "with an OAuth token computation" in {
            val rendered = base.account(oauth2AccountOf("ada@example.com", tokenOf(token))).toString
            assert(rendered.contains("<token computation>"))
            assert(!rendered.contains(token))
        }
    }

end EmailSmtpConfigTest
