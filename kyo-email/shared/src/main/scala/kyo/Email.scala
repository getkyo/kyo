package kyo

import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.charset.ImapModifiedUtf7
import kyo.internal.email.mime.HeaderCodec
import kyo.internal.email.mime.MediaTypeDefaults
import kyo.internal.email.mime.MimeModel
import kyo.internal.email.mime.MimeParser
import kyo.internal.email.mime.MimeRenderer
import scala.annotation.tailrec

/** What the IMAP and SMTP clients share: the message model, the credentials and TLS mode both connect with, and the typed identifiers of
  * mail.
  *
  * [[Email.Message]] is one model for received and outgoing mail, with [[Email.Address]], [[Email.Header]], [[Email.MediaType]],
  * [[Email.Attachment]] and [[Email.InlinePart]] as its parts. [[Email.Flag]] is a flag on a message in a mailbox. [[Email.Auth]] holds a
  * [[Email.Password]] or a computation of an [[Email.OAuthToken]], and [[Email.Tls]] says how a connection is secured.
  *
  * The identifiers are [[Email.MessageId]], [[Email.ContentId]], [[Email.MailboxName]], [[Email.UidValidity]] and [[Email.Uid]]. Each kind
  * is its own type, so a mailbox name cannot be passed where a message id is required, and a bare number cannot stand in for a UID. Every
  * kind carries a `Schema`, so a caller can persist one (a resume checkpoint, a thread index), and a `CanEqual`.
  *
  * A UID means nothing on its own. RFC 3501 section 2.3.1.1 makes the combination of mailbox name, UIDVALIDITY and UID the identity of a
  * message, and a server that renumbers a mailbox changes its UIDVALIDITY, which invalidates every UID issued under the old value.
  * [[Email.Uid]] therefore carries all three: two UIDs are equal only when their mailbox, validity and number all match. The session checks
  * a UID's validity against the mailbox's current one before using it and fails with [[EmailUidValidityChangedException]] when they differ.
  *
  * An identifier's `init` validates the value and fails with [[EmailInvalidIdException]] on an impossible one: an empty id or name, a
  * control character (which could end an IMAP command or a header line early and inject another), a `<`, `>` or space inside a message or
  * content id, a `(` outside a quoted string or a quoted string that does not close inside one, or a UID outside the non-zero 32-bit
  * unsigned range. Only spaces around an id are removed. Decoding through a `Schema` applies the same checks.
  *
  * @see
  *   [[kyo.EmailImap]] for reading mail
  * @see
  *   [[kyo.EmailSmtp]] for sending it
  */
object Email:

    /** An email message as a typed model: its addresses, subject, date, threading ids, bodies, attachments, inline parts and headers.
      *
      * The same model describes a message received from a mailbox and a message to send. A received message is parsed from its MIME form,
      * with transfer encodings, encoded words (RFC 2047) and charsets decoded; a message to send is rendered to MIME from these fields.
      * Parsing a rendered message yields the fields it was rendered from, for bodies whose line ends are LF alone: MIME carries text with
      * CRLF line ends (RFC 2046 section 4.1.1), so a parsed body always has them as LF.
      *
      * The bodies are text: `text` is the `text/plain` body, empty when the message has none, and `html` is the `text/html` body when there
      * is one. When a message offers both, as a `multipart/alternative`, both are filled. Other parts become [[Email.Attachment]]s, or
      * [[Email.InlinePart]]s when their structure marks them as shown inside the HTML body.
      *
      * `from` names the authors and `sender` the mailbox that actually sent the message. RFC 5322 section 3.6.2 requires a `sender` when
      * `from` holds more than one address, so submitting such a message without one fails with [[EmailIncompleteMessageException]].
      *
      * Threading comes from three headers (RFC 5322 section 3.6.4). `messageId` is this message's own id, `inReplyTo` names the message it
      * answers, and `references` lists the thread's ids from its root. A reply sets the last two from the message it answers so mail
      * clients keep it in the thread. A message to send without a `messageId` or `date` gets one when it is submitted.
      *
      * `headers` keeps every header of a received message, in order and undecoded, including those the fields above decode. When rendering,
      * the fields are the source of truth: a header the model renders from a field is skipped, and the others are written as given.
      *
      * @see
      *   [[kyo.Email.Address]] for addresses
      * @see
      *   [[kyo.Email.Attachment]] and [[kyo.Email.InlinePart]] for the non-body parts
      * @see
      *   [[kyo.Email.Header]] for raw headers
      * @see
      *   [[kyo.Email.MessageId]] for the threading ids
      */
    final case class Message(
        from: Chunk[Email.Address] = Chunk.empty,
        sender: Maybe[Email.Address] = Absent,
        to: Chunk[Email.Address] = Chunk.empty,
        cc: Chunk[Email.Address] = Chunk.empty,
        bcc: Chunk[Email.Address] = Chunk.empty,
        replyTo: Chunk[Email.Address] = Chunk.empty,
        subject: String = "",
        date: Maybe[Instant] = Absent,
        messageId: Maybe[Email.MessageId] = Absent,
        inReplyTo: Chunk[Email.MessageId] = Chunk.empty,
        references: Chunk[Email.MessageId] = Chunk.empty,
        text: String = "",
        html: Maybe[String] = Absent,
        attachments: Chunk[Email.Attachment] = Chunk.empty,
        inlineParts: Chunk[Email.InlinePart] = Chunk.empty,
        headers: Chunk[Email.Header] = Chunk.empty
    ) derives CanEqual

    object Message:

        /** The message whose MIME form (RFC 5322, RFC 2045 to RFC 2049) is `bytes`.
          *
          * Parsing is total: malformed mail becomes the model it most plausibly means, and nothing is dropped silently. A header that does
          * not read stays in `headers` with its field left empty or absent; a part that cannot become text is kept as an attachment. The
          * one failure is multiparts nested more than 100 deep, [[EmailMimeException]] with `NestingTooDeep(100)`, the bound mail servers
          * enforce. An encapsulated `message/rfc822` part is an attachment holding its octets; parse them again to read that message.
          */
        def parse(bytes: Span[Byte])(using Frame): Email.Message < Abort[EmailParseFailure] =
            // A refused default means kyo-mime no longer takes one of the RFC 2045 media types, and a refused piece that kyo.Base64 no
            // longer takes what TransferEncoding builds: defects no mail can cause.
            MediaTypeDefaults.built match
                case Result.Success(defaults) =>
                    Abort.get(MimeParser.parse(bytes, defaults)).map { root =>
                        MimeModel.toModel(bytes, root, defaults) match
                            case Result.Success(message) => message
                            case Result.Failure(refused) => Abort.panic(refused)
                            case Result.Panic(cause)     => Abort.panic(cause)
                    }
                case Result.Failure(refused) => Abort.panic(refused)
                case Result.Panic(cause)     => Abort.panic(cause)

        /** The MIME form of `message` (RFC 5322, RFC 2045 to RFC 2049), which [[parse]] reads back as the same fields.
          *
          * The fields are the source of truth: a header in `headers` that a field renders (`From`, `Subject`, `Content-Type`, ...) is
          * skipped, and every other header is written as given, after the white space at either end of its value. Bodies are written as
          * UTF-8 text, attachments with their media type and file name, inline parts with the HTML in a `multipart/related`.
          *
          * Rendering refuses what it cannot write or what would read back as something else: an address that is not an `addr-spec`
          * ([[EmailInvalidAddressException]]), and a header name, value, date or media type parameter that cannot be written
          * ([[EmailInvalidHeaderException]]), so no value can start a header line of its own.
          */
        def render(message: Email.Message)(using Frame): Span[Byte] < Abort[EmailRenderFailure] =
            Abort.get(MimeRenderer.render(message).mapFailure(failureOf))

        private def failureOf(failure: MimeRenderer.Failure)(using Frame): EmailRenderFailure =
            failure match
                case MimeRenderer.Failure.Header(_, HeaderCodec.WriteFailure.InvalidAddress(address)) =>
                    EmailInvalidAddressException(address)
                case MimeRenderer.Failure.Header(name, HeaderCodec.WriteFailure.LineTooLong) =>
                    EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.LineTooLong)
                case MimeRenderer.Failure.Header(name, HeaderCodec.WriteFailure.LineBreakInValue) =>
                    EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.LineBreakInValue)
                case MimeRenderer.Failure.Header(name, HeaderCodec.WriteFailure.DateOutOfRange) =>
                    EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.DateOutOfRange)
                case MimeRenderer.Failure.Header(name, HeaderCodec.WriteFailure.UnwritableParameterName(parameter)) =>
                    EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.UnwritableParameterName(parameter))
                case MimeRenderer.Failure.InvalidName(name) =>
                    EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.InvalidName)
                case MimeRenderer.Failure.ControlCharacter(name) =>
                    EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.ControlCharacterInValue)

    end Message

    /** A mailbox address as it appears in an address header: the `addr-spec` and an optional display name.
      *
      * `address` is the `local-part@domain` form (RFC 5322 section 3.4.1), without angle brackets. `name` is the display name decoded to
      * text, so `"Ada Lovelace" <ada@example.com>` becomes `Email.Address("ada@example.com", Present("Ada Lovelace"))`. Group syntax
      * flattens into its member addresses.
      *
      * The value is not validated on construction, because a received message may carry an address no strict parser accepts and the model
      * keeps it as sent. Validation happens when a message is rendered or sent: an address that is not an RFC 5322 `addr-spec` (a dot-atom
      * or quoted local part, `@`, and a dot-atom or domain literal, UTF-8 allowed as RFC 6532 extends it, white space only inside the
      * quotes, and no control character) fails with [[EmailInvalidAddressException]] there, so it can never inject a header.
      *
      * @see
      *   [[kyo.Email.Message]] for the headers that hold addresses
      */
    final case class Address(address: String, name: Maybe[String] = Absent) derives CanEqual

    /** One header field of a message as it arrived: its name and its unfolded, undecoded value.
      *
      * `value` has its folding line breaks removed (RFC 5322 section 2.2.3) but is otherwise the text on the wire: encoded words (RFC 2047)
      * are left as they are. The decoded forms of the headers the model understands (addresses, subject, date, the threading ids) live on
      * [[Email.Message]]'s own fields; `Email.Message.headers` keeps every header, in order, including those, so nothing a server sent is
      * lost.
      *
      * Header names compare case-insensitively on the wire, but this value keeps the name as written. When a message is rendered, a header
      * whose name the model already renders from a field is skipped, and the rest are written as given; a name that is not a valid field
      * name, or a value that contains a line break, fails with [[EmailInvalidHeaderException]] there.
      *
      * @see
      *   [[kyo.Email.Message]] for the model that carries headers
      */
    final case class Header(name: String, value: String) derives CanEqual

    /** A MIME media type with its parameters, as a `Content-Type` header states it (RFC 2045 section 5.1), such as
      * `text/calendar; method=REQUEST; charset=UTF-8`.
      *
      * The parameters matter to a reader: `charset` says how a text part's bytes become text, `method` says whether a calendar part is an
      * invitation, a reply or a cancellation, and `name` can carry a file name. So the model keeps all of them, in order, rather than the
      * bare type.
      *
      * It is kyo-mime's [[kyo.mime.MediaType]], so a value built or parsed by either module is the other's. Type, subtype and parameter
      * names are case-insensitive (RFC 2045 section 5.1), so `init` lowercases them and two values that differ only in their casing are
      * equal. Parameter values keep their case, since some (a boundary, a file name) are case-sensitive. A parameter value is the decoded
      * text: RFC 2231 continuations and charset encodings are resolved by the parser and applied again by the renderer.
      *
      * `init` fails with [[kyo.mime.MimeInvalidMediaTypeException]] when the type, the subtype or a parameter name is not an RFC 2045
      * token, or when a parameter name occurs twice. It removes white space at the end of a `boundary`, as every reader does. A parameter
      * value may be any text, control characters included, since received values hold them (a quoted string folded with a tab, a `%00`
      * escape). It still cannot inject a header line: the renderer writes a value as a token or a quoted string only when it is printable
      * ASCII, and every other value in the RFC 2231 form, where CR and LF are `%0D` and `%0A`.
      *
      * @see
      *   [[kyo.Email.Attachment]] and [[kyo.Email.InlinePart]] for where media types appear
      */
    type MediaType = kyo.mime.MediaType

    /** kyo-mime's `MediaType` companion: `init` builds a media type from its parts and `parse` reads a `Content-Type` value. */
    val MediaType: kyo.mime.MediaType.type = kyo.mime.MediaType

    /** A file attached to a message: its content type, its file name when it has one, and its decoded bytes.
      *
      * `contentType` is the part's media type with its parameters, such as `text/calendar; method=REQUEST`. `fileName` comes from the
      * `filename` parameter of `Content-Disposition` or, failing that, the `name` parameter of `Content-Type`, decoded to text (RFC 2231,
      * RFC 2047); it is absent when the sender gave none. `content` holds the bytes after the transfer encoding is removed, so a base64
      * attachment arrives as its original bytes.
      *
      * Parts the parser cannot turn into text are kept here rather than dropped (RFC 2049 section 2): a text part in a charset the module
      * does not decode keeps its declared type, `charset` included, and holds its bytes after transfer decoding, so a caller with its own
      * decoder can still read it; a part in a transfer encoding the module does not know is of type `application/octet-stream` and holds
      * its bytes as they arrived (RFC 2045 section 6.4).
      *
      * A part classified as inline (see [[Email.InlinePart]]) is not an attachment, and a text or HTML part that forms the body lives on
      * [[Email.Message]]'s `text` and `html`.
      *
      * Equality compares `content` element by element, since `Span` itself compares by reference, so a parsed attachment equals the one it
      * was rendered from.
      *
      * @see
      *   [[kyo.Email.InlinePart]] for parts shown inside the HTML body
      * @see
      *   [[kyo.Email.MediaType]] for the content type
      * @see
      *   [[kyo.Email.Message]] for the model that carries attachments
      */
    final case class Attachment(contentType: Email.MediaType, fileName: Maybe[String], content: Span[Byte]) derives CanEqual:

        override def equals(other: Any): Boolean =
            other match
                case that: Email.Attachment =>
                    that.contentType == contentType && that.fileName == fileName && that.content.is(content)
                case _ => false

        override def hashCode: Int = (contentType, fileName, content.hash).##

    end Attachment

    /** A part meant to be shown inside a message's HTML body, which references it by its `Content-ID`, such as a logo shown with
      * `<img src="cid:logo">`.
      *
      * `contentId` is the part's `Content-ID`, the id a `cid:` URL names. `contentType` is its media type with parameters, `fileName` is
      * its file name when the sender gave one, and `content` holds the bytes after the transfer encoding is removed.
      *
      * Inline parts are kept apart from attachments because a reader shows them inside the HTML rather than as files. The parser classifies
      * a part by its structure, never by scanning the HTML: a non-body part with a `Content-ID` inside a `multipart/related` container, or
      * with an `inline` disposition, is an inline part. When a message is rendered with inline parts, they are placed with the HTML body in
      * a `multipart/related` container (RFC 2387).
      *
      * Equality compares `content` element by element, since `Span` itself compares by reference.
      *
      * @see
      *   [[kyo.Email.Attachment]] for parts shown as files
      * @see
      *   [[kyo.Email.Message]] for the model that carries inline parts
      */
    final case class InlinePart(contentId: Email.ContentId, contentType: Email.MediaType, fileName: Maybe[String], content: Span[Byte])
        derives CanEqual:

        override def equals(other: Any): Boolean =
            other match
                case that: Email.InlinePart =>
                    that.contentId == contentId && that.contentType == contentType && that.fileName == fileName && that.content.is(content)
                case _ => false

        override def hashCode: Int = (contentId, contentType, fileName, content.hash).##

    end InlinePart

    /** A message flag in an IMAP mailbox (RFC 9051 section 2.3.2): one of the five system flags a client may set, a keyword, or a flag the
      * server uses that the module does not model.
      *
      * `Keyword.init` validates its name as an IMAP atom with no backslash, since a backslash starts a system flag, and fails with
      * [[EmailInvalidFlagException]] otherwise. `Other` holds any other flag as the server sent it; only the reader builds one, and it can
      * be stored back verbatim with `addFlags` or `removeFlags`.
      *
      * `\Recent` is never read: it is state of one session, and IMAP4rev2 removed it. System flags are read in any case of their name.
      *
      * An `Email.Flag`'s `Schema` stores it as its wire form, as a `FLAGS` response writes it, and decodes it as the reader reads that
      * text: `\Recent`, and text no flag holds, fail decode with a `ConstructorRejectedException` holding the
      * [[EmailInvalidFlagException]]. `Keyword` and `Other` have `Schema`s of their own, the same wire text decoded through the same
      * checks.
      */
    sealed abstract class Flag(val wire: String) derives CanEqual

    object Flag:

        case object Seen extends Email.Flag("\\Seen")

        case object Answered extends Email.Flag("\\Answered")

        case object Flagged extends Email.Flag("\\Flagged")

        case object Deleted extends Email.Flag("\\Deleted")

        case object Draft extends Email.Flag("\\Draft")

        final case class Keyword private[Flag] (name: String) extends Email.Flag(name)

        object Keyword:
            /** The keyword `name`, or [[EmailInvalidFlagException]] when it is not a non-empty IMAP atom without a backslash. */
            def init(name: String)(using Frame): Result[EmailInvalidFlagException, Keyword] =
                if isAtom(name) then Result.succeed(new Keyword(name)) else Result.fail(EmailInvalidFlagException(name))

            given (using Frame): Schema[Keyword] = Schema.stringSchema.transformVia((name: String) => init(name))(_.name)
        end Keyword

        /** A flag the module does not model, as the server sent it: a system flag other than the five and `\Recent` (`\Forwarded`, say), or
          * a keyword that is not an IMAP atom (`$a%b`). Its text is non-empty and holds no control character, space or parenthesis.
          */
        final case class Other private[Flag] (override val wire: String) extends Email.Flag(wire)

        object Other:
            given (using Frame): Schema[Other] = Schema.stringSchema.transformVia((wire: String) => checked(wire))(_.wire)

            private def checked(wire: String)(using Frame): Result[EmailInvalidFlagException, Other] =
                fromWire(wire) match
                    case Present(other: Other) => Result.succeed(other)
                    case _                     => Result.fail(EmailInvalidFlagException(wire))
        end Other

        // Not derived: the wire form is one string for every variant, and which variant it is follows from the text (fromWire), not from
        // a tag or a field set.
        given (using Frame): Schema[Email.Flag] =
            Schema.stringSchema.transformVia((wire: String) => fromWire(wire).toResult(Result.fail(EmailInvalidFlagException(wire))))(
                _.wire
            )

        private val system: Chunk[Email.Flag] = Chunk(Seen, Answered, Flagged, Deleted, Draft)

        /** The flag the text of a `FLAGS` item names: `Absent` for `\Recent`, which is never read, and for text no flag holds (see
          * [[isFlagText]]).
          */
        private[kyo] def fromWire(text: String): Maybe[Email.Flag] =
            if !isFlagText(text) || Ascii.equalsIgnoreCase(text, "\\Recent") then Absent
            else if !text.startsWith("\\") then Present(if isAtom(text) then new Keyword(text) else new Other(text))
            else Present(Maybe.fromOption(system.find(flag => Ascii.equalsIgnoreCase(flag.wire, text))).getOrElse(new Other(text)))

        /** Whether `text` can be a flag: non-empty, with no control character (RFC 9051 section 9 `CTL`), space or parenthesis, which would
          * end it inside a flag list.
          */
        private[kyo] def isFlagText(text: String): Boolean =
            text.nonEmpty && text.forall(c => c > ' ' && c != '\u007f' && c != '(' && c != ')')

        // RFC 9051 section 9: ATOM-CHAR excludes ( ) { SP CTL % * " \ ] and every octet from 0x80.
        private def isAtom(text: String): Boolean = text.nonEmpty &&
            text.forall(c => c > ' ' && c < '\u007f' && "(){%*\"\\]".indexOf(c.toInt) < 0)

    end Flag

    /** How the module authenticates to an IMAP or SMTP server: a password, or a computation that yields an OAuth 2.0 token.
      *
      * [[Email.Auth.Password]] covers account passwords and the app passwords providers issue where they allow them. The module uses the
      * IMAP `LOGIN` command or the SASL `PLAIN` mechanism, whichever the server advertises, and SMTP `AUTH PLAIN` or `AUTH LOGIN`.
      *
      * [[Email.Auth.OAuth2]] covers providers that require OAuth, as Gmail and Microsoft 365 largely do, through SASL XOAUTH2. The caller
      * supplies a computation that yields an [[Email.OAuthToken]]; the module runs it every time it authenticates (each connection, each
      * reconnect and each submission), so its effects run again each time and the caller decides how tokens are obtained, cached and
      * refreshed.
      *
      * `E` is the token computation's own failure. It reaches the row of every operation that authenticates unchanged, so a caller gets
      * back exactly the error its computation raised. A password never fails this way, so `Password` is an `Email.Auth[Nothing]` and adds
      * nothing to a row.
      *
      * IMPORTANT: the token computation runs again on every reconnect, which is when the network may be down. The module cannot tell which
      * of the caller's failures are temporary, so a failure of the computation ends a receive loop instead of being retried.
      *
      * Both cases render without the secret: a password prints as `Email.Password(<redacted>)`, and the token computation prints as a
      * placeholder. An `OAuth2` value holds a computation, which compares by reference, so it has no structural equality and neither does a
      * config that holds one.
      *
      * @tparam E
      *   the failure of the token computation; `Nothing` for a password
      * @see
      *   [[kyo.Email.Password]] and [[kyo.Email.OAuthToken]] for the secret types
      * @see
      *   [[kyo.EmailAuthenticationException]] for a rejected credential
      */
    sealed abstract class Auth[+E]:
        def user: String

    object Auth:

        /** Authenticate `user` with a password or an app password. */
        final case class Password private (user: String, password: Email.Password) extends Email.Auth[Nothing]

        object Password:
            /** `user` with `password`, or [[EmailInvalidConfigException]] when `user` is empty or holds a control character. */
            def init(user: String, password: Email.Password)(using Frame): Result[EmailInvalidConfigException, Password] =
                checkedUser(user)(new Password(user, password))
        end Password

        /** Authenticate `user` through SASL XOAUTH2 with the token `token` yields, run again at each authentication. */
        final case class OAuth2[+E] private (user: String, token: Email.OAuthToken < (Async & Abort[E])) extends Email.Auth[E]:
            override def toString: String = s"OAuth2($user,<token computation>)"

        object OAuth2:
            /** `user` with the token computation `token`, or [[EmailInvalidConfigException]] when `user` is empty or holds a control
              * character.
              */
            def init[E](user: String, token: Email.OAuthToken < (Async & Abort[E]))(using
                Frame
            ): Result[EmailInvalidConfigException, OAuth2[E]] =
                checkedUser(user)(new OAuth2(user, token))
        end OAuth2

        /** The authentication exchange the module ran with a server, reported by the authentication failure leaves. */
        enum Mechanism derives CanEqual:
            /** The IMAP `LOGIN` command, or the SMTP `AUTH LOGIN` exchange. */
            case Login

            /** SASL `PLAIN` (RFC 4616). */
            case Plain

            /** SASL `XOAUTH2`, as Google and Microsoft document it. */
            case XOAuth2
        end Mechanism

        // A control character in the user name would end the IMAP command or SMTP line that carries it and let the rest inject another.
        private def checkedUser[A](user: String)(auth: => A)(using Frame): Result[EmailInvalidConfigException, A] =
            if user.isEmpty then Result.fail(EmailInvalidConfigException(EmailInvalidConfigException.Violation.EmptyUser))
            else if user.exists(_.isControl) then
                Result.fail(EmailInvalidConfigException(EmailInvalidConfigException.Violation.ControlCharacterInUser))
            else Result.succeed(auth)

    end Auth

    /** A mail account password, or an app password where the provider issues one.
      *
      * A password is a secret, so it has a type of its own rather than being a `String`: it cannot be passed where a user name, a host or
      * an OAuth token belongs, and it never prints. `toString` renders `Email.Password(<redacted>)`, so a config, an [[Email.Auth]] or a
      * log line that contains one shows no secret, and no [[EmailException]] message renders it. The raw text is reachable only through
      * `value`, which the module reads when it authenticates.
      *
      * Equality compares the underlying text. There is no `Schema`: a password is never encoded as part of a model.
      *
      * @see
      *   [[kyo.Email.Auth.Password]] for where a password is used
      * @see
      *   [[kyo.Email.OAuthToken]] for the OAuth counterpart
      */
    final class Password private (val value: String):

        override def equals(other: Any): Boolean =
            other match
                case that: Email.Password => that.value == value
                case _                    => false

        override def hashCode: Int = value.hashCode

        override def toString: String = "Email.Password(<redacted>)"

    end Password

    object Password:
        /** The password `value`, or [[EmailInvalidTokenException]] when it is empty or holds NUL, CR or LF. */
        def init(value: String)(using Frame): Result[EmailInvalidTokenException, Email.Password] =
            if value.isEmpty then Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.EmptyPassword))
            else
                val position = value.indexWhere(c => c == '\u0000' || c == '\r' || c == '\n')
                if position >= 0 then
                    Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.LineBreakOrNulInPassword(position)))
                else Result.succeed(new Email.Password(value))

        given CanEqual[Email.Password, Email.Password] = CanEqual.derived
    end Password

    /** An OAuth 2.0 access token, presented to the server through SASL XOAUTH2.
      *
      * A token is a secret, so it has a type of its own: it cannot be passed where an [[Email.Password]], a user name or a host belongs,
      * and it never prints. `toString` renders `Email.OAuthToken(<redacted>)`, and no [[EmailException]] message renders it. The raw text
      * is reachable only through `value`, which the module reads when it builds the XOAUTH2 initial response.
      *
      * The module never obtains or refreshes a token itself. The caller's computation in [[Email.Auth.OAuth2]] yields one each time the
      * module authenticates, so expiry and refresh stay with the caller.
      *
      * Equality compares the underlying text. There is no `Schema`: a token is never encoded as part of a model.
      *
      * @see
      *   [[kyo.Email.Auth.OAuth2]] for the computation that yields tokens
      * @see
      *   [[kyo.Email.Password]] for the password counterpart
      */
    final class OAuthToken private (val value: String):

        override def equals(other: Any): Boolean =
            other match
                case that: Email.OAuthToken => that.value == value
                case _                      => false

        override def hashCode: Int = value.hashCode

        override def toString: String = "Email.OAuthToken(<redacted>)"

    end OAuthToken

    object OAuthToken:
        /** The token `value`, or [[EmailInvalidTokenException]] when it is empty or not an RFC 6750 `b64token`. */
        def init(value: String)(using Frame): Result[EmailInvalidTokenException, Email.OAuthToken] =
            if value.isEmpty then Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.EmptyToken))
            else
                val body = endOf(value, 0, isTokenChar)
                val end  = endOf(value, body, _ == '=')
                if body == 0 then Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.CharacterOutsideToken(0)))
                else if end < value.length then
                    Result.fail(EmailInvalidTokenException(EmailInvalidTokenException.Problem.CharacterOutsideToken(end)))
                else Result.succeed(new Email.OAuthToken(value))
                end if

        private def endOf(value: String, from: Int, keep: Char => Boolean): Int =
            val stop = value.indexWhere(c => !keep(c), from)
            if stop < 0 then value.length else stop

        // RFC 6750 section 2.1: b64token = 1*( ALPHA / DIGIT / "-" / "." / "_" / "~" / "+" / "/" ) *"="
        private def isTokenChar(c: Char): Boolean =
            (c >= 'a' && c <= 'z') ||
                (c >= 'A' && c <= 'Z') ||
                (c >= '0' && c <= '9') || "-._~+/".indexOf(c.toInt) >= 0

        given CanEqual[Email.OAuthToken, Email.OAuthToken] = CanEqual.derived
    end OAuthToken

    /** How a connection to a mail server is secured: TLS from the first byte, or a plaintext greeting upgraded in place with STARTTLS.
      *
      * [[Email.Tls.Implicit]] opens TLS before any protocol byte, as IMAP on port 993 and SMTP submission on port 465 do. RFC 8314
      * recommends it for both, so it is the default of both configs. [[Email.Tls.StartTls]] connects in plaintext, reads the greeting, and
      * upgrades the same connection before authenticating, as IMAP on port 143 and SMTP submission on port 587 do.
      *
      * There is no plaintext mode. A STARTTLS server that does not advertise the upgrade fails the connection with
      * an [[EmailConnectException]] of kind `StartTlsUnavailable` rather than continuing in the clear, so credentials never cross an
      * unencrypted connection.
      *
      * The server certificate is verified against the configured host name, from the trust anchors [[Email.Tls.Trust]] names.
      *
      * WARNING: [[Email.Tls.Trust.TrustAll]] disables certificate and host name verification, which exposes the credentials to anyone on
      * the path. It exists for test servers only.
      *
      * `minVersion` and `maxVersion` bound the negotiated protocol version on every platform. A [[Email.Tls.ClientCertificate]] is
      * presented when the server asks for one, for a server that authenticates its clients by certificate. `Email.Tls.Implicit` and
      * `Email.Tls.StartTls` are the default settings: the platform's trust store, TLS 1.2 to 1.3, and no client certificate. The factories
      * of the same names take all four settings.
      *
      * @see
      *   [[kyo.EmailImapConfig]] and [[kyo.EmailSmtpConfig]] for where this is set
      * @see
      *   [[kyo.EmailConnectException]] for a failed handshake (kind `Tls`) or client certificate files that cannot be loaded (kind
      *   `TlsSetup`)
      */
    sealed abstract class Tls(
        val trust: Email.Tls.Trust,
        val minVersion: Email.Tls.Version,
        val maxVersion: Email.Tls.Version,
        val clientCertificate: Maybe[Email.Tls.ClientCertificate]
    ) derives CanEqual:

        override def equals(other: Any): Boolean =
            other match
                case that: Email.Tls =>
                    (this, that) match
                        case (_: Email.Tls.Implicit, _: Email.Tls.Implicit) | (_: Email.Tls.StartTls, _: Email.Tls.StartTls) =>
                            trust == that.trust && minVersion == that.minVersion && maxVersion == that.maxVersion &&
                            clientCertificate == that.clientCertificate
                        case _ => false
                case _ => false

        override def hashCode: Int = (mode, trust, minVersion, maxVersion, clientCertificate).##

        override def toString: String = s"$mode($trust,$minVersion,$maxVersion,$clientCertificate)"

        private def mode: String =
            this match
                case _: Email.Tls.Implicit => "Implicit"
                case _: Email.Tls.StartTls => "StartTls"

    end Tls

    object Tls:

        /** TLS from the first byte (IMAP port 993, SMTP port 465). */
        sealed class Implicit private[Tls] (
            trust: Email.Tls.Trust,
            minVersion: Email.Tls.Version,
            maxVersion: Email.Tls.Version,
            clientCertificate: Maybe[Email.Tls.ClientCertificate]
        ) extends Email.Tls(trust, minVersion, maxVersion, clientCertificate)

        /** Implicit TLS with the default settings. */
        object Implicit extends Implicit(Trust.System, Version.TLS12, Version.TLS13, Absent):
            /** Implicit TLS with these settings. */
            def apply(
                trust: Email.Tls.Trust,
                minVersion: Email.Tls.Version,
                maxVersion: Email.Tls.Version,
                clientCertificate: Maybe[Email.Tls.ClientCertificate]
            ): Implicit = new Implicit(trust, minVersion, maxVersion, clientCertificate)
        end Implicit

        /** A plaintext greeting upgraded with STARTTLS before authentication (IMAP port 143, SMTP port 587). */
        sealed class StartTls private[Tls] (
            trust: Email.Tls.Trust,
            minVersion: Email.Tls.Version,
            maxVersion: Email.Tls.Version,
            clientCertificate: Maybe[Email.Tls.ClientCertificate]
        ) extends Email.Tls(trust, minVersion, maxVersion, clientCertificate)

        /** STARTTLS with the default settings. */
        object StartTls extends StartTls(Trust.System, Version.TLS12, Version.TLS13, Absent):
            /** STARTTLS with these settings. */
            def apply(
                trust: Email.Tls.Trust,
                minVersion: Email.Tls.Version,
                maxVersion: Email.Tls.Version,
                clientCertificate: Maybe[Email.Tls.ClientCertificate]
            ): StartTls = new StartTls(trust, minVersion, maxVersion, clientCertificate)
        end StartTls

        /** Where the certificates that anchor verification come from. */
        enum Trust derives CanEqual:
            /** The platform's default trust store. */
            case System

            /** The PEM-encoded certificate authorities in the file at `path`, in place of the platform's store. */
            case CaFile(path: Path)

            /** No verification at all. Test servers only. */
            case TrustAll
        end Trust

        /** A TLS protocol version, the bound of the range a connection negotiates within. */
        enum Version derives CanEqual:
            case TLS12, TLS13

        /** The certificate a client presents to a server that authenticates its clients by certificate (mutual TLS), such as a corporate or
          * self-hosted mail server; it is sent only when the server asks for one.
          *
          * `chain` is a PEM file holding the client certificate first, then any intermediates; `privateKey` is a PEM file holding its key.
          * kyo-email holds only the two paths and never reads the key: the files are read at each handshake. So `toString` renders the
          * paths and never key material, and no [[EmailException]] message carries it. A file that cannot be read, or a key that does not
          * match the certificate, fails the connection with an [[EmailConnectException]] of kind `TlsSetup`.
          *
          * A server that refuses the certificate, or requires one that is not configured, fails the handshake with kind `Tls` under TLS
          * 1.2. Under TLS 1.3 the client completes its side of the handshake before the server decides, so the refusal can instead reach
          * the client as the connection closing on its first read, an [[EmailTransportException]] of kind `ConnectionClosed`.
          */
        final case class ClientCertificate(chain: Path, privateKey: Path) derives CanEqual

    end Tls

    /** The value of a `Message-ID` header without its angle brackets. `init` accepts the value with or without them. */
    type MessageId = MessageId.Value

    object MessageId:
        opaque type Value = String

        /** The id `value` holds, or the violation that makes it no id. */
        def init(value: String)(using Frame): Result[EmailInvalidIdException, MessageId] = invalid(normalize(value))

        /** The id `init` would build, or `Absent` where it would fail. */
        private[kyo] def read(value: String): Maybe[MessageId] = normalize(value).toMaybe

        /** `local@domain` when that is an id, and `local@fallback` otherwise. `local` and `fallback` must be dot-atoms, which no check
          * refuses.
          */
        private[kyo] def generated(local: String, domain: String, fallback: String): MessageId =
            read(s"$local@$domain").getOrElse(s"$local@$fallback")

        extension (self: MessageId) def value: String = self

        given (using Frame): Schema[MessageId] = Schema.stringSchema.transformVia((v: String) => init(v))(_.value)
        given CanEqual[MessageId, MessageId]   = CanEqual.derived

        private def normalize(value: String): Result[EmailInvalidIdException.Violation, MessageId] =
            unbracket(
                value,
                EmailInvalidIdException.Violation.EmptyMessageId,
                EmailInvalidIdException.Violation.ControlCharacterInMessageId,
                EmailInvalidIdException.Violation.DelimiterInMessageId,
                EmailInvalidIdException.Violation.UnreadableMessageId
            )
    end MessageId

    /** The value of a part's `Content-ID` header without its angle brackets: the text a `cid:` URL in an HTML body names (RFC 2392).
      * `init` accepts the value with or without the brackets.
      */
    type ContentId = ContentId.Value

    object ContentId:
        opaque type Value = String

        /** The id `value` holds, or the violation that makes it no id. */
        def init(value: String)(using Frame): Result[EmailInvalidIdException, ContentId] = invalid(normalize(value))

        /** The id `init` would build, or `Absent` where it would fail. */
        private[kyo] def read(value: String): Maybe[ContentId] = normalize(value).toMaybe

        extension (self: ContentId) def value: String = self

        given (using Frame): Schema[ContentId] = Schema.stringSchema.transformVia((v: String) => init(v))(_.value)
        given CanEqual[ContentId, ContentId]   = CanEqual.derived

        private def normalize(value: String): Result[EmailInvalidIdException.Violation, ContentId] =
            unbracket(
                value,
                EmailInvalidIdException.Violation.EmptyContentId,
                EmailInvalidIdException.Violation.ControlCharacterInContentId,
                EmailInvalidIdException.Violation.DelimiterInContentId,
                EmailInvalidIdException.Violation.UnreadableContentId
            )
    end ContentId

    /** A mailbox name, held as its wire form: the octets that name the mailbox on the server, one character per octet, so two names are
      * equal exactly when the server sees one mailbox. `init` takes the name as text and holds its canonical modified UTF-7 form (RFC 3501
      * section 5.1.3). A name a server lists is held as the octets it sent, since those are the mailbox's identity even when they are not
      * canonical (RFC 9051 Appendix A: a client must not depend on the server having produced the canonical form).
      *
      * `value` is the text, decoded leniently: a part that does not decode stays as its octets. `verbatim` marks a listed name whose octets
      * are not the canonical form of that text. It is sent back as those octets, and differs from the name `init` builds from the same
      * text, as the two are different mailboxes on the server. The Schema form is the wire form, so a stored `Entwürfe` reads
      * `Entw&APw-rfe`.
      *
      * `INBOX` is case-insensitive over US-ASCII (RFC 3501 section 5.1), so any ASCII casing of it is `INBOX`; a name that only resembles
      * it through non-ASCII letters, such as one spelled with a dotless `ı`, is a different mailbox. Every other name is kept as given,
      * since servers treat those names as case-sensitive.
      */
    type MailboxName = MailboxName.Value

    object MailboxName:
        opaque type Value = String

        /** The mailbox named by the text `value`, or the violation that makes it no name. */
        def init(value: String)(using Frame): Result[EmailInvalidIdException, MailboxName] = invalid(canonical(value))

        /** A name as a session read it: the octets the server sent, or, in a session in UTF-8 mode, the canonical form of their text when
          * they are UTF-8, so that the name equals the one `init` builds.
          */
        private[kyo] def fromWire(octets: Chunk[Byte], utf8: Boolean): Maybe[MailboxName] =
            val raw = new String(octets.toArray, "ISO-8859-1")
            if !utf8 then checkWire(raw).toMaybe
            else exactUtf8(raw).fold(checkWire(raw).toMaybe)(text => canonical(text).toMaybe)
        end fromWire

        val Inbox: MailboxName = InboxName

        extension (self: MailboxName)
            /** The name as text, decoded leniently: a part of it that does not decode is kept as its octets. */
            def value: String = decoded(self)

            /** Whether this is a name the server produced whose octets are not the canonical form of its text; it is sent as those octets.
              */
            def verbatim: Boolean = !canonical(decoded(self)).toMaybe.exists(_ == self)

            /** The octets that name the mailbox on the server, one character per octet. */
            private[kyo] def wire: String = self
        end extension

        given (using Frame): Schema[MailboxName] = Schema.stringSchema.transformVia((v: String) => invalid(checkWire(v)))(_.wire)
        given CanEqual[MailboxName, MailboxName] = CanEqual.derived

        private def canonical(text: String): Result[EmailInvalidIdException.Violation, MailboxName] =
            if text.isEmpty then Result.fail(EmailInvalidIdException.Violation.EmptyMailboxName)
            else if text.exists(_.isControl) then Result.fail(EmailInvalidIdException.Violation.ControlCharacterInMailboxName)
            else if Ascii.equalsIgnoreCase(text, InboxName) then Result.succeed(InboxName)
            else
                ImapModifiedUtf7.encode(text) match
                    case Result.Success(encoded) => Result.succeed(encoded)
                    case _                       => Result.fail(EmailInvalidIdException.Violation.UnpairedSurrogateInMailboxName)

        // Octets 0x80 to 0x9F are C1 controls as characters, and every UTF-8 continuation octet is one, so only C0 and DEL are refused
        // here.
        private[Email] def checkWire(raw: String): Result[EmailInvalidIdException.Violation, MailboxName] =
            if raw.isEmpty then Result.fail(EmailInvalidIdException.Violation.EmptyMailboxName)
            else if raw.exists(c => c < ' ' || c == '\u007f') then
                Result.fail(EmailInvalidIdException.Violation.ControlCharacterInMailboxName)
            else if raw.exists(_ > 'ÿ') then Result.fail(EmailInvalidIdException.Violation.MailboxNameNotOctets)
            else if Ascii.equalsIgnoreCase(raw, InboxName) then Result.succeed(InboxName)
            else Result.succeed(raw)

        private def decoded(wire: String): String =
            if wire.forall(_ < '\u0080') then
                ImapModifiedUtf7.decode(wire) match
                    case Result.Success(text) => text
                    case _                    => lenient(wire)
            else exactUtf8(wire).getOrElse(wire)

        // Each run of modified UTF-7 (`&`, modified base64, `-`) decoded alone; an `&` that starts no run that decodes is kept as it is.
        private def lenient(wire: String): String =
            val out                            = new java.lang.StringBuilder
            @tailrec def loop(from: Int): Unit =
                val amp = wire.indexOf('&', from)
                if amp < 0 then discard(out.append(wire, from, wire.length))
                else
                    discard(out.append(wire, from, amp))
                    val end     = wire.indexWhere(c => !(Ascii.isAlphaNumeric(c) || c == '+' || c == ','), amp + 1)
                    val run     = if end >= 0 && wire(end) == '-' then Present(wire.substring(amp, end + 1)) else Absent
                    val decoded = run.flatMap(r => ImapModifiedUtf7.decode(r).toMaybe)
                    if decoded.isEmpty then
                        discard(out.append('&'))
                        loop(amp + 1)
                    else
                        discard(out.append(decoded.getOrElse("")))
                        loop(end + 1)
                    end if
                end if
            end loop
            loop(0)
            out.toString
        end lenient

        // The octets, one per character, as UTF-8 text when they are exactly UTF-8.
        private def exactUtf8(raw: String): Maybe[String] =
            val octets = raw.getBytes("ISO-8859-1")
            val text   = Utf8.decode(Span.from(octets))
            if Utf8.encode(text).toArray.sameElements(octets) then Present(text) else Absent
        end exactUtf8

        private inline def InboxName = "INBOX"
    end MailboxName

    /** A mailbox's UIDVALIDITY: a non-zero 32-bit unsigned value that changes whenever the server renumbers the mailbox's UIDs. */
    type UidValidity = UidValidity.Value

    object UidValidity:
        opaque type Value = Long

        /** The validity `value`, or the violation that puts it outside the non-zero 32-bit unsigned range. */
        def init(value: Long)(using Frame): Result[EmailInvalidIdException, UidValidity] = invalid(check(value))

        private[kyo] def read(value: Long): Maybe[UidValidity] = check(value).toMaybe

        extension (self: UidValidity) def value: Long = self

        given (using Frame): Schema[UidValidity] = Schema.longSchema.transformVia((v: Long) => init(v))(_.value)
        given CanEqual[UidValidity, UidValidity] = CanEqual.derived

        private[Email] def check(value: Long): Result[EmailInvalidIdException.Violation, UidValidity] =
            if inUnsigned32Range(value) then Result.succeed(value)
            else Result.fail(EmailInvalidIdException.Violation.UidValidityOutOfRange(value))
    end UidValidity

    /** A message's UID together with the mailbox and UIDVALIDITY it was issued under. `value` is the non-zero 32-bit unsigned UID number.
      */
    final case class Uid private (mailbox: MailboxName, validity: UidValidity, value: Long) derives CanEqual

    object Uid:
        /** The UID `value` under `mailbox` and `validity`, or the violation that puts it outside the non-zero 32-bit unsigned range. */
        def init(mailbox: MailboxName, validity: UidValidity, value: Long)(using Frame): Result[EmailInvalidIdException, Uid] =
            invalid(check(mailbox, validity, value))

        private[kyo] def read(mailbox: MailboxName, validity: UidValidity, value: Long): Maybe[Uid] =
            check(mailbox, validity, value).toMaybe

        given (using Frame): Schema[Uid] =
            Schema.derivedVia((mailbox: MailboxName, validity: UidValidity, value: Long) => init(mailbox, validity, value))

        private def check(mailbox: MailboxName, validity: UidValidity, value: Long): Result[EmailInvalidIdException.Violation, Uid] =
            if inUnsigned32Range(value) then Result.succeed(new Uid(mailbox, validity, value))
            else Result.fail(EmailInvalidIdException.Violation.UidOutOfRange(value))
    end Uid

    private def inUnsigned32Range(value: Long): Boolean = value >= 1 && value <= 0xffffffffL

    // A `<`, `>` or SP inside an id would close its brackets early or split it when written (RFC 5322 section 3.6.4).
    private def unbracket(
        value: String,
        empty: EmailInvalidIdException.Violation,
        control: EmailInvalidIdException.Violation,
        delimiter: EmailInvalidIdException.Violation,
        unreadable: EmailInvalidIdException.Violation
    ): Result[EmailInvalidIdException.Violation, String] =
        var start = 0
        var end   = value.length
        while start < end && value.charAt(start) == ' ' do start += 1
        while end > start && value.charAt(end - 1) == ' ' do end -= 1
        if end - start >= 2 && value.charAt(start) == '<' && value.charAt(end - 1) == '>' then
            start += 1
            end -= 1
        val bare = value.substring(start, end)
        if bare.isEmpty then Result.fail(empty)
        else if bare.exists(_.isControl) then Result.fail(control)
        else if bare.exists(c => c == '<' || c == '>' || c == ' ') then Result.fail(delimiter)
        else if !readsBack(bare) then Result.fail(unreadable)
        else Result.succeed(bare)
        end if
    end unbracket

    // Inside the brackets a reader drops a comment and keeps a quoted string as written, a backslash escaping the next character (RFC 5322
    // section 3.2.4), so an id reads back as itself only when every ( is quoted and every quoted string closes.
    private def readsBack(id: String): Boolean =
        @scala.annotation.tailrec
        def loop(at: Int, quoted: Boolean): Boolean =
            if at >= id.length then !quoted
            else
                id.charAt(at) match
                    case '"'            => loop(at + 1, !quoted)
                    case '\\' if quoted => loop(at + 2, quoted)
                    case '(' if !quoted => false
                    case _              => loop(at + 1, quoted)
        loop(0, false)
    end readsBack

    private def invalid[A](checked: Result[EmailInvalidIdException.Violation, A])(using Frame): Result[EmailInvalidIdException, A] =
        checked.mapFailure(EmailInvalidIdException(_))

end Email
