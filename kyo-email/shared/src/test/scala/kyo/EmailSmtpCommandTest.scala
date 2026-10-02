package kyo

import kyo.EmailInvalidCommandException.Problem
import kyo.EmailLiterals.*

class EmailSmtpCommandTest extends kyo.test.Test[Any]:

    private def problem(value: String): Problem = refused(EmailSmtp.Command.init(value)).problem

    "keeps a command line as written" in {
        assert(smtpCommandOf("VRFY postmaster").value == "VRFY postmaster")
    }

    "init fails on an empty command" in {
        assert(problem("") == Problem.Empty)
    }

    "init fails on CR, LF or NUL, which would end the line and let the rest run as another command" in {
        assert(problem("NOOP\r\nRCPT TO:<victim@example.com>") == Problem.LineBreakOrNul(4))
        assert(problem("\nNOOP") == Problem.LineBreakOrNul(0))
    }

    "init fails on a line longer than 510 octets, RFC 5321 section 4.5.3.1.4's 512 with its CRLF, counted in UTF-8" in {
        assert(smtpCommandOf("X" * 510).value.length == 510)
        assert(problem("X" * 511) == Problem.TooLong(510))
        assert(problem("X" * 509 + "é") == Problem.TooLong(510))
    }

    "init fails on a command whose exchange custom cannot carry, named by its first word in any case, with the reason in the message" in {
        def smtp(command: String) = Problem.Unsupported(EmailException.Protocol.Smtp, command)
        assert(problem("DATA") == smtp("DATA"))
        assert(problem("data") == smtp("DATA"))
        assert(problem("BDAT 100 LAST") == smtp("BDAT"))
        assert(problem("StartTLS") == smtp("STARTTLS"))
        assert(problem("AUTH PLAIN dXNlcgA=") == smtp("AUTH"))
        assert(problem("quit") == smtp("QUIT"))
        assert(smtpCommandOf("DATAX").value == "DATAX")
        assert(smtpCommandOf("VRFY QUIT").value == "VRFY QUIT")
        assert(refused(EmailSmtp.Command.init("DATA")).getMessage.contains(
            "SMTP DATA cannot be sent through custom: the message content follows it"
        ))
    }

end EmailSmtpCommandTest
