# Contributing to kyo-email

Module-specific guide for kyo-email. Read the repository-root [CONTRIBUTING.md](../CONTRIBUTING.md) first: it carries the conventions, naming, type vocabulary, test patterns and unsafe tiers that apply across Kyo. This document records only what is specific to kyo-email: its layers, the two effects and their handlers, where a type lives, the session invariant, the one place server text is redacted, the response bounds, the receive loop, the failure model, the unsafe boundary, and how its tests are built.

## What kyo-email is

The public surface is in `shared/src/main/scala/kyo`: the message model (`Email.Message` and the types it holds), the receiving effect and its loop (`EmailReceive`, `EmailImapConfig`, `EmailReceive.InboxEvent`), the sending effect (`EmailSend`, `EmailSmtpConfig`), `type Email` for both, the account, credential and TLS types, and the `EmailException` hierarchy. Everything under `kyo.internal.email` is `private[kyo]` and sits in these layers, each depending only on the ones before it (`imap` and `smtp` are siblings):

- `charset`: `Charsets`, mail's reading of a charset label over kyo-charset's `Charset.resolve` (the RFC 2231 language suffix, and the overrides where WHATWG's answer is wrong for mail), and IMAP's modified UTF-7.
- `mime`: `MimeParser`, `MimeModel` and `MimeRenderer`, and the codecs for headers, addresses, dates, message ids and transfer encodings.
- `net`: `LineConnection`, lines and counted octets over one kyo-net connection, and the `Redactor`.
- `imap`: `ImapCodec` (the wire grammar), `ImapModel` (wire values to public types), `ImapSession` (one authenticated connection), `ImapClient` (where a verb finds its session) and `ImapReceiver` (the receive loop).
- `smtp`: `SmtpCodec` (replies, enhanced codes, `EHLO` extensions and the data section), `SmtpSession` (one submission's connection) and `SmtpClient` (the config a verb reads). `Threading`, in `mime`, fills a reply's fields.

## The effects and their handlers

Sending and receiving are effects: a verb (`EmailSend.send`, `EmailReceive.fetch`, ...) puts `EmailSend` or `EmailReceive` in its row, and `EmailSend.run(smtpConfig)`, `EmailReceive.run(imapConfig)` or `Email.run(smtpConfig, imapConfig)` handles it. A computation whose verbs reach no handler does not compile, so a missing config is never a runtime failure. Each is an opaque type over `Env` of a `private[kyo]` client, as kyo-aeron's `Topic` is: the client cannot be summoned or provided outside the module, so `run` is the only way in, and there is no `let`, `init` or `use`.

There are two effects, not one, because their resources differ: a submission is a whole connection per verb and holds nothing, while receiving holds an authenticated session. A program that only sends needs no IMAP config and opens no IMAP connection. `type Email = EmailSend & EmailReceive` names a program that needs both.

`EmailReceive.run` holds one session, opened by the first verb that needs it, so a computation that reaches no verb never logs in, and logged out by a `Scope` finalizer when `run` ends, so success, failure and interruption all log out. `ImapClient.Lazy` keeps it: a mutex admits one opener, so concurrent first verbs share one login, and a verb that finds the held session closed by a transport failure opens a new one, since the caller has no other way to. `close` marks the slot ended before it logs out, and an open that completes after that closes what it opened, so no session outlives the `run` that held it. The receive loop is the exception to the shared session: a connection in IDLE takes no other command, so the loop opens its own, and binds `EmailReceive` to it (`ImapClient.Bound`) for its handler, whose verbs then need no second login.

Neither the configs nor the effects take a type parameter. The OAuth token computation fails with `EmailTokenException`, which the caller builds from its own failure, message and cause. A parameter for that failure would ride the config and the effect's key, so two handlers with different failures would be different effects and a verb would have to name one; a single leaf keeps every row closed and every effect monomorphic. `Email.Account` (user and `Email.Auth`) is shared by both configs, so a mailbox that sends and receives is described once.

SMTP has no escape hatch for raw commands. Each SMTP verb is a whole connection, so a raw command would run alone on a connection of its own, after a login, and could neither prepare nor follow a submission. IMAP's `custom` runs on the held session, where a command can change state the next verb sees.

The module decodes mail charsets through kyo-charset and never through `java.nio.charset`, which Scala.js and Native carry only in part; the JDK is called only for US-ASCII, ISO-8859-1 and UTF-8, which every platform has. Protocol keywords are compared and case-folded through kyo-data's `kyo.internal.Ascii`, never `toUpperCase`, whose result depends on the locale.

## Where a type lives

Package `kyo` holds only the effects, their configs and the exception hierarchy. A type both protocols use is a member of `object Email`; a type only one protocol uses is a member of `EmailReceive` or `EmailSend`. Nothing is exported back to package level.

A nested opaque type sits in an object of its own and is aliased from the enclosing object: `type MessageId = MessageId.Value`, with `opaque type Value = String` inside `object MessageId`, as kyo-data's `Render.Rendered` does. An opaque type declared directly in `object Email` would be transparent in all of `Email`'s template, so a `Tag` or `Schema` derived anywhere in that template would see the underlying `String` or `Long`: `Tag` refuses such a derivation, and `Schema.derived` of a record holding the type cannot summon its `Fields` and proceeds with none, so the Schema loses what they carry: a renamed field encodes under its new name and fails to decode. The object of its own confines that transparency to the type's companion. Inside the companion the type is its underlying `String` or `Long`, so its `Schema` is built on the named base given (`Schema.stringSchema.transformVia(init)(...)`): summoning `Schema[String]` there would find the given being defined. Every record holding one of these types has a Json round-trip leaf with the field populated.

## The session invariant: one command in flight, and no resynchronization

An `ImapSession` owns one connection, and exactly one command is in flight on it at a time. `exclusive` holds the session's mutex for a whole verb, so a verb's `SELECT` and the command it prepares never interleave with another fiber's; `command` runs one exchange from its write to its tagged completion under `commandTimeout`.

A command whose completion never arrived leaves the connection at an unknown point of the server's reply, and IMAP has no way to find the next response boundary from there. So any transport failure of a command (a deadline, a close, a `BYE`, a response past a bound) closes the connection through `bounded`, and every later command fails with `ConnectionClosed` without writing. Never add a path that catches a transport failure and keeps using the connection.

**The reader is never interrupted.** Interrupting a fiber that is reading drops whatever it had already taken from kyo-net's channel and not yet kept: measured over the in-memory pair, 500 of 500 interrupted `readLine` calls that had taken a chunk lost it, and the next read began in the middle of a response. So a read ends by the connection closing, never by `Async.timeout` around it. IDLE follows this: after the continuation, one reader reads through the tagged completion, and a watch fiber writes `DONE` at the first `EXISTS`, when `idleRenewal` passes, or at once when an `EXISTS` came before the continuation; if the completion does not follow within `commandTimeout`, the watch closes the connection. `bounded`'s deadline around a whole command is safe because it closes the connection whatever the reader held.

## One redaction point

Server text reaches a failure only through the `Redactor` the session builds from the credential before its first byte is written. It replaces every form in which the credential crossed the wire: the secret, IMAP's quoted form of it, the base64 of the SASL initial responses and of an `AUTH LOGIN` line, and the `\xNN` form `LineConnection.shown` gives a non-ASCII secret.

In `SmtpSession` every reply's text reaches a failure through `described` or `unexpected`, and `bounded` rewrites a transport failure's text through `redacted`. In `ImapSession` every failure that carries server text leaves through `protocol`, `redacted` or `refused`, each of which redacts: `refused` builds a command refusal, `protocol` a line the session could not read, and `redacted` rewrites the text of a transport failure from the connection or the parser, which `bounded` applies to every command's failure. A response code is server text too: `responseCode` redacts its arguments before a refusal or an authentication failure keeps it. A new failure that carries server text goes through one of them. Redaction runs before any cut to a display length (`Redactor.redact(text, limit)`): cutting first can split a form and leave its head in place. A text already cut where it was read may end in part of a form, so the redacted text also loses its longest tail that begins a form, never reaching into a mask.

## Response bounds

A server can announce a literal of any size, so the session refuses one past what remains of `maxResponseLength` before reading any of it (`next`), and every untagged response of a reply counts against the same budget with its literals. A line without a line end is at most `LineLimit` (1 MiB). An `ESEARCH` range counts as the plain `SEARCH` listing of its UIDs would, checked before it is expanded, and the expansion is one primitive array, so the result's UIDs are its only allocation per UID.

An SMTP reply has no literals, so `SmtpCodec.next` bounds a whole reply, its lines and line ends together, by `ReplyLimit` (1 MiB); each line is read with what remains of it as its limit.

The receive loop fetches one message per command, so a message's size bounds the reply. A listed size past `maxResponseLength` is delivered as `Unreadable(uid, TooLarge(size, max))` without asking for the body; a message within it may also take `FramingRoom`, the two lines around its body, so a message at the limit is read rather than refused on every attempt.

## The receive loop

`ImapReceiver.run` repeats rounds on a session until one fails:

1. **Mark** the session's count of `EXISTS` responses read.
2. **List** `UID FETCH <next>:* (UID RFC822.SIZE)`: the pending UIDs with their sizes, no bodies. That reply grows with the pending messages, so it is used only while the mailbox holds at most one window, `min(10000, maxResponseLength / 128)` messages. The 128 octets a line are an assumption: 70 for the line itself with ten-digit numbers, the rest for flags a server may add unsolicited, which nothing bounds. It binds only below a `maxResponseLength` of 1,280,000 octets, where the window is under the 10000 cap; above it a window's reply would need lines of more than `maxResponseLength / 10000` octets to pass the limit. A larger mailbox is listed in windows of sequence numbers, `FETCH a:b (UID RFC822.SIZE)`, each delivered before the next is asked, starting at the first sequence number whose UID is at least `next` (a binary search over `FETCH n (UID)`). Sequence numbers, not UID ranges, because UIDs can be sparse and an empty UID range still costs a round trip. An `EXPUNGE` shifts later messages below the cursor, so every window after the first starts one message early, and a UID there at least `next` sends the round back to the search; the session counts messages from `EXISTS` and `EXPUNGE` for this.
3. **Deliver** one UID at a time: select the mailbox again if the handler selected another, fetch the message, run the handler, and move `next` past the UID. A UID with no data (expunged since the listing) is passed over; a message the model cannot read is `Unreadable`.
4. **Wait**, unless the count moved since the mark: a server announces a new message in the reply to whatever command it is processing (RFC 9051 section 5.2), and IDLE reports only what arrives after it starts.

Each session runs in a `Scope.run` of its own, acquired with `Scope.acquireRelease(open)(abandon)`, so no step lies between opening a connection and registering its release (`Scope.acquireRelease` uses `ensureMap` for that reason). A session answers the failure that ended it as a value; the reconnect loop is outside, and what outlives a session (the next UID, the first `UIDVALIDITY`, the number of completed rounds) is the `Resume` cell. The handler runs outside every `Abort.run` over the loop's own failures, so its failure reaches the caller as raised. The first session's failures before its `SELECT` is checked are not retried; afterwards connect and transport failures, a dropped IDLE and `NO [UNAVAILABLE]` are, and a completed round restarts the schedule. `EmailTokenException` is an `EmailConnectFailure` but is never retried (`retried` lists what is): the computation runs on every reconnect, when the network may be down, and the module cannot tell which of the caller's failures are temporary.

## One submission

`SmtpSession.send` runs one connection from open to close inside a `Scope` of its own: the connection is acquired with its release, so no failure, interrupt or deadline leaves it open. The order is load-bearing:

1. **Before anything else,** `send` checks the message: `From`, `Sender` when `From` holds several, a recipient, every envelope address as an `addr-spec` (`AddressCodec.isAddrSpec`), and the render. `Bcc` is never rendered, so its addresses reach the wire only through `RCPT TO`, and without that check one holding CRLF would write a command of its own. A message that cannot be sent runs neither the network nor the token computation.
2. **The credential,** outside every deadline, so the token computation takes as long as it needs and its `EmailTokenException` opens no connection; the `Redactor` is built from it before the first byte. A retry schedule never retries that failure.
3. **The handshake:** greeting, `EHLO`, STARTTLS and a second `EHLO` whose extensions replace the first's, `AUTH`.
4. **The verb:** `transfer` checks `SMTPUTF8`, `8BITMIME` and `SIZE` before `MAIL`, collects every `RCPT` refusal, sends `RSET` and fails with all of them if any, and sends `DATA` only when every recipient was accepted. Once the data is accepted the message is sent; `quit` ignores its own failure.
5. **`QUIT`** ends every connection whose state is known, after a refusal as after success (RFC 5321 section 4.1.1.10); `known` excludes a transport failure, which closed the connection, and a failed TLS upgrade.

Two rules hold for every reply. A `421` is the server closing the channel, whatever the command (RFC 5321 section 4.2.1): `replied` closes the connection and fails with `ConnectionClosed` holding its text, so no loop writes past it. A reply other than the expected one is a refusal when its code is 400 or more and `Kind.Protocol` otherwise (`refusedOr`); a positive code the command does not take is never reported as a refusal.

Each exchange, the data section included, is bounded by `commandTimeout`, and a failed exchange closes the connection, as the IMAP session does.

## The failure model

Every public operation has its own sealed failure trait (`EmailFetchPartFailure`, `EmailReceiveFailure`, ...) listing exactly the leaves it can raise, and that trait is its `Abort` row. A leaf is a top-level `final case class` in `EmailException.scala` that mixes in the trait of every operation that can raise it, and carries the operation's name in `method`. Adding a failure means adding a leaf, or a `Kind` of an existing one, and mixing it into each row that can reach it; never widen a row to the base `EmailException`.

Any IMAP verb can be the one that opens its run's session, so opening's failures, `EmailTokenException` among them, are the trait `EmailConnectFailure`, which extends the receive loop's trait and every IMAP verb's. A leaf of opening mixes in `EmailConnectFailure`, and `EmailSendFailure` too when SMTP raises it, and reaches every row through it.

A field documented as the server's text holds only the server's text. A completion that lacks data the protocol requires is `EmailTransportException` of kind `Incomplete(missing)`, naming what was missing, not `Protocol` holding a sentence of the module's.

## The unsafe boundary

The safe tier is the default. The `// Unsafe:` sites are:

- `LineConnection`: kyo-net's connect, TLS upgrade and close are unsafe-tier. `open` and `startTls` bridge the returned fiber to the safe tier and await it under the deadline; a connection that completes after the caller failed or was interrupted is closed as soon as it exists.
- The codecs in `mime` and `charset` fill a local array and wrap it as a `Span` once it is fully written, so only the span escapes.
- In tests, `LineConnectionFixture` (kyo-net's listener, whose accept callback is synchronous, and its close) and the in-memory connection pairs of `LineConnectionTest` and `ImapSessionTest`.

A new site carries its `// Unsafe:` comment saying why the bridge is sound.

## Testing

Every failure leaf is produced through its real path: a scripted server, not a constructed exception. Tests run on all four platforms from `shared/src/test`; the only JVM-only tests are the differential ones against Apache mime4j, which exists only there.

### The scripted IMAP server

`ImapTestServer.serve` listens on 127.0.0.1 over TLS with a localhost certificate the config trusts through `Email.Tls.Trust.CaFile`, and runs a script per connection over a `Peer`. `peer.answer(lines*)` reads one command and sends the lines with `TAG` replaced by its tag; `peer.select`, `peer.idle` and `peer.ready` script the common exchanges; `server.received` is every line the client sent, and `server.connections` how many connections it accepted. `EmailReceiveLoopTest.loopServer` follows a script with `untilClosed`, which answers every later command `OK` until the client closes.

### The scripted SMTP server

`SmtpTestServer.serve` has the same shape. `peer.ready(extensions)` greets, answers `EHLO` with the extensions and accepts `AUTH`; `peer.reply(lines*)` reads one command and sends the lines; `peer.data` reads a data section through its `.` line; `untilClosed` accepts every command, a data section included, until the client closes. `server.received` holds the data section's lines too, so a test asserts the whole transcript.

A script never writes in an unbounded loop: over TLS, a server-side kyo-net connection keeps accepting writes after its peer closed, so such a loop outlives its test and starves the others' handshakes.

### Driving the connection octet by octet

`ImapSession.over` opens a session over a given `LineConnection`. `ImapSessionTest` builds the pair with a channel of no capacity on the server's side, so a server write returns only once the session's reader has taken it: that is how a test knows a response is half read before it moves the clock.

### Time

Deadlines, IDLE renewal, polling and reconnect delays run on kyo's `Clock`, so tests use `Clock.withTimeControl` and never the real clock. A leaf that opens a connection, over a socket or `ImapSession.over`, is written `"..." in scripted { ... }`: `LineConnectionFixture.scripted` runs the body under time control, so the module's deadlines (connect, command, IDLE renewal, poll) never fire on the real clock in a scripted exchange, and `LineConnectionFixture.listen` runs each server handler on the leaf's clock, so the fixtures' TLS upgrade deadline does not either. A leaf that moves time calls `Clock.withTimeControl` again and gets the same control. The wrapper sits inside the leaf, never in `aroundLeaf`: the runner times a leaf inside `aroundLeaf`, so a controlled clock there would put kyo-test's per-leaf timeout (120 seconds by default, `TestBase.timeout`) on virtual time, where a leaf advancing past it fails and a stall never does. On the real clock that timeout turns a hung exchange into a failure; it is never a pass condition. A timed leaf advances by exact durations, with no margin: `control.awaitPendingSleepers(n)` first, since a sleep armed by a fiber that has not run yet only sees a later move, then the duration less one nanosecond, where nothing may have happened yet, then the last nanosecond. A deadline the leaf does not exercise is `Duration.Infinity`, which arms no sleeper, so the wait under test is the one pending. Where the moment something happened matters, the leaf records `Clock.now` inside the client's own computation (an OAuth token computation runs once per connection) rather than reading the server's state after an advance, which races the woken fiber.

### Live suites

The live suites extend `EmailLiveSuite` and run against real server software: each leaf starts its own `EmailLiveServer`, a docker-mailserver image pinned by digest (Postfix delivering into Dovecot) through kyo-pod, so a message submitted over SMTP is delivered and read back over IMAP with no account or credential. No leaf reaches the internet: a CI run whose test selection includes kyo-email pulls that digest before the tests (`scripts/fixture-images.sh`), a leaf whose image is missing fails with the `podman pull` command rather than pulling it, so a digest change updates `EmailLiveServer.Image` and its entry in `scripts/fixture-images.sh` together, and every image service that would call out (the update check, ClamAV, SpamAssassin, Postfix's reverse lookups) is off. They need a container daemon, which CI provides on every Linux row; they are cancelled on Windows only, whose daemon cannot run the Linux image. A container per leaf keeps a leaf that kicks sessions or recreates mailboxes from disturbing another, and the leaves run one at a time across suites because they share one daemon. The server is configured from files staged in a scoped temporary directory, never baked into an image: the accounts, whose userdb fields take capabilities away per account (MOVE, UIDPLUS), the Postfix and Dovecot overrides, and kyo-net's localhost test certificate, which the clients trust. A `Strict` server accepts TLS 1.3 only and requires that certificate as a client certificate. XOAUTH2 needs no authorization server: Dovecot validates an HS256 JWT itself, and Postfix authenticates through Dovecot, so one token serves both protocols. No secret is committed: each server draws its accounts' password and its signing key when it starts, and `EmailLiveServer.token` mints tokens with the container's `openssl`.

A leaf causes at most one authentication failure per server. Dovecot delays each further failure from the same address longer than the last, and after four Postfix gave up on the delay with a 454, so a leaf that refused several credentials on one server would assert on that penalty rather than on the refusal.

Readiness is kyo-pod's `ContainerPredef.readinessLoop`, one bounded poll inside the container: every host-side exec leaves a `conmon` process behind for minutes on rootless podman, so a host poll across the suites piles them up. The receive loop starts after the inbox's last UID before the send, so a message cannot be missed. A leaf that waits for a message filters by subject, so a message Postfix bounced or the parser refused shows as a timeout; a leaf that fails or times out prints the tail of its server's log before the container is removed.

Waiting for a real server to deliver is real time by nature, so these suites are the module's one real-clock deviation. Each leaf is bounded by its kyo-test timeout, `EmailLiveAccount.Timeout` (3 minutes), which covers a container's start (a leaf took 7.6 to 17.6 seconds on the JVM locally, start included) so a lost message fails the run instead of hanging it. A leaf never sleeps and asserts only on what arrives. A leaf that must show a message came through IDLE sets `pollInterval` and `idleRenewal` to their 29-minute caps, past that timeout, so only a push can deliver it.

### Vendored vectors

The sets under `shared/src/test/vectors` (the CPython, mime4j, Stalwart and Thunderbird mail corpora) are fetched byte for byte and never edited. An RFC example is a literal in the test that uses it, named by its RFC section, rather than a slice of a vendored RFC text, so no test depends on a line number of a document. Each set's `MANIFEST` names its source, licence and every file with its SHA-256, and the build fails on a mismatch, a missing file or an unlisted one (`project/VendoredFiles.scala`, shared with kyo-charset). No vendored file may hold the mail of a private person: a mail corpus's manifest records how its messages were read before vendoring and why none is.

### Difference lists

Where the module deliberately differs from a reference implementation, the difference is a row of a TSV with its reason, and the test fails on a difference no row explains: `mime4j-differences.tsv`, `mime4j-model-differences.tsv` and the `stalwart-*-differences.tsv` files. The files are kept by hand: the failing test prints each unlisted row and each stale one in the file's own line format, and a difference no reason explains fails before any row is printed. Copy a row in only after reading it and agreeing with its reason; a new kind of difference needs a new `Reason` case and a `classify` rule, never a looser existing rule.

## Pre-submission checklist (kyo-email)

- [ ] A bug fix starts with a leaf that fails for the right reason, committed before the fix.
- [ ] No transport failure is caught and the connection used again; no read is ended by an interrupt.
- [ ] Server text in a new failure goes through `protocol`, `redacted` or `refused` (IMAP), or `described`, `unexpected` or `redacted` (SMTP).
- [ ] Every address that reaches an SMTP command line was checked as an `addr-spec` before the connection opened.
- [ ] A new leaf is mixed into every row that can reach it, and no row widens to `EmailException`.
- [ ] A new IMAP verb reads its session through `ImapClient` (`held` in `EmailReceive`), and its failure trait is a parent of `EmailConnectFailure`.
- [ ] Nothing adds a type parameter to a config, `Email.Account` or an effect; a caller's failure enters as `EmailTokenException`.
- [ ] Protocol text goes through `Ascii`; no mail charset goes through `java.nio.charset`.
- [ ] A new nested opaque type sits in an object of its own behind an alias, and every record holding it has a Json round-trip leaf.
- [ ] Tests move kyo's clock, never wait on the real one (the live suites excepted, see Live suites), and live in `shared/src/test` unless they compare with a JVM-only reference.
- [ ] `sbt 'kyo-emailJVM/test'` and `sbt 'kyo-emailJVM/doctest'` pass.
