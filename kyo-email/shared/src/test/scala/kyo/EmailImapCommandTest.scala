package kyo

import kyo.EmailInvalidCommandException.Problem
import kyo.EmailLiterals.*

class EmailImapCommandTest extends kyo.test.Test[Any]:

    private def problem(value: String): Problem = refused(EmailImap.Command.init(value)).problem

    "keeps a command line as written" in {
        assert(imapCommandOf("UID SEARCH UNSEEN").value == "UID SEARCH UNSEEN")
        assert(imapCommandOf("GETQUOTAROOT \"INBOX\"").value == "GETQUOTAROOT \"INBOX\"")
    }

    "init fails on an empty command" in {
        assert(problem("") == Problem.Empty)
    }

    "init fails on CR, LF or NUL, which would end the line and let the rest run as another command" in {
        assert(problem("NOOP\r\nA1 LOGOUT") == Problem.LineBreakOrNul(4))
        assert(problem("NOOP\n") == Problem.LineBreakOrNul(4))
        assert(problem("NO\u0000OP") == Problem.LineBreakOrNul(2))
    }

    "init fails on a trailing literal, which would leave the server waiting for octets the command never sends" in {
        assert(problem("APPEND INBOX {12}") == Problem.Literal)
        assert(problem("APPEND INBOX {12+}") == Problem.Literal)
        assert(imapCommandOf("SEARCH SUBJECT \"{12}\" ALL").value == "SEARCH SUBJECT \"{12}\" ALL")
    }

    "init fails on a command whose exchange custom cannot carry, named by its first word in any case" in {
        def imap(command: String) = Problem.Unsupported(EmailException.Protocol.Imap, command)
        assert(problem("IDLE") == imap("IDLE"))
        assert(problem("idle") == imap("IDLE"))
        assert(problem("AUTHENTICATE PLAIN") == imap("AUTHENTICATE"))
        assert(problem("login user pass") == imap("LOGIN"))
        assert(problem("StartTLS") == imap("STARTTLS"))
        assert(problem("COMPRESS DEFLATE") == imap("COMPRESS"))
        assert(imapCommandOf("IDLEX").value == "IDLEX")
        assert(imapCommandOf("GETQUOTAROOT LOGIN").value == "GETQUOTAROOT LOGIN")
    }

    "init fails on a line longer than 8192 octets, RFC 7162's recommended limit" in {
        assert(imapCommandOf("X " + "a" * 8190).value.length == 8192)
        assert(problem("X " + "a" * 8191) == Problem.TooLong(8192))
    }

    "renders escaped, so a hostile value cannot forge a log line" in {
        assert(refused(EmailImap.Command.init("a\r\nb")).getMessage.contains("CR, LF or NUL at position 1"))
    }

end EmailImapCommandTest
