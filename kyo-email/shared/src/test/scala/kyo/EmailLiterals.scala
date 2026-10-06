package kyo

/** Values a test knows to be valid, built through the checked constructors: a violation is a defect in the test itself. */
object EmailLiterals:

    def valid[E, A](checked: Result[E, A]): A =
        checked match
            case Result.Success(value) => value
            case other                 => throw new AssertionError(s"the test literal is invalid: $other")

    def instantOf(iso8601: String): Instant = valid(Instant.parse(iso8601))

    def messageIdOf(value: String)(using Frame): Email.MessageId = valid(Email.MessageId.init(value))

    def contentIdOf(value: String)(using Frame): Email.ContentId = valid(Email.ContentId.init(value))

    def mailboxOf(value: String)(using Frame): Email.MailboxName = valid(Email.MailboxName.init(value))

    def uidValidityOf(value: Long)(using Frame): Email.UidValidity = valid(Email.UidValidity.init(value))

    def uidOf(mailbox: Email.MailboxName, validity: Email.UidValidity, value: Long)(using Frame): Email.Uid =
        valid(Email.Uid.init(mailbox, validity, value))

    def keywordOf(name: String)(using Frame): Email.Flag.Keyword = valid(Email.Flag.Keyword.init(name))

    def mediaTypeOf(mainType: String, subType: String, parameters: (String, String)*)(using Frame): Email.MediaType =
        valid(Email.MediaType.init(mainType, subType, parameters*))

    def passwordOf(value: String)(using Frame): Email.Password = valid(Email.Password.init(value))

    def tokenOf(value: String)(using Frame): Email.OAuthToken = valid(Email.OAuthToken.init(value))

    def passwordAccountOf(user: String, password: Email.Password)(using Frame): Email.Account =
        valid(Email.Account.init(user, Email.Auth.Password(password)))

    def oauth2AccountOf(user: String, token: Email.OAuthToken < (Async & Abort[EmailTokenException]))(using Frame): Email.Account =
        valid(Email.Account.init(user, Email.Auth.OAuth2(token)))

    def imapCommandOf(value: String)(using Frame): EmailReceive.Command = valid(EmailReceive.Command.init(value))

    def statusCodeOf(statusClass: EmailSend.EnhancedStatusCode.StatusClass, subject: Int, detail: Int)(using
        Frame
    ): EmailSend.EnhancedStatusCode =
        valid(EmailSend.EnhancedStatusCode.init(statusClass, subject, detail))

    def headerKeyOf(name: String, value: String)(using Frame): EmailReceive.Search.Header =
        valid(EmailReceive.Search.Header.init(name, value))

    /** The failure of `checked`, failing the test when it succeeded. */
    def refused[E, A](checked: Result[E, A]): E =
        checked match
            case Result.Failure(failure) => failure
            case other                   => throw new AssertionError(s"expected a failure, got $other")

    def implicitTlsOf(
        trust: Email.Tls.Trust,
        minVersion: Email.Tls.Version = Email.Tls.Version.TLS12,
        maxVersion: Email.Tls.Version = Email.Tls.Version.TLS13,
        clientCertificate: Maybe[Email.Tls.ClientCertificate] = Absent
    ): Email.Tls.Implicit = Email.Tls.Implicit(trust, minVersion, maxVersion, clientCertificate)

    def startTlsOf(
        trust: Email.Tls.Trust,
        minVersion: Email.Tls.Version = Email.Tls.Version.TLS12,
        maxVersion: Email.Tls.Version = Email.Tls.Version.TLS13,
        clientCertificate: Maybe[Email.Tls.ClientCertificate] = Absent
    ): Email.Tls.StartTls = Email.Tls.StartTls(trust, minVersion, maxVersion, clientCertificate)

end EmailLiterals
