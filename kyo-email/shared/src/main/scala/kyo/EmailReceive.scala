package kyo

import kyo.internal.Ascii
import kyo.internal.email.CommandLine
import kyo.internal.email.imap.ImapClient
import kyo.internal.email.imap.ImapReceiver
import kyo.internal.email.imap.ImapSession

/** Reading mail from an IMAP server, as an effect: the receive loop and every verb on a mailbox require `EmailReceive`, and
  * [[EmailReceive.run]] handles it with an [[EmailImapConfig]].
  *
  * `run` holds one authenticated session for the verbs of its computation, over TLS from the first byte or upgraded with STARTTLS. It
  * connects when the first verb needs it, so a computation that reaches no verb never logs in, and it logs out when `run` ends, by
  * success, failure or interruption. The receive loop is the exception: a connection in IDLE takes no other command, so the loop opens
  * one of its own.
  *
  * Verbs on one session run one at a time, in the order they acquire it, so concurrent fibers share it safely. A verb that names a
  * mailbox, or takes UIDs, selects that mailbox when it is not the selected one. Each command is bounded by the config's
  * `commandTimeout`. A command that fails in transport, including one whose deadline passed or which the server ended with `BYE`, closes
  * the connection, since a session cannot be resynchronized after a lost completion; the verb fails, and the next verb connects again.
  * An OAuth token's computation runs once per login, and its failure reaches the verb as an [[EmailTokenException]].
  *
  * Server text copied into a failure never holds the credential: the password, the token and every form in which they crossed the wire are
  * replaced with `<redacted>`.
  *
  * @see
  *   [[EmailImapConfig]] for the server, the account and the timing settings
  * @see
  *   [[Email.Tls]] for how the connection is secured and which certificates are trusted
  * @see
  *   [[EmailSend]] for sending
  * @see
  *   [[Email.run]] to handle both effects at once
  */
opaque type EmailReceive <: Env[ImapClient] = Env[ImapClient]

object EmailReceive:

    /** Runs `v` with its verbs on one session to the server `config` names, opened by the first verb that needs it and logged out when
      * `v` ends.
      */
    def run[A, S](config: EmailImapConfig)(v: A < (EmailReceive & S))(using Frame): A < (Async & S) =
        Scope.run {
            ImapClient.init(config).map { client =>
                Scope.ensure(client.close).andThen(Env.run(client: ImapClient)(v))
            }
        }

    /** Where a receive loop begins. The mailbox is part of the value, so a start cannot name one mailbox and resume from another's UID. */
    enum Start derives CanEqual:
        /** Only messages that arrive after the loop starts: the UIDs at or above the mailbox's UIDNEXT when it is first selected. */
        case New(mailbox: Email.MailboxName)

        /** Every message in the mailbox, oldest UID first, then the ones that arrive. */
        case All(mailbox: Email.MailboxName)

        /** Resume after `uid`: the UIDs above it in its mailbox, which must still have its UIDVALIDITY. */
        case After(uid: Email.Uid)
    end Start

    /** Receives the messages of one mailbox, forever: each new message is handed to `handler` in UID order, as
      * [[EmailReceive.InboxEvent.Received]], or as [[EmailReceive.InboxEvent.Unreadable]] when the module cannot read it or it is past
      * `maxResponseLength`. Between rounds the loop waits with IDLE (RFC 2177), or polls every `pollInterval` on a server without it; it
      * does not wait when the server announced a message during the round.
      *
      * The loop owns a connection of its own, since a connection in IDLE takes no other command. `handler` runs on the loop's fiber
      * between waits, with `EmailReceive` bound to that connection, so its verbs (flag, move, fetch a part of the message it was handed)
      * need no second login; the loop selects its mailbox again when a verb selected another. The binding is for the handler's own fiber
      * and its own run: a fiber it forks that runs a verb later waits for the loop's IDLE to end, up to `idleRenewal`, and after a
      * reconnect finds that connection closed. The next UID advances only after `handler` returns, so a reconnect redelivers nothing
      * handled and loses nothing not yet handled.
      *
      * A connect or transport failure, a dropped IDLE and a `NO [UNAVAILABLE]` are retried under the config's `reconnect`, except on the
      * first connection until its mailbox is selected, where they fail at once; when the schedule allows no further attempt, the loop fails
      * with the last attempt's failure. The schedule starts over only after a completed round (a listing, its deliveries and the wait after
      * them), so a server that accepts every reconnect and then fails the same way is not retried forever. Every other failure ends the
      * loop, an [[EmailTokenException]] included, and so does the handler's `E2`, which reaches the caller unchanged. The loop ends only
      * by failure or interruption, and closes its connection either way.
      */
    def receive[E2, S](start: Start)(
        handler: EmailReceive.InboxEvent => Unit < (Async & Abort[E2] & EmailReceive & S)
    )(using Frame): Unit < (Async & Abort[EmailReceiveFailure | E2] & EmailReceive & S) =
        Env.use[ImapClient] { client =>
            ImapReceiver.run(client.config, start) { (session, event) =>
                Env.run(new ImapClient.Bound(client.config, session): ImapClient)(handler(event))
            }
        }

    /** Every mailbox of the account, in the server's order, with its special use (`\Sent`, `\Trash`, ...) when the server reports one. */
    def listMailboxes(using Frame): Chunk[EmailReceive.Mailbox] < (Async & Abort[EmailListMailboxesFailure] & EmailReceive) =
        held("listMailboxes")(_.listMailboxes)

    /** The counts of `mailbox`, read without selecting it. */
    def status(mailbox: Email.MailboxName)(using Frame): EmailReceive.MailboxStatus < (Async & Abort[EmailStatusFailure] & EmailReceive) =
        held("status")(_.status(mailbox))

    /** The UIDs of the messages in `mailbox` that match `query`, ascending. */
    def search(mailbox: Email.MailboxName, query: EmailReceive.Search)(using
        Frame
    ): Chunk[Email.Uid] < (Async & Abort[EmailSearchFailure] & EmailReceive) =
        held("search")(_.search(mailbox, query))

    /** A summary of each message, in UID order per mailbox, the mailboxes in the order their first UID appears. A UID whose message was
      * expunged has no summary; it is not a failure. Nothing is marked `\Seen`.
      */
    def fetch(uids: Seq[Email.Uid])(using Frame): Chunk[EmailReceive.Summary] < (Async & Abort[EmailFetchFailure] & EmailReceive) =
        held("fetch")(_.fetch(Chunk.from(uids)))

    /** The whole message, parsed. Nothing is marked `\Seen`. */
    def fetchMessage(uid: Email.Uid)(using Frame): Email.Message < (Async & Abort[EmailFetchMessageFailure] & EmailReceive) =
        held("fetchMessage")(_.fetchMessage(uid))

    /** The content of one part, by its `EmailReceive.Part.path`, with its transfer encoding decoded. The empty path is the whole message as
      * stored. Nothing is marked `\Seen`.
      */
    def fetchPart(uid: Email.Uid, part: Chunk[Int])(using
        Frame
    ): Span[Byte] < (Async & Abort[EmailFetchPartFailure] & EmailReceive) =
        held("fetchPart")(_.fetchPart(uid, part))

    /** Adds `flags` to each message. */
    def addFlags(uids: Seq[Email.Uid], flags: Set[Email.Flag])(using
        Frame
    ): Unit < (Async & Abort[EmailAddFlagsFailure] & EmailReceive) =
        held("addFlags")(_.store("addFlags", Chunk.from(uids), flags, add = true))

    /** Removes `flags` from each message. */
    def removeFlags(uids: Seq[Email.Uid], flags: Set[Email.Flag])(using
        Frame
    ): Unit < (Async & Abort[EmailRemoveFlagsFailure] & EmailReceive) =
        held("removeFlags")(_.store("removeFlags", Chunk.from(uids), flags, add = false))

    /** Moves each message to `to`: with `MOVE` (RFC 6851) when the server has it, else by copying, flagging `\Deleted` and expunging
      * exactly the moved UIDs under `UIDPLUS` (RFC 4315), which never expunges another message. A server with neither fails with
      * [[EmailCapabilityMissingException]].
      *
      * The fallback is three commands, so it is not atomic (RFC 6851 section 1), and a refusal's `command` names the step that failed. A
      * refused `COPY` leaves every message where it was. A refused `STORE` leaves them copied and still in the source, so both mailboxes
      * hold them. A refused `EXPUNGE` leaves them copied and flagged `\Deleted` in the source. A set of UIDs too long for one command line
      * runs the three steps once per part, in order, so a failure leaves the earlier parts moved.
      */
    def move(uids: Seq[Email.Uid], to: Email.MailboxName)(using Frame): Unit < (Async & Abort[EmailMoveFailure] & EmailReceive) =
        held("move")(_.move(Chunk.from(uids), to))

    /** Sends `command` with a tag of the session's and answers with what the server sent until its `OK`. The command may change the
      * selected mailbox, so the next verb that needs one selects it again.
      */
    def custom(command: EmailReceive.Command)(using Frame): EmailReceive.Reply < (Async & Abort[EmailImapCustomFailure] & EmailReceive) =
        held("custom")(_.custom(command))

    // Every verb's union holds EmailConnectFailure, so the session's own open, run by the first verb that needs it, fails within it.
    private def held[A, F >: EmailConnectFailure](method: String)(verb: ImapSession => A < (Async & Abort[F]))(using
        Frame
    ): A < (Async & Abort[F] & EmailReceive) =
        Env.use[ImapClient](_.session(method).map(verb))

    /** What `EmailReceive.receive` delivers to its handler. IMAP takes no answer for a delivery, so every case is handled with `Unit`.
      *
      * `Received` is one new message: its UID, flags, the time the server received it, its size, and its octets as stored. `uid` is the
      * resume checkpoint: persist it once the handler has processed the message, and resume a later loop with `EmailReceive.Start.After(uid)`.
      * The message is not parsed until `message` is called, and a message that fails to parse fails that call only; the loop moves on once
      * the handler returns.
      *
      * `raw` compares by content, so two deliveries of the same octets are equal.
      *
      * `Unreadable` is a new message the loop could not deliver as `Received`: the server wrote one of its fields in a form the module does
      * not read, or its size is past the config's `maxResponseLength`, in which case its body was never asked for. `item` says which. The
      * loop moves past it as past any delivery, so one such message never holds back the ones after it. The handler may read the message
      * with `EmailReceive.fetchMessage(uid)`, which asks for the body alone, or skip it.
      *
      * @see
      *   [[EmailReceive.receive]] for the loop that delivers these
      */
    sealed trait InboxEvent derives CanEqual

    object InboxEvent:

        final case class Received(uid: Email.Uid, flags: Set[Email.Flag], internalDate: Instant, size: ByteSize, raw: Span[Byte])
            extends EmailReceive.InboxEvent:

            /** The message parsed from `raw`, on each call. */
            def message(using Frame): Email.Message < Abort[EmailParseFailure] = Email.Message.parse(raw)

            override def equals(other: Any): Boolean =
                other match
                    case that: Received =>
                        that.uid == uid && that.flags == flags && that.internalDate == internalDate && that.size == size && that.raw.is(raw)
                    case _ => false

            override def hashCode: Int = (uid, flags, internalDate, size, raw.hash).##
        end Received

        final case class Unreadable(uid: Email.Uid, item: Unreadable.Item) extends EmailReceive.InboxEvent

        object Unreadable:
            /** Why a message was not delivered as `Received`. */
            enum Item derives CanEqual:
                /** The `INTERNALDATE` is not in RFC 9051's `date-time` form. */
                case InternalDate

                /** The `FLAGS` list holds something other than a flag. */
                case Flags

                /** The server gave no `RFC822.SIZE` that reads as a number: in the listing, and again when asked for that message alone, or
                  * with the body.
                  */
                case Size

                /** The message is `size` octets, past the config's `maxResponseLength`, `max`. */
                case TooLarge(size: ByteSize, max: ByteSize)
            end Item
        end Unreadable

    end InboxEvent

    /** What `EmailReceive.fetch` reads of one message without downloading it: its UID, its flags, when the server received it, its size, its
      * header section and its MIME structure.
      *
      * `header` is [[Email.Message.parse]] over the header section alone: its fields (`from`, `subject`, `date`, ...) and `headers` are
      * filled, and its bodies, attachments and inline parts are empty. `structure` describes every part, so a caller can pick one to
      * download with `fetchPart` by its `path`. `size` is the size of the whole message as stored, `RFC822.SIZE`.
      */
    final case class Summary(
        uid: Email.Uid,
        flags: Set[Email.Flag],
        internalDate: Instant,
        size: ByteSize,
        header: Email.Message,
        structure: EmailReceive.Part
    ) derives CanEqual

    /** One part of a message's MIME structure as the server describes it (`BODYSTRUCTURE`, RFC 9051 section 7.5.2), read without fetching
      * the message.
      *
      * `path` is the part's number in `fetchPart`'s numbering (RFC 9051 section 6.4.5): the children of a multipart are numbered from 1, a
      * nested part's number extends its parent's (`1.2`), and the body of a message with a single part is `1`. The root of a multipart
      * message has the empty path; a `message/rfc822` part has the attached message's structure as its one child, whose parts extend its
      * number.
      *
      * `mediaType` carries the parameters after RFC 2231 and encoded words are decoded, as [[Email.Message.parse]] reads them; `encoding`
      * is the transfer encoding as the server names it, lowercased; `size` is the size of the encoded content. `fileName` is the
      * disposition's `filename`, or the type's `name` when the part has no disposition, the rule `Email.Message.parse` applies.
      */
    final case class Part(
        path: Chunk[Int],
        mediaType: Email.MediaType,
        encoding: String,
        size: ByteSize,
        disposition: Maybe[String],
        fileName: Maybe[String],
        contentId: Maybe[Email.ContentId],
        children: Chunk[EmailReceive.Part]
    ) derives CanEqual

    /** A mailbox as `EmailReceive.listMailboxes` reports it: its name, decoded; the character that separates levels of its hierarchy, when it
      * has one; and its attributes.
      *
      * The attributes are RFC 9051's (section 7.3.1) and the special uses of RFC 6154 and RFC 8457 (`\Sent`, `\Trash`, `\Important`, ...),
      * each read in any case of its name. The special uses are what identify a server's sent, draft or junk mailbox, whose names vary with
      * the server and the user's language. An attribute the module does not model is kept as `Other`, as the server wrote it.
      */
    final case class Mailbox(name: Email.MailboxName, delimiter: Maybe[Char], attributes: Set[EmailReceive.Mailbox.Attribute])
        derives CanEqual

    object Mailbox:

        enum Attribute(val wire: String) derives CanEqual:
            case NoInferiors   extends Attribute("\\Noinferiors")
            case NoSelect      extends Attribute("\\Noselect")
            case NonExistent   extends Attribute("\\NonExistent")
            case HasChildren   extends Attribute("\\HasChildren")
            case HasNoChildren extends Attribute("\\HasNoChildren")
            case Marked        extends Attribute("\\Marked")
            case Unmarked      extends Attribute("\\Unmarked")
            case Subscribed    extends Attribute("\\Subscribed")
            case Remote        extends Attribute("\\Remote")
            case All           extends Attribute("\\All")
            case Archive       extends Attribute("\\Archive")
            case Drafts        extends Attribute("\\Drafts")
            case Flagged       extends Attribute("\\Flagged")
            case Junk          extends Attribute("\\Junk")
            case Sent          extends Attribute("\\Sent")
            case Trash         extends Attribute("\\Trash")
            case Important     extends Attribute("\\Important")

            case Other(override val wire: String) extends Attribute(wire)
        end Attribute

        object Attribute:
            // Every case but `Other`, so the atom each declares is the only place its wire text is written.
            private val modelled: Chunk[Attribute] = Chunk(
                NoInferiors,
                NoSelect,
                NonExistent,
                HasChildren,
                HasNoChildren,
                Marked,
                Unmarked,
                Subscribed,
                Remote,
                All,
                Archive,
                Drafts,
                Flagged,
                Junk,
                Sent,
                Trash,
                Important
            )

            private[kyo] def fromWire(atom: String): Attribute =
                Maybe.fromOption(modelled.find(a => Ascii.equalsIgnoreCase(a.wire, atom))).getOrElse(Other(atom))
        end Attribute

    end Mailbox

    /** A mailbox's counts as `EmailReceive.status` reports them, read without selecting it (RFC 9051 section 6.3.11): the number of messages,
      * the number without `\Seen`, the UID the next message will receive, and the UIDVALIDITY under which its UIDs are valid.
      *
      * `uidNext` is a lower bound for new UIDs, which a caller can use to resume from where a mailbox stood; a UID stored alongside the
      * `uidValidity` stays valid only while the mailbox reports the same UIDVALIDITY.
      */
    final case class MailboxStatus(
        mailbox: Email.MailboxName,
        messages: Long,
        unseen: Long,
        uidNext: Long,
        uidValidity: Email.UidValidity
    ) derives CanEqual

    /** A search query for `EmailReceive.search`: a tree of IMAP search keys (RFC 9051 section 6.4.4).
      *
      * The leaves test a flag, an address field, the subject, the body, the whole text, one header field, the internal date or the size;
      * `And`, `Or` and `Not` combine them. Text keys match a substring without regard to case, as the server defines it. A query holding
      * text that is not ASCII is sent with `CHARSET UTF-8`, which a server that refuses it answers with `BADCHARSET`, failing the search
      * with [[EmailCapabilityMissingException]] for `Capability.ImapUtf8Search`.
      *
      * `Since` and `Before` compare the message's internal date (when the server received it) by calendar day: IMAP's search dates are
      * days, so each sends the UTC date of its instant, and `Since` includes that day while `Before` excludes it. `Header.init` validates its
      * field name as a header field name and fails with [[EmailInvalidHeaderException]] otherwise.
      */
    sealed trait Search derives CanEqual

    object Search:

        case object All extends EmailReceive.Search

        case object Seen extends EmailReceive.Search

        case object Unseen extends EmailReceive.Search

        case object Flagged extends EmailReceive.Search

        case object Unflagged extends EmailReceive.Search

        case object Answered extends EmailReceive.Search

        case object Deleted extends EmailReceive.Search

        case object Draft extends EmailReceive.Search

        final case class Keyword(flag: Email.Flag.Keyword) extends EmailReceive.Search

        final case class From(text: String) extends EmailReceive.Search

        final case class To(text: String) extends EmailReceive.Search

        final case class Cc(text: String) extends EmailReceive.Search

        final case class Subject(text: String) extends EmailReceive.Search

        final case class Body(text: String) extends EmailReceive.Search

        final case class Text(text: String) extends EmailReceive.Search

        final case class Header private (name: String, value: String) extends EmailReceive.Search

        object Header:
            /** The key matching `value` in the field `name`, or [[EmailInvalidHeaderException]] when `name` is not a field name. */
            def init(name: String, value: String)(using Frame): Result[EmailInvalidHeaderException, Header] =
                if name.isEmpty || !name.forall(c => c >= '!' && c <= '~' && c != ':') then
                    Result.fail(EmailInvalidHeaderException(name, EmailInvalidHeaderException.Problem.InvalidName))
                else Result.succeed(new Header(name, value))
        end Header

        final case class Since(instant: Instant) extends EmailReceive.Search

        final case class Before(instant: Instant) extends EmailReceive.Search

        final case class Larger(size: ByteSize) extends EmailReceive.Search

        final case class Smaller(size: ByteSize) extends EmailReceive.Search

        final case class And(first: EmailReceive.Search, rest: EmailReceive.Search*) extends EmailReceive.Search

        final case class Or(left: EmailReceive.Search, right: EmailReceive.Search) extends EmailReceive.Search

        final case class Not(query: EmailReceive.Search) extends EmailReceive.Search

    end Search

    /** What the server answered to an `EmailReceive.custom` command: every untagged response it sent, as text, and the text of its `OK`
      * completion.
      *
      * An untagged line holds its literals inline, each after its `{n}` marker and a CRLF, as they crossed the wire, so a caller can read
      * any response the module does not model. Octets that are not UTF-8 appear as U+FFFD. The credential never appears: every form of it
      * is replaced with `<redacted>`, as in every failure.
      */
    final case class Reply(untagged: Chunk[String], text: String) derives CanEqual

    /** The bracketed response code of an IMAP status response, such as the `NONEXISTENT` in `A001 NO [NONEXISTENT] Unknown Mailbox`.
      *
      * A code is the machine-readable reason behind a `NO` or `BAD`, so a caller branches on it rather than on the human text. The cases
      * are the failure codes RFC 5530 and RFC 9051 section 7.1 define, each with the atom it has on the wire. A code the module does not
      * model, including a server's own, is kept as [[EmailReceive.ResponseCode.Other]] with its atom and arguments, so nothing a server sent
      * is lost.
      *
      * Atoms are case-insensitive over US-ASCII only: `nonexistent` is `NonExistent`, while a look-alike spelled with a non-ASCII letter is
      * an `Other`.
      *
      * `UNAVAILABLE` is the one code the receive loop treats as temporary: it retries the session under its reconnect schedule.
      *
      * @see
      *   [[kyo.EmailImapCommandException]] and [[kyo.EmailAuthenticationException]] for where codes appear
      */
    enum ResponseCode(val atom: String) derives CanEqual:
        case Alert                extends EmailReceive.ResponseCode("ALERT")
        case AlreadyExists        extends EmailReceive.ResponseCode("ALREADYEXISTS")
        case AuthenticationFailed extends EmailReceive.ResponseCode("AUTHENTICATIONFAILED")
        case AuthorizationFailed  extends EmailReceive.ResponseCode("AUTHORIZATIONFAILED")

        /** The server does not accept the charset a command named; `supported` lists those it does when it sent the list (RFC 9051 section
          * 7.1), and is empty otherwise.
          */
        case BadCharset(supported: Chunk[String]) extends EmailReceive.ResponseCode("BADCHARSET")
        case Cannot                               extends EmailReceive.ResponseCode("CANNOT")
        case ClientBug                            extends EmailReceive.ResponseCode("CLIENTBUG")
        case ContactAdmin                         extends EmailReceive.ResponseCode("CONTACTADMIN")
        case Corruption                           extends EmailReceive.ResponseCode("CORRUPTION")
        case Expired                              extends EmailReceive.ResponseCode("EXPIRED")
        case ExpungeIssued                        extends EmailReceive.ResponseCode("EXPUNGEISSUED")
        case InUse                                extends EmailReceive.ResponseCode("INUSE")
        case Limit                                extends EmailReceive.ResponseCode("LIMIT")
        case NoPerm                               extends EmailReceive.ResponseCode("NOPERM")
        case NonExistent                          extends EmailReceive.ResponseCode("NONEXISTENT")
        case OverQuota                            extends EmailReceive.ResponseCode("OVERQUOTA")
        case Parse                                extends EmailReceive.ResponseCode("PARSE")
        case PrivacyRequired                      extends EmailReceive.ResponseCode("PRIVACYREQUIRED")
        case ServerBug                            extends EmailReceive.ResponseCode("SERVERBUG")
        case TryCreate                            extends EmailReceive.ResponseCode("TRYCREATE")
        case Unavailable                          extends EmailReceive.ResponseCode("UNAVAILABLE")

        /** A code the module does not model: its atom as sent, and its arguments when it has any. */
        case Other(override val atom: String, arguments: Maybe[String]) extends EmailReceive.ResponseCode(atom)

        /** The code as it appears on the wire, without brackets. */
        def show: String =
            this match
                case Other(atom, arguments)                      => arguments.fold(atom)(a => s"$atom $a")
                case BadCharset(supported) if supported.nonEmpty => s"$atom (${supported.mkString(" ")})"
                case code                                        => code.atom
    end ResponseCode

    object ResponseCode:

        /** The code a server sent as `atom` with optional `arguments`. `BADCHARSET` takes a parenthesized list of charsets and every other
          * modelled atom none, so a code whose arguments are off its grammar is kept as an `Other` rather than dropping them.
          */
        private[kyo] def fromWire(atom: String, arguments: Maybe[String]): EmailReceive.ResponseCode =
            val upper = Ascii.toUpper(atom)
            arguments match
                case Absent                                   => byAtom.getOrElse(upper, Other(atom, Absent))
                case Present(listed) if upper == "BADCHARSET" => charsets(listed).fold(Other(atom, arguments))(BadCharset(_))
                case _                                        => Other(atom, arguments)
            end match
        end fromWire

        // RFC 9051 section 7.1: `"(" charset *(SP charset) ")"`, each charset an atom or a quoted string.
        private def charsets(listed: String): Maybe[Chunk[String]] =
            if listed.length < 3 || listed.head != '(' || listed.last != ')' then Absent
            else
                val names = Chunk.from(listed.substring(1, listed.length - 1).split(' '))
                    .map(name =>
                        if name.length >= 2 && name.head == '"' && name.last == '"' then name.substring(1, name.length - 1) else name
                    )
                if names.exists(_.isEmpty) then Absent else Present(names)

        // Every case but `Other`. `show` renders each case's own atom and `fromWire` reads the same atoms back, so the atom a case declares
        // is the only place its wire text is written.
        private[kyo] val modelled: Chunk[EmailReceive.ResponseCode] = Chunk(
            Alert,
            AlreadyExists,
            AuthenticationFailed,
            AuthorizationFailed,
            BadCharset(Chunk.empty),
            Cannot,
            ClientBug,
            ContactAdmin,
            Corruption,
            Expired,
            ExpungeIssued,
            InUse,
            Limit,
            NoPerm,
            NonExistent,
            OverQuota,
            Parse,
            PrivacyRequired,
            ServerBug,
            TryCreate,
            Unavailable
        )

        private val byAtom: Map[String, EmailReceive.ResponseCode] = modelled.map(code => code.atom -> code).toMap

    end ResponseCode

    /** One IMAP command line for the session's escape hatch, `EmailReceive.custom`, without its tag: the module adds the tag and the CRLF.
      *
      * The line is validated at construction, so what the caller wrote is exactly one command. It cannot hold CR, LF or NUL, which would
      * end the line and run the rest as a second command, and it cannot end with a literal marker (`{n}` or `{n+}`, RFC 9051 section 4.3),
      * which would leave the server waiting for octets that are never sent; a command that needs a literal is outside what `custom` sends.
      * Its UTF-8 form is at most 8192 octets, the line length RFC 7162 section 4 has clients stay within.
      *
      * `custom` sends the line and reads through its tagged completion, so a command whose exchange does more is refused by its first word,
      * in any case: `IDLE` (its continuation ends only with `DONE`), `AUTHENTICATE` and `LOGIN` (a credential the session cannot redact, on
      * a session already authenticated), `STARTTLS` (a TLS upgrade) and `COMPRESS` (a compressed stream). `init` fails with
      * [[EmailInvalidCommandException]] otherwise.
      */
    final class Command private (val value: String) derives CanEqual:
        override def equals(other: Any): Boolean =
            other match
                case that: EmailReceive.Command => that.value == value
                case _                          => false

        override def hashCode: Int = value.hashCode

        override def toString: String = s"EmailReceive.Command(${EmailException.printable(value)})"
    end Command

    object Command:

        /** RFC 7162 section 4: a client limits the command lines it generates to about 8192 octets. */
        inline val MaxLength = 8192

        /** The command line `value`, or [[EmailInvalidCommandException]] when it is not one command `custom` can carry. */
        def init(value: String)(using Frame): Result[EmailInvalidCommandException, EmailReceive.Command] =
            CommandLine.check(value, MaxLength).flatMap { line =>
                val command = kyo.internal.Ascii.toUpper(line.takeWhile(_ != ' '))
                if literal.matches(line) then Result.fail(EmailInvalidCommandException(EmailInvalidCommandException.Problem.Literal))
                else if refused.contains(command) then
                    Result.fail(EmailInvalidCommandException(EmailInvalidCommandException.Problem.Unsupported(command)))
                else Result.succeed(new EmailReceive.Command(line))
                end if
            }

        private val literal = ".*\\{[0-9]+\\+?\\}".r

        private val refused = Set("IDLE", "AUTHENTICATE", "LOGIN", "STARTTLS", "COMPRESS")

        // Why each refused command is outside what `custom` carries; `custom` sends a line and reads through its tagged completion.
        private[kyo] def unsupported(command: String): String =
            command match
                case "IDLE"                   => "the server answers with a continuation that only DONE ends (RFC 2177 section 3)"
                case "AUTHENTICATE" | "LOGIN" =>
                    "it carries a credential the session cannot redact, and the session is already authenticated " +
                        "(RFC 9051 sections 6.2.2 and 6.2.3)"
                case "STARTTLS" =>
                    "it upgrades the connection to TLS, which the session does only when it connects (RFC 9051 section 6.2.1)"
                case _ => "the server compresses the stream after its completion, which the session cannot read (RFC 4978)"

    end Command

end EmailReceive
