# kyo-email

kyo-email reads mail from an IMAP server, sends it through an SMTP submission server, and models messages as typed values. `EmailImap.run` receives the new messages of one mailbox forever, one at a time and in UID order, over a connection of its own that it renews and reconnects; the verbs on `EmailImap` search, fetch, flag and move messages on a session. `EmailSmtp.send` and `EmailSmtp.reply` submit a message. `Email.Message` is the message model for both directions: `Email.Message.parse` reads the MIME form a server stores and `Email.Message.render` writes it back.

The types both protocols share live in `Email`: the message and its parts, addresses, flags, credentials, TLS settings and the identifiers. The types only IMAP uses live in `EmailImap` and the ones only SMTP uses in `EmailSmtp`, next to the verbs that take them.

Every operation fails through its own sealed union, such as `EmailRunFailure` or `EmailFetchPartFailure`, which names exactly the failures that operation can raise, so a `match` over it is exhaustive and holds no case that cannot happen. The module is built for the JVM, JS, Native and WASM, over kyo-net's TCP and TLS.

<!-- doctest:setup
```scala
import kyo.*
def literal[E, A](checked: Result[E, A]): A =
    checked match
        case Result.Success(value) => value
        case other                 => throw new IllegalStateException(s"a README literal is invalid: $other")
val app      = literal(Email.Password.init("app-password"))
val auth     = literal(Email.Auth.Password.init("support@example.com", app))
val config   = literal(EmailImapConfig.init("imap.example.com", auth))
val inbox    = Email.MailboxName.Inbox
val archive  = literal(Email.MailboxName.init("Archive"))
val pdfBytes = Array[Byte](37, 80, 68, 70)
val outbox   = literal(EmailSmtpConfig.init("smtp.example.com", auth))
val team     = Email.Address("support@example.com", Present("Support"))
```
-->

```scala
import kyo.*

val support: Result[EmailInvalidTokenException | EmailInvalidConfigException, EmailImapConfig[Nothing]] =
    for
        password <- Email.Password.init("app-password")
        auth     <- Email.Auth.Password.init("support@example.com", password)
        config   <- EmailImapConfig.init("imap.example.com", auth)
    yield config

val tickets: Unit < (Async & Abort[EmailRunFailure | EmailParseFailure | EmailInvalidTokenException | EmailInvalidConfigException]) =
    Abort.get(support).map { config =>
        EmailImap.run(config, EmailImap.Start.New(Email.MailboxName.Inbox)) {
            case received: EmailImap.InboxEvent.Received =>
                received.message.map(message => Log.info(s"new ticket: ${message.subject}"))
            case EmailImap.InboxEvent.Unreadable(uid, item) =>
                Log.warn(s"UID ${uid.value} could not be read: $item")
        }
    }
```

Credentials, configs, mailbox names, flags and commands are checked when they are built: each `init` returns a `Result` holding the value or the violation, so an invalid one never reaches a connection.

The examples below follow one support mailbox: new tickets arrive in `INBOX`, the team searches and answers them by mail, and answered tickets move to `Archive`.

## Receiving new mail

A mail client that waits for new messages has three jobs besides reading them: remembering where it stopped, waiting without polling a server that can push, and reconnecting when the connection drops without losing or repeating a message. `EmailImap.run` does all three and hands each new message to your handler.

### Where the loop starts

`EmailImap.Start` names the mailbox and the first message:

- `Start.New(mailbox)` delivers only what arrives after the loop starts: the UIDs at or above the mailbox's `UIDNEXT` when the loop first selects it.
- `Start.All(mailbox)` delivers every message in the mailbox, oldest UID first, then the ones that arrive.
- `Start.After(uid)` resumes after a message delivered earlier. The UID carries its mailbox and its `UIDVALIDITY`, so a start cannot name one mailbox and resume from another's UID.

A delivery's `uid` is the resume checkpoint. Persist it once the handler has processed the message, and start the next process from it. `Email.Uid` has a `Schema`, so any kyo-schema codec stores it:

```scala
def resume(last: Email.Uid): Unit < (Async & Abort[EmailRunFailure]) =
    EmailImap.run(config, EmailImap.Start.After(last)) {
        case received: EmailImap.InboxEvent.Received => Log.info(s"ticket ${received.uid.value}")
        case _: EmailImap.InboxEvent.Unreadable      => Kyo.unit
    }
```

If the server renumbered the mailbox since the UID was stored (its `UIDVALIDITY` changed), the loop fails with `EmailUidValidityChangedException` before delivering anything, since every stored UID of that mailbox is then meaningless.

### What the handler receives

The handler receives an `EmailImap.InboxEvent`, and IMAP takes no answer for a delivery, so every handler returns `Unit`.

`Received` is one new message: its UID, flags, the time the server received it, its size, and its octets as stored. The message is not parsed until you call `message`, and a message that fails to parse fails that call only:

```scala
def triage(received: EmailImap.InboxEvent.Received): Unit < (Sync & Abort[EmailParseFailure]) =
    received.message.map { message =>
        val customer = message.from.headOption.map(_.address).getOrElse("unknown sender")
        Log.info(s"$customer: ${message.subject} (${received.size.show})")
    }
```

`Unreadable(uid, item)` is a new message the loop could not deliver as `Received`: the server wrote its `INTERNALDATE`, its `FLAGS` or its size in a form the module does not read, or its size is past the config's `maxResponseLength` (`TooLarge(size, max)`), in which case its body was never asked for. The loop moves past it as past any delivery, so one such message never holds back the messages after it. The handler may read it with `EmailImap.fetchMessage(uid)`, which asks for the body alone, or skip it.

### Acting on a message from the handler

The handler often needs to act on what it was handed: flag it, move it, download one attachment. The loop binds `Env[EmailImap]` to its own connection while the handler runs, so the verbs of the next section work inside the handler with no second login:

```scala
val filed: Unit < (Async & Abort[EmailRunFailure | EmailAddFlagsFailure | EmailMoveFailure | EmailInvalidFlagException]) =
    Abort.get(Email.Flag.Keyword.init("Triaged")).map { triaged =>
        EmailImap.run(config, EmailImap.Start.New(inbox)) {
            case received: EmailImap.InboxEvent.Received =>
                EmailImap.addFlags(Seq(received.uid), Set(triaged)).andThen(EmailImap.move(Seq(received.uid), archive))
            case _: EmailImap.InboxEvent.Unreadable => Kyo.unit
        }
    }
```

A verb that selects another mailbox is fine: the loop selects its own mailbox again before the next message.

> **Caution:** the binding is for the handler's own fiber and its own run. A fiber the handler forks that runs a verb later waits for the loop's IDLE to end, up to `idleRenewal`, and after a reconnect finds that connection closed. Do the handler's work inline, or open a session of its own with `EmailImap.let`.

The next UID advances only after the handler returns, so a reconnect redelivers nothing the handler finished and loses nothing it had not reached.

### Waiting, reconnecting and failing

Between rounds the loop waits with IDLE (RFC 2177), where the server pushes new mail, renewing it every `idleRenewal`; on a server without IDLE it checks every `pollInterval`. It does not wait when the server announced a message during the round, in any reply.

A connect or transport failure, a dropped IDLE (`EmailIdleDroppedException`) and a `NO [UNAVAILABLE]` refusal are retried under `config.reconnect`, and the reconnect resumes from the next undelivered UID. The first connection is the exception: until its mailbox is selected, a failure ends the loop at once. The schedule starts over after each completed round, so a server that accepts every reconnect and then fails the same way is not retried forever. When the schedule allows no further attempt, the loop fails with the last failure.

Every other failure ends the loop, and so do the token computation's failure `E` and the handler's own failure `E2`, which reach the caller unchanged. The loop's row is therefore `Abort[EmailRunFailure | E | E2]`:

| Leaf of `EmailRunFailure` | When |
|---|---|
| `EmailConnectException` | the connection or its TLS could not be opened |
| `EmailTransportException` | a command timed out, the connection closed, or the server broke the protocol |
| `EmailAuthenticationException` | the server rejected the credential |
| `EmailAuthMechanismUnavailableException` | the server offers no mechanism the credential can use |
| `EmailImapCommandException` | the server refused a command |
| `EmailMailboxNotFoundException` | the mailbox does not exist |
| `EmailUidValidityChangedException` | the mailbox was renumbered |
| `EmailIdleDroppedException` | the connection closed or the server said `BYE` while idling |

The loop ends only by failure or interruption, and drops its connection either way. To keep receiving while the program does other work, run it on a fiber of its own, `Fiber.init(EmailImap.run(config, start)(handler))`, which the enclosing `Scope` interrupts when it closes.

## Connecting and authenticating

`EmailImapConfig` holds the server, the credential and the timings; `init` validates every field and fails with `EmailInvalidConfigException` on an empty host, a port outside 1 to 65535, or a duration or size outside its range.

```scala
val tuned: Result[EmailInvalidConfigException, EmailImapConfig[Nothing]] =
    EmailImapConfig.init(
        "imap.example.com",
        auth,
        tls = Email.Tls.StartTls,
        commandTimeout = 30.seconds,
        reconnect = Schedule.exponentialBackoff(1.second, 2.0, 1.minute),
        pollInterval = 5.minutes
    )

val patient: Result[EmailInvalidConfigException, EmailImapConfig[Nothing]] = config.pollInterval(10.minutes)
```

A setter named after a field replaces it: a checked field's setter validates the new value as `init` does and returns a `Result`, and `auth` and `reconnect`, which have no range, return the config.

- `tls`: `Email.Tls.Implicit` (the default, port 993) opens TLS before any protocol byte; `Email.Tls.StartTls` (port 143) upgrades a plaintext greeting before authenticating. There is no plaintext mode: a server that does not offer STARTTLS fails the connection with `EmailConnectException` of kind `StartTlsUnavailable`. Both values verify the certificate against the host from `Email.Tls.Trust.System`, allow TLS 1.2 to 1.3 and present no client certificate. `Email.Tls.Implicit(trust, minVersion, maxVersion, clientCertificate)` and its `StartTls` counterpart set all four: the trust anchors (`System`, a `CaFile`, or `TrustAll`, which exists for test servers only), the version range, and an `Email.Tls.ClientCertificate(chain, privateKey)`, two PEM files presented to a server that authenticates its clients by certificate; the module holds only the paths.
- `connectTimeout` bounds the TCP connect and TLS handshake; `commandTimeout` bounds each command's reply.
- `maxResponseLength` (150 MiB by default, Microsoft 365's largest message) bounds what one reply may hold. The receive loop never asks for a message past it.
- `reconnect`, `idleRenewal` and `pollInterval` govern the receive loop. `idleRenewal` and `pollInterval` are at most 29 minutes: a server may log out a client that sent nothing for 30 minutes (RFC 9051 section 5.4).

A password never prints: `Email.Password` and `Email.OAuthToken` render as `<redacted>`, and every form in which a credential crossed the wire is replaced with `<redacted>` in any server text a failure carries.

### OAuth

Gmail and Microsoft 365 require OAuth for most accounts. `Email.Auth.OAuth2` takes a computation that yields the token, which the module runs each time it authenticates, so obtaining, caching and refreshing tokens stay with you. The computation's own failure `E` reaches the row of every operation that authenticates:

```scala
enum TokenError:
    case Expired

def token: Email.OAuthToken < (Async & Abort[TokenError | EmailInvalidTokenException]) = Abort.get(Email.OAuthToken.init("ya29.a0Af"))

val oauth: Result[EmailInvalidConfigException, EmailImapConfig[TokenError | EmailInvalidTokenException]] =
    Email.Auth.OAuth2.init("support@example.com", token).flatMap(auth => EmailImapConfig.init("imap.gmail.com", auth))

val oauthTickets: Unit < (Async & Abort[EmailRunFailure | TokenError | EmailInvalidTokenException | EmailInvalidConfigException]) =
    Abort.get(oauth).map(config => EmailImap.run(config, EmailImap.Start.New(inbox)) { case _ => Kyo.unit })
```

> **Note:** the token computation runs again on every reconnect, which is when the network may be down. The module cannot tell which of your failures are temporary, so a failure of the computation ends a receive loop instead of being retried; retry inside the computation when that is what you want.

## Working with a session

Outside the loop, the verbs run on a session: one authenticated connection. `EmailImap.let` opens one for the duration of a computation, and the verbs read it from `Env[EmailImap]`:

```scala
val counts: EmailImap.MailboxStatus < (Async & Abort[EmailConnectFailure | EmailStatusFailure]) =
    EmailImap.let(config)(EmailImap.status(inbox))
```

`EmailImap.init` opens a session closed when the enclosing `Scope` ends, `initUnscoped` one closed by `EmailImap.close`, and `use` binds a session to a computation. A verb reads the session the innermost `let` or `use` bound. Verbs on one session run one at a time, in the order they acquire it, so concurrent fibers share a session safely.

A command whose reply never completes leaves the connection impossible to resynchronize, so a command that fails in transport (a timeout, a close, a `BYE`) closes the session, and every later verb fails with `EmailTransportException` of kind `ConnectionClosed` without writing. Open a new session to continue.

### Finding messages

`listMailboxes` reports every mailbox with its special use, which is how you find the sent or trash mailbox whose name varies with the server and the user's language; `status` reads a mailbox's counts without selecting it:

```scala
val trash: Maybe[EmailImap.Mailbox] < (Async & Abort[EmailListMailboxesFailure] & Env[EmailImap]) =
    EmailImap.listMailboxes.map(all => Maybe.fromOption(all.find(_.attributes.contains(EmailImap.Mailbox.Attribute.Trash))))
```

`search` answers the UIDs matching an `EmailImap.Search`, ascending. A query is a tree of keys combined with `And`, `Or` and `Not`; text keys match a substring without regard to case, and `Since` and `Before` compare the day the server received the message:

```scala
val unanswered: Chunk[Email.Uid] < (Async & Abort[EmailSearchFailure] & Env[EmailImap]) =
    EmailImap.search(
        inbox,
        EmailImap.Search.And(
            EmailImap.Search.Not(EmailImap.Search.Answered),
            EmailImap.Search.Or(EmailImap.Search.Subject("invoice"), EmailImap.Search.Body("refund")),
            EmailImap.Search.Since(Instant.Epoch)
        )
    )
```

A query holding text that is not ASCII is sent with `CHARSET UTF-8`; a server that refuses it answers `BADCHARSET`, which fails the search with `EmailCapabilityMissingException` for `Capability.ImapUtf8Search`.

### Reading messages

Reading a message comes in three sizes, and each marks nothing `\Seen`. `fetch` reads a summary per UID without downloading bodies: flags, received time, size, the parsed header section and the MIME structure. `fetchMessage` downloads and parses one whole message. `fetchPart` downloads one part by its path in the structure, with its transfer encoding decoded, which is how you take one attachment of a large message:

```scala
def parts(part: EmailImap.Part): Chunk[EmailImap.Part] = Chunk(part).concat(part.children.flatMap(parts))

def invoices(uids: Chunk[Email.Uid]): Chunk[Span[Byte]] < (Async & Abort[EmailFetchFailure | EmailFetchPartFailure] & Env[EmailImap]) =
    EmailImap.fetch(uids).map { summaries =>
        Kyo.foreach(summaries) { summary =>
            val pdfs = parts(summary.structure).filter(_.mediaType.baseType == "application/pdf")
            Kyo.foreach(pdfs)(part => EmailImap.fetchPart(summary.uid, part.path))
        }.map(_.flatten)
    }
```

A UID whose message was expunged has no summary in `fetch`'s answer; `fetchMessage` and `fetchPart` fail such a UID with `EmailMessageNotFoundException`.

A part's `mediaType` in the structure is read as a `Content-Type` field would be: a type or subtype the server reports that is not an RFC 2045 token makes it `text/plain; charset=us-ascii`, the default RFC 2045 section 5.2 gives a field that does not parse and the one `Email.Message.parse` gives the same part.

### Flags and moving

`addFlags` and `removeFlags` change the system flags (`Seen`, `Answered`, `Flagged`, `Deleted`, `Draft`) and keywords; `move` moves messages to another mailbox. A flag the server reports that the module does not model, another system flag or a keyword that is not an IMAP atom such as `$a%b`, arrives as `Email.Flag.Other` exactly as sent, and can be stored back:

```scala
def answered(uids: Chunk[Email.Uid]): Unit < (Async & Abort[EmailAddFlagsFailure | EmailMoveFailure] & Env[EmailImap]) =
    EmailImap.addFlags(uids, Set(Email.Flag.Answered)).andThen(EmailImap.move(uids, archive))
```

`move` uses `MOVE` (RFC 6851) when the server has it, and otherwise copies, flags `\Deleted` and expunges exactly the moved UIDs under `UIDPLUS` (RFC 4315), which never expunges another message. A server with neither fails with `EmailCapabilityMissingException`.

> **Caution:** the fallback is three commands, so it is not atomic. A refused `STORE` leaves the messages in both mailboxes, and a refused `EXPUNGE` leaves them copied and flagged `\Deleted` in the source; the failure's `command` names the step that failed.

### Commands the module does not model

`EmailImap.custom` sends one command line with a tag of the session's and answers every untagged response as text, with the `OK` completion's text, so you can reach an extension the module does not wrap:

```scala
val quota: Chunk[String] < (Async & Abort[EmailImapCustomFailure | EmailInvalidCommandException] & Env[EmailImap]) =
    Abort.get(EmailImap.Command.init("GETQUOTAROOT INBOX")).map(command => EmailImap.custom(command).map(_.untagged))
```

`EmailImap.Command` holds exactly one command: its `init` fails with `EmailInvalidCommandException` on a line break, on a literal marker the module would not send, and on `IDLE`, `AUTHENTICATE`, `LOGIN`, `STARTTLS` and `COMPRESS`, whose exchanges `custom` cannot complete. The command may select another mailbox; the next verb that needs one selects it again.

## Reading and writing messages

`Email.Message` is one model for received and outgoing mail: addresses, subject, date, threading ids, the `text` and `html` bodies, attachments, inline parts and every header. Parsing decodes transfer encodings, encoded words and charsets; rendering writes UTF-8 bodies and the MIME structure the parts need. Parsing a rendered message gives back the fields it was rendered from.

A reply keeps the thread by naming the message it answers in `inReplyTo` and extending its `references`. `EmailSmtp.reply` (next section) fills these for you; built by hand, the same reply reads:

```scala
def replyTo(ticket: Email.Message): Email.Message < Abort[kyo.mime.MimeInvalidMediaTypeException] =
    Abort.get(Email.MediaType.init("application", "pdf")).map { pdf =>
        Email.Message(
            from = Chunk(Email.Address("support@example.com", Present("Support"))),
            to = ticket.replyTo.concat(if ticket.replyTo.isEmpty then ticket.from else Chunk.empty),
            subject = s"Re: ${ticket.subject}",
            inReplyTo = ticket.messageId.toChunk,
            references = ticket.references.concat(ticket.messageId.toChunk),
            text = "Your invoice is attached.",
            attachments = Chunk(Email.Attachment(pdf, Present("invoice.pdf"), Span.from(pdfBytes)))
        )
    }

def wire(ticket: Email.Message): Span[Byte] < Abort[EmailRenderFailure | kyo.mime.MimeInvalidMediaTypeException] =
    replyTo(ticket).map(Email.Message.render)
```

`Email.MediaType` is kyo-mime's `MediaType`, so a value either module builds or parses is the other's. `Email.MediaType.init` lowercases the type, subtype and parameter names, and fails with kyo-mime's `MimeInvalidMediaTypeException` on a part that is not an RFC 2045 token or a parameter named twice.

`Email.Address` is not validated when built, since a received message may carry an address no strict parser accepts; rendering refuses one that is not an RFC 5322 `addr-spec` with `EmailInvalidAddressException`, and a header name or value it cannot write with `EmailInvalidHeaderException`, so no value can start a header line of its own.

Parsing is total: malformed mail becomes the model it most plausibly means, a header that does not read stays in `headers`, and a part that cannot become text is kept as an attachment. Its one failure is multiparts nested more than 100 deep:

```scala
def parsed(raw: Span[Byte]): Email.Message < Abort[EmailParseFailure] = Email.Message.parse(raw)
```

Parts shown inside the HTML body by `Content-ID` (`<img src="cid:logo">`) are `Email.InlinePart`s rather than attachments; the parser tells them apart by the MIME structure, never by scanning the HTML.

## Sending mail

`EmailSmtp` submits messages to a submission server (RFC 6409), the server your provider gives for mail clients. No connection is held between messages: each verb connects, authenticates, sends and closes before it returns. So `EmailSmtp.let` binds the config for a computation and needs no `Scope`, and the verbs read it from `Env[EmailSmtp[E]]`:

```scala
def acknowledge(ticket: Email.Message): Email.MessageId < (Async & Abort[EmailSendFailure]) =
    EmailSmtp.let(outbox) {
        EmailSmtp.reply(ticket, Email.Message(from = Chunk(team), text = "We received your ticket and will answer within a day."))
    }
```

`reply` fills what the reply leaves empty: `inReplyTo` and `references` from the ticket (RFC 5322 section 3.6.4), the subject as `Re: ` and the ticket's unless it already begins with `Re:`, and, when the reply names no recipient, the ticket's `replyTo`, or its `from`. A field you set is kept. It then sends as `send` does.

`send` answers the `Message-ID` the message went out with. It fills `messageId` (a random id at the sender's domain) and `date` (`Clock.now`) when they are absent, sends to every address of `to`, `cc` and `bcc`, and never writes `Bcc` into the message:

```scala
def escalate(ticket: Email.Message): Email.MessageId < (Async & Abort[EmailSendFailure] & Env[EmailSmtp[Nothing]]) =
    EmailSmtp.send(
        Email.Message(
            from = Chunk(team),
            to = Chunk(Email.Address("billing@example.com")),
            bcc = Chunk(Email.Address("audit@example.com")),
            subject = s"Escalated: ${ticket.subject}",
            text = ticket.text
        )
    )
```

What a message lacks fails before anything connects: no `from`, several `from` and no `sender` (RFC 5322 section 3.6.2), or no recipient is `EmailIncompleteMessageException`; an envelope address that is not an `addr-spec`, `bcc` included, is `EmailInvalidAddressException`; and a message the renderer refuses fails as `render` does. The token computation of an OAuth credential has not run by then.

Once connected, a message larger than the server's advertised `SIZE` (RFC 1870) fails with `EmailMessageTooLargeException` before `MAIL`. An address past ASCII needs the server's `SMTPUTF8` (RFC 6531), and content past ASCII its `8BITMIME` (RFC 6152); without them the send fails with `EmailCapabilityMissingException`. If the server refuses any recipient, the client resets the transaction and fails with one `EmailRecipientRefusedException` listing every refusal with its code and enhanced status (RFC 3463), so the message reaches none of them. Once the server accepts the data the message is sent, and nothing after that fails the verb.

`EmailSmtpConfig` defaults to TLS from the first byte on port 465; `Email.Tls.StartTls` upgrades a plaintext connection on port 587, and fails with `StartTlsUnavailable` before any credential when the server does not offer it. `clientName` is the name the client announces with `EHLO`; set it to the host's public name when it has one. With an OAuth credential, the computation runs once per connection the verb opens and its failure `E` joins the verb's row; the verb's `E` is inferred from the enclosing `let`. A verb reaches only a client whose `E` its row admits, so an inner `let` of another `E` never captures a verb written for the outer one. When two enclosing clients both fit a verb's `E`, it reaches the outer one, the first bound.

Each verb ends its connection with `QUIT`, after a refusal too, unless the connection was lost. A `421` reply to any command means the server is closing the channel: the verb fails with `EmailTransportException` of kind `ConnectionClosed` holding the server's text, and sends nothing more.

`send` and `reply` retry nothing unless the config holds a `retry` schedule. With one, a transient reply is tried again on a new connection after the schedule's next delay: a `4xx` refusal, which RFC 5321 section 4.2.1 defines as an action that did not occur, every recipient refused with `4xx` (greylisting), or a `421`. A permanent refusal, an authentication refusal, a connection lost with no reply and a failed token computation fail at once, and `custom` is never retried. When the schedule ends, or its next delay is longer than `retryMaxDelay` (60 seconds by default), the verb fails with the last failure:

```scala
val persistent: EmailSmtpConfig[Nothing] = outbox.retry(Present(Schedule.exponentialBackoff(1.second, 2.0, 30.seconds).take(5)))
```

`EmailSmtp.custom` sends command lines after authenticating and answers each reply, with its code, enhanced code and lines:

```scala
val checked: Chunk[EmailSmtp.Reply] < (Async & Abort[EmailSmtpCustomFailure | EmailInvalidCommandException]) =
    Abort.get(EmailSmtp.Command.init("VRFY billing")).map(command => EmailSmtp.let(outbox)(EmailSmtp.custom(Seq(command))))
```

A `4xx` or `5xx` reply fails with `EmailSmtpRejectedException` naming the command, and the lines after it are not sent. `EmailSmtp.Command.init` fails with `EmailInvalidCommandException` on a line break, a line past 510 octets, and `DATA`, `BDAT`, `STARTTLS`, `AUTH` and `QUIT`, whose exchanges `custom` cannot carry.

## Handling failures

Each operation's row names its own union; each leaf is a case class with the operation's name in `method` and the fields a handler needs.

| Operation | Row |
|---|---|
| `EmailImap.run` | `EmailRunFailure \| E \| E2` |
| `EmailImap.let`, `init`, `initUnscoped` | `EmailConnectFailure \| E` |
| `listMailboxes` | `EmailListMailboxesFailure` |
| `status` | `EmailStatusFailure` |
| `search` | `EmailSearchFailure` |
| `fetch` | `EmailFetchFailure` |
| `fetchMessage` | `EmailFetchMessageFailure` |
| `fetchPart` | `EmailFetchPartFailure` |
| `addFlags` / `removeFlags` | `EmailAddFlagsFailure` / `EmailRemoveFlagsFailure` |
| `move` | `EmailMoveFailure` |
| `custom` | `EmailImapCustomFailure` |
| `EmailSmtp.send`, `EmailSmtp.reply` | `EmailSendFailure \| E` |
| `EmailSmtp.custom` | `EmailSmtpCustomFailure \| E` |
| `Email.Message.parse` | `EmailParseFailure` |
| `Email.Message.render` | `EmailRenderFailure` |

Every session verb can fail with `EmailTransportException` and `EmailImapCommandException`. A verb that names a mailbox adds `EmailMailboxNotFoundException`, and one that takes UIDs adds `EmailUidValidityChangedException`, raised without touching the messages when a UID was issued under a `UIDVALIDITY` the mailbox no longer reports. `fetchMessage` adds `EmailMessageNotFoundException` and `EmailMimeException`; `fetchPart` adds `EmailMessageNotFoundException`, `EmailPartNotFoundException` and `EmailTransferDecodeException`; `move` and `search` add `EmailCapabilityMissingException`.

Every `EmailSmtp` verb can fail with `EmailConnectException`, `EmailTransportException`, `EmailAuthenticationException`, `EmailAuthMechanismUnavailableException` and `EmailSmtpRejectedException`, whose `permanent` tells a `5xx` refusal from a `4xx` one a later attempt may clear. `send` and `reply` add the message failures of the previous section: `EmailIncompleteMessageException`, `EmailInvalidAddressException`, `EmailInvalidHeaderException`, `EmailMessageTooLargeException`, `EmailCapabilityMissingException` and `EmailRecipientRefusedException`.

## Running on every platform

The module is built for the JVM, Native, JS and WASM through kyo-net, which drives TLS with its in-process engine on each; JS and WASM run on Node. Its test suite is shared by all four and CI runs it on each. Every example above is the same on all four.

## What kyo-email does not do

- It does not write to a mailbox: there is no `APPEND`, and no verb creates, renames or deletes a mailbox. `custom` cannot send `APPEND`, since its message is a literal.
- It does not synchronize a mailbox. There is no CONDSTORE or QRESYNC, and the receive loop reports new messages only, not flag changes or expunges.
- It does not speak POP3 or JMAP.
- It does not deliver mail itself: sending goes through an authenticated submission server, with no MX lookup and no delivery on port 25.
- It does not sign, encrypt or verify messages: no DKIM, S/MIME or OpenPGP.
- It never obtains or refreshes an OAuth token; the computation you pass does.
