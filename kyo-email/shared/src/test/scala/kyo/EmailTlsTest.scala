package kyo

class EmailTlsTest extends kyo.test.Test[Any]:

    import Email.Tls.*

    private val defaults = (Trust.System, Version.TLS12, Version.TLS13, Absent)

    "Implicit and StartTls are the default settings: the platform's trust store, TLS 1.2 to 1.3, no client certificate" in {
        Seq[Email.Tls](Implicit, StartTls).foreach { tls =>
            assert((tls.trust, tls.minVersion, tls.maxVersion, tls.clientCertificate) == defaults)
        }
    }

    "a factory given the default settings equals the value, and one given others holds them" in {
        assert(Implicit(Trust.System, Version.TLS12, Version.TLS13, Absent) == Implicit)
        assert(StartTls(Trust.System, Version.TLS12, Version.TLS13, Absent) == StartTls)
        assert(Implicit(Trust.System, Version.TLS12, Version.TLS13, Absent).hashCode == Implicit.hashCode)
        val pinned = StartTls(Trust.TrustAll, Version.TLS13, Version.TLS13, Absent)
        assert((pinned.trust, pinned.minVersion, pinned.maxVersion) == (Trust.TrustAll, Version.TLS13, Version.TLS13))
        assert(pinned != StartTls)
    }

    "the two modes differ with the same settings" in {
        assert((Implicit: Email.Tls) != (StartTls: Email.Tls))
        assert(Implicit(Trust.TrustAll, Version.TLS12, Version.TLS13, Absent) !=
            StartTls(Trust.TrustAll, Version.TLS12, Version.TLS13, Absent))
    }

    "a match tells the modes apart" in {
        def port(tls: Email.Tls): Int =
            tls match
                case _: Implicit => 993
                case _: StartTls => 143
        assert(port(Implicit) == 993 && port(StartTls) == 143)
        assert(port(StartTls(Trust.TrustAll, Version.TLS12, Version.TLS13, Absent)) == 143)
    }

end EmailTlsTest
