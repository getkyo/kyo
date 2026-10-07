package kyo.internal.email.imap

import kyo.*
import kyo.EmailTransportException.Kind
import kyo.internal.Ascii
import kyo.internal.charset.Utf8
import kyo.internal.email.imap.ImapCodec.*
import kyo.internal.email.mime.TransferEncoding
import kyo.internal.email.net.LineConnection
import kyo.internal.email.net.Redactor
import kyo.internal.email.net.Sasl
import kyo.internal.email.net.TlsOptions
import kyo.net.NetTlsConfig

/** One authenticated IMAP connection and the protocol state over it: the capabilities, the selected mailbox and its UIDVALIDITY, the tag
  * counter, and whether the connection is still usable.
  *
  * A command runs from its write to its tagged completion under `commandTimeout`. A command whose completion never arrives leaves the
  * connection impossible to resynchronize, so any failure of a command (a deadline, a transport failure, a `BYE`) closes the connection,
  * and every later command fails with `ConnectionClosed` without writing. `exclusive` serializes whole verbs, so one verb's `SELECT` and
  * the command it prepares are never interleaved with another fiber's.
  *
  * Server text reaches a failure only through the [[Redactor]] built from the credential before the first byte was sent.
  */
final private[kyo] class ImapSession private (
    val host: String,
    val port: Int,
    connection: LineConnection,
    redactor: Redactor,
    mutex: Meter,
    commandTimeout: Duration,
    val maxResponseLength: Long,
    tags: AtomicLong,
    state: AtomicRef[ImapSession.State]
):
    import ImapSession.*

    def capabilities(using Frame): Set[String] < Sync = state.get.map(_.capabilities)

    def selected(using Frame): Maybe[Selected] < Sync = state.get.map(_.selected)

    def closed(using Frame): Boolean < Sync = state.get.map(_.closed)

    def select(selection: Maybe[Selected])(using Frame): Unit < Sync = state.updateAndGet(_.copy(selected = selection)).unit

    /** Runs a whole verb holding the session. */
    def exclusive[A, S](method: String)(v: => A < S)(using Frame): A < (S & Async & Abort[EmailTransportException]) =
        Abort.run[Closed](mutex.run(v)).map {
            case Result.Success(value) => value
            case Result.Failure(_)     => bug("the session's mutex is never closed; a shut session fails in command, on its closed flag")
            case Result.Panic(ex)      => Abort.panic(ex)
        }

    /** Sends one command and reads through its completion. `respond` answers each continuation the server sends after the command is
      * written, numbered from 0; without it a continuation there is `Protocol`. A `BYE` ends the command as a closed connection, except
      * under `logout`, where RFC 9051 section 3.4 has it precede the tagged `OK` the client must read. The untagged data may take `budget`
      * octets, past which the reply is `Protocol`.
      */
    def command(
        method: String,
        parts: Chunk[Part],
        respond: Maybe[Int => String] = Absent,
        logout: Boolean = false,
        budget: Long = maxResponseLength
    )(using Frame): Reply < (Async & Abort[EmailTransportException]) =
        state.get.map { current =>
            // kyo-net closes a connection's channels at once, so a write would fail as well; the flag keeps a dead session's outcome
            // from depending on that, where a buffered write would instead wait out `commandTimeout`.
            if current.closed then Abort.fail(transport(method, Kind.ConnectionClosed(Absent)))
            else
                tags.incrementAndGet.map { n =>
                    bounded(method)(exchange(method, s"A$n", parts, respond, logout, budget)).map(reply =>
                        forgetRenumbered(reply).andThen(reply)
                    )
                }
        }

    // A UIDVALIDITY other than the selected mailbox's means the server renumbered it, so the next verb that needs it selects it again.
    private def forgetRenumbered(reply: Reply)(using Frame): Unit < Sync =
        announcedValidity(reply) match
            case Present(validity) => state.updateAndGet(s => s.copy(selected = s.selected.filter(_.validity == validity))).unit
            case Absent            => ()

    /** The command's completion when it is `OK`; `NO` or `BAD` is `EmailImapCommandException` naming `name`. */
    def expectOk(method: String, name: String, reply: Reply)(using Frame): Reply < Abort[EmailImapCommandException] =
        if reply.completion.condition == Condition.Ok then reply else Abort.fail(refused(method, name, reply.completion))

    def refused(method: String, name: String, completion: Response.Tagged)(using Frame): EmailImapCommandException =
        EmailImapCommandException(
            method,
            name,
            if completion.condition == Condition.Bad then EmailImapCommandException.Status.Bad else EmailImapCommandException.Status.No,
            completion.code.map(responseCode),
            redactor.redact(completion.text)
        )

    def redact(text: String): String = redactor.redact(text)

    def listMailboxes(using Frame): Chunk[EmailReceive.Mailbox] < (Async & Abort[EmailListMailboxesFailure]) =
        val method = "listMailboxes"
        exclusive(method) {
            capabilities.map { offered =>
                val special = if offered.contains("SPECIAL-USE") then " RETURN (SPECIAL-USE)" else ""
                command(method, Chunk(Part.Text("LIST \"\" \"*\"" + special))).map(expectOk(method, "LIST", _)).map { reply =>
                    Kyo.foreach(reply.untagged.collect { case Line(item: Data.ListItem, segments) => (item, segments) }) {
                        (item, segments) =>
                            ImapModel.mailbox(item, utf8(offered)) match
                                case Present(mailbox) => mailbox
                                case Absent           => Abort.fail(protocol(method, segments))
                    }
                }
            }
        }
    end listMailboxes

    def status(mailbox: Email.MailboxName)(using Frame): EmailReceive.MailboxStatus < (Async & Abort[EmailStatusFailure]) =
        val method = "status"
        exclusive(method) {
            capabilities.map { offered =>
                val parts = Chunk(Part.Text("STATUS ")).concat(mailboxName(offered, mailbox))
                    .append(Part.Text(" (MESSAGES UNSEEN UIDNEXT UIDVALIDITY)"))
                command(method, parts).map(existing(method, "STATUS", mailbox, _)).map { reply =>
                    Maybe.fromOption(reply.untagged.collectFirst { case Line(data: Data.MailboxStatus, segments) =>
                        (data, segments)
                    }) match
                        case Present((data, segments)) =>
                            ImapModel.status(mailbox, data) match
                                case Present(status) => status
                                case Absent          => Abort.fail(protocol(method, segments))
                        case Absent => Abort.fail(missing(method, "STATUS"))
                }
            }
        }
    end status

    def search(mailbox: Email.MailboxName, query: EmailReceive.Search)(using
        Frame
    ): Chunk[Email.Uid] < (Async & Abort[EmailSearchFailure]) =
        val method = "search"
        exclusive(method) {
            capabilities.map { offered =>
                selectFor(method, mailbox).map { selection =>
                    val (keys, utf8) = ImapModel.search(query, nonSynchronizing(offered, _))
                    val parts        = Chunk(Part.Text(if utf8 then "UID SEARCH CHARSET UTF-8 " else "UID SEARCH ")).concat(keys)
                    command(method, parts).map { reply =>
                        reply.completion match
                            case Response.Tagged(_, Condition.No | Condition.Bad, Present(code), _) if utf8 && code.atom == "BADCHARSET" =>
                                Abort.fail(EmailCapabilityMissingException(
                                    method,
                                    host,
                                    EmailCapabilityMissingException.Capability.ImapUtf8Search
                                ))
                            case _ => expectOk(method, "SEARCH", reply)
                    }.map { reply =>
                        val sets = reply.untagged.collect { case Line(Data.ESearch(ranges), segments) => (ranges, segments) }
                        // An ESEARCH set counts as the plain SEARCH listing of its UIDs would, so it answers no more than SEARCH could
                        // within maxResponseLength; each is checked before anything is expanded.
                        Kyo.foldLeft(sets)(reply.untagged.foldLeft(0L)((sum, line) => sum + octets(line.segments))) {
                            case (taken, (ranges, segments)) =>
                                if ranges.exists((low, high) => !isUid(low) || !isUid(high)) then Abort.fail(protocol(method, segments))
                                else
                                    val total = ranges.foldLeft(taken)((sum, range) => sum + listing(range._1, range._2))
                                    if total > maxResponseLength then Abort.fail(protocol(method, segments)) else total
                        }.andThen {
                            val listed = reply.untagged.collect { case Line(Data.Search(numbers), segments) => (numbers, segments) }
                            Kyo.foreachDiscard(listed) { (numbers, segments) =>
                                if numbers.forall(isUid) then Kyo.unit else Abort.fail(protocol(method, segments))
                            }.andThen(merged(selection, listed.map(_._1), sets.map(_._1)))
                        }
                    }
                }
            }
        }
    end search

    def fetch(uids: Chunk[Email.Uid])(using Frame): Chunk[EmailReceive.Summary] < (Async & Abort[EmailFetchFailure]) =
        val method = "fetch"
        if uids.isEmpty then Chunk.empty
        else
            exclusive(method) {
                perMailbox(method, uids) { (selection, group) =>
                    val items  = "(UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[HEADER] BODYSTRUCTURE)"
                    val wanted = group.map(_.value).toSet
                    Kyo.foreach(uidSets(group.map(_.value), room(s"UID FETCH  $items".length))) { set =>
                        command(method, Chunk(Part.Text(s"UID FETCH $set $items")))
                            .map(expectOk(method, "FETCH", _))
                            .map { reply =>
                                Kyo.foreach(data(reply, wanted.contains, "BODY[HEADER]")) { (_, items, segments) =>
                                    ImapModel.summaryFields(selection.mailbox, selection.validity, items) match
                                        case Present(fields) =>
                                            header(method, fields.header, segments).map { header =>
                                                EmailReceive.Summary(
                                                    fields.uid,
                                                    fields.flags,
                                                    fields.internalDate,
                                                    fields.size,
                                                    header,
                                                    fields.structure
                                                )
                                            }
                                        case Absent => Abort.fail(protocol(method, segments))
                                }
                            }
                    }.map(_.flatten.sortBy(_.uid.value))
                }
            }
        end if
    end fetch

    def fetchMessage(uid: Email.Uid)(using Frame): Email.Message < (Async & Abort[EmailFetchMessageFailure]) =
        val method = "fetchMessage"
        exclusive(method) {
            perMailbox(method, Chunk(uid)) { (_, _) =>
                section(method, uid, "").map { octets =>
                    Abort.run[EmailParseFailure](Email.Message.parse(octets)).map {
                        case Result.Success(message)                     => Chunk(message)
                        case Result.Failure(failure: EmailMimeException) => Abort.fail(failure)
                        case Result.Panic(ex)                            => Abort.panic(ex)
                    }
                }
            }.map(_.head)
        }
    end fetchMessage

    def fetchPart(uid: Email.Uid, part: Chunk[Int])(using Frame): Span[Byte] < (Async & Abort[EmailFetchPartFailure]) =
        val method = "fetchPart"
        exclusive(method) {
            perMailbox(method, Chunk(uid)) { (_, _) =>
                if part.isEmpty then section(method, uid, "").map(Chunk(_))
                else
                    command(method, Chunk(Part.Text(s"UID FETCH ${uid.value} (UID BODYSTRUCTURE)"))).map(expectOk(method, "FETCH", _)).map {
                        reply =>
                            data(reply, _ == uid.value, "BODYSTRUCTURE").headMaybe match
                                case Absent                        => Abort.fail(EmailMessageNotFoundException(method, uid))
                                case Present((_, items, segments)) =>
                                    val structure =
                                        Maybe.fromOption(items.find(_._1 == "BODYSTRUCTURE")).flatMap(item => ImapModel.part(item._2))
                                    structure.fold(Abort.fail(protocol(method, segments))) { root =>
                                        find(root, part).fold(Abort.fail(EmailPartNotFoundException(method, uid, part))) { found =>
                                            section(method, uid, part.mkString(".")).map(decoded(part, found, _)).map(Chunk(_))
                                        }
                                    }
                    }
            }.map(_.head)
        }
    end fetchPart

    def store(method: String, uids: Chunk[Email.Uid], flags: Set[Email.Flag], add: Boolean)(using
        Frame
    ): Unit < (Async & Abort[EmailAddFlagsFailure & EmailRemoveFlagsFailure]) =
        if uids.isEmpty || flags.isEmpty then ()
        else
            val sign = if add then "+" else "-"
            val list = flags.toSeq.map(_.wire).sorted.mkString(" ")
            exclusive(method) {
                perMailbox(method, uids) { (_, group) =>
                    val flagged = s"${sign}FLAGS.SILENT ($list)"
                    Kyo.foreachDiscard(uidSets(group.map(_.value), room(s"UID STORE  $flagged".length))) { set =>
                        command(method, Chunk(Part.Text(s"UID STORE $set $flagged"))).map(expectOk(method, "STORE", _))
                    }.andThen(Chunk.empty[Unit])
                }.unit
            }
        end if
    end store

    def move(uids: Chunk[Email.Uid], to: Email.MailboxName)(using Frame): Unit < (Async & Abort[EmailMoveFailure]) =
        val method = "move"
        if uids.isEmpty then ()
        else
            exclusive(method) {
                capabilities.map { offered =>
                    val native = offered.contains("MOVE") || offered.contains("IMAP4REV2")
                    if !native && !offered.contains("UIDPLUS") then
                        Abort.fail(EmailCapabilityMissingException(method, host, EmailCapabilityMissingException.Capability.ImapMove))
                    else
                        perMailbox(method, uids) { (_, group) =>
                            val destination = mailboxName(offered, to)
                            val fixed = ("UID COPY  ".length + textLength(destination)).max("UID STORE  +FLAGS.SILENT (\\Deleted)".length)
                            Kyo.foreachDiscard(uidSets(group.map(_.value), room(fixed))) { set =>
                                if native then
                                    command(
                                        method,
                                        Chunk(Part.Text(s"UID MOVE $set ")).concat(destination)
                                    ).map(destinationExisting(method, "MOVE", to, _))
                                else
                                    // Expunging exactly the moved UIDs is what keeps another message the user flagged \Deleted.
                                    command(method, Chunk(Part.Text(s"UID COPY $set ")).concat(destination))
                                        .map(destinationExisting(method, "COPY", to, _))
                                        .andThen(command(method, Chunk(Part.Text(s"UID STORE $set +FLAGS.SILENT (\\Deleted)"))))
                                        .map(expectOk(method, "STORE", _))
                                        .andThen(command(method, Chunk(Part.Text(s"UID EXPUNGE $set"))))
                                        .map(expectOk(method, "EXPUNGE", _))
                                end if
                            }.andThen(Chunk.empty[Unit])
                        }.unit
                    end if
                }
            }
        end if
    end move

    def custom(line: EmailReceive.Command)(using Frame): EmailReceive.Reply < (Async & Abort[EmailImapCustomFailure]) =
        val method = "custom"
        exclusive(method) {
            command(method, Chunk(Part.Text(line.value))).map { reply =>
                select(Absent).andThen {
                    if reply.completion.condition == Condition.Ok then
                        EmailReceive.Reply(
                            reply.untagged.map(untagged => redactor.redact(rendered(untagged.segments))),
                            redactor.redact(reply.completion.text)
                        )
                    else Abort.fail(refused(method, line.value.takeWhile(_ != ' '), reply.completion))
                }
            }
        }
    end custom

    // The selected mailbox when it is `mailbox`, else a SELECT of it. A failed SELECT leaves nothing selected (RFC 9051 section 6.3.2).
    private def selectFor(method: String, mailbox: Email.MailboxName)(using
        Frame
    ): Selected < (Async & Abort[EmailTransportException | EmailImapCommandException | EmailMailboxNotFoundException]) =
        selected.map {
            case Present(current) if current.mailbox == mailbox => current
            case _                                              => selectNow(method, mailbox).map(_._1)
        }

    /** A SELECT of `mailbox`, answering the selection and the UIDNEXT the server reported with it. */
    def selectNow(method: String, mailbox: Email.MailboxName)(using
        Frame
    ): (Selected, Maybe[Long]) < (Async & Abort[EmailTransportException | EmailImapCommandException | EmailMailboxNotFoundException]) =
        capabilities.map { offered =>
            select(Absent)
                .andThen(command(method, Chunk(Part.Text("SELECT ")).concat(mailboxName(offered, mailbox))))
                .map(existing(method, "SELECT", mailbox, _))
                .map { reply =>
                    announcedValidity(reply) match
                        case Present(validity) =>
                            val selection = Selected(mailbox, validity)
                            select(Present(selection)).andThen((selection, announced(reply, "UIDNEXT")))
                        case Absent => Abort.fail(missing(method, "UIDVALIDITY"))
                }
        }

    /** How many `EXISTS` the server has announced on this connection; a change between two reads means a message may have arrived. */
    def announcements(using Frame): Long < Sync = state.get.map(_.announced)

    /** The selected mailbox's message count, as the server last reported it. */
    def messages(using Frame): Long < Sync = state.get.map(_.messages)

    private def counted(data: Data)(using Frame): Unit < Sync =
        data match
            case Data.Exists(count) => state.updateAndGet(s => s.copy(announced = s.announced + 1, messages = count)).unit
            case Data.Expunge(_)    => state.updateAndGet(s => s.copy(messages = Math.max(0L, s.messages - 1))).unit
            case _                  => ()

    /** Whether the server can push new messages to an idle connection (RFC 2177, part of IMAP4rev2). */
    def idles(using Frame): Boolean < Sync = capabilities.map(offered => offered.contains("IDLE") || offered.contains("IMAP4REV2"))

    /** `mailbox` selected under `validity`, selecting it again when a verb selected another; another UIDVALIDITY is a failure. */
    def reselect(method: String, mailbox: Email.MailboxName, validity: Email.UidValidity)(using
        Frame
    ): Unit < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method)(selectFor(method, mailbox)).map { selection =>
            if selection.validity == validity then ()
            else Abort.fail(EmailUidValidityChangedException(method, mailbox, validity, selection.validity))
        }

    /** The UIDs of the selected `mailbox` from `from` on, ascending, each with what the listing said of its size, without any message's
      * content. A UID any response of the reply names is listed, with or without a size: a message the server sent only a flag update
      * for is still a message to deliver.
      */
    def pending(method: String, mailbox: Email.MailboxName, validity: Email.UidValidity, from: Long)(using
        Frame
    ): Chunk[(Email.Uid, Listed)] < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method) {
            command(method, Chunk(Part.Text(s"UID FETCH $from:* (UID RFC822.SIZE)")))
                .map(expectOk(method, "FETCH", _))
                .map { reply =>
                    // RFC 9051 section 6.4.9: `n:*` also answers the highest UID when it is below `n`.
                    Kyo.foreach(sizes(reply, _ >= from)) { (n, listed, segments) =>
                        Email.Uid.read(mailbox, validity, n) match
                            case Present(uid) => (uid, listed)
                            case Absent       => Abort.fail(protocol(method, segments))
                    }.map(_.sortBy(_._1.value))
                }
        }

    /** What the server says of `uid`'s size when asked for it alone; absent when it sends no data for `uid`, as for an expunged message. */
    def sizeOf(method: String, uid: Long)(using Frame): Maybe[Listed] < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method) {
            command(method, Chunk(Part.Text(s"UID FETCH $uid (UID RFC822.SIZE)")))
                .map(expectOk(method, "FETCH", _))
                .map(reply => sizes(reply, _ == uid).headMaybe.map(_._2))
        }

    /** The UID of the message at `sequence` in the selected mailbox. */
    def uidAt(method: String, sequence: Long)(using Frame): Long < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method) {
            command(method, Chunk(Part.Text(s"FETCH $sequence (UID)"))).map(expectOk(method, "FETCH", _)).map { reply =>
                numbered(reply, sequence, sequence).headMaybe match
                    case Present((_, uid, _, _)) => uid
                    case Absent                  => Abort.fail(missing(method, s"the UID of message $sequence"))
            }
        }

    /** The messages at sequence numbers `first` to `last` of the selected `mailbox`, each with its sequence number, its UID and what the
      * server said of its size.
      */
    def window(method: String, mailbox: Email.MailboxName, validity: Email.UidValidity, first: Long, last: Long)(using
        Frame
    ): Chunk[(Long, Email.Uid, Listed)] < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method) {
            command(method, Chunk(Part.Text(s"FETCH $first:$last (UID RFC822.SIZE)"))).map(expectOk(method, "FETCH", _)).map { reply =>
                Kyo.foreach(numbered(reply, first, last)) { (sequence, n, listed, segments) =>
                    Email.Uid.read(mailbox, validity, n) match
                        case Present(uid) => (sequence, uid, listed)
                        case Absent       => Abort.fail(protocol(method, segments))
                }.map(_.sortBy(_._1))
            }
        }

    // Each message from sequence number `first` to `last` that a FETCH response of the reply names with a UID, with that UID, its size
    // as the first response that carries RFC822.SIZE gives it, and the segments of a response that names it.
    private def numbered(reply: Reply, first: Long, last: Long): Chunk[(Long, Long, Listed, Chunk[Segment])] =
        val responses = reply.untagged.collect {
            case Line(Data.Fetch(sequence, items), segments) if sequence >= first && sequence <= last => (sequence, items, segments)
        }
        responses.map(_._1).distinct.flatMap { sequence =>
            val own = responses.filter(_._1 == sequence)
            val uid = own.flatMap(_._2.collect { case ("UID", Value.Number(n)) => n }).headMaybe
            uid.toChunk.map(n => (sequence, n, listedOf(own.map(_._2)), own.head._3))
        }
    end numbered

    // What one message's responses say of its size: the first RFC822.SIZE among them.
    private def listedOf(responses: Chunk[Chunk[(String, Value)]]): Listed =
        responses.flatMap(_.collect { case ("RFC822.SIZE", value) => value }).headMaybe match
            case Present(Value.Number(octets)) => Listed.Octets(octets)
            case Present(_)                    => Listed.Unreadable
            case Absent                        => Listed.Unsized

    // Each UID `wanted` accepts that a FETCH response of the reply names, in the order they first appear, with its size as the first
    // response that carries RFC822.SIZE gives it, and the segments of a response that names it.
    private def sizes(reply: Reply, wanted: Long => Boolean): Chunk[(Long, Listed, Chunk[Segment])] =
        val responses = fetched(reply).filter((n, _, _) => wanted(n))
        responses.map(_._1).distinct.map { n =>
            val own = responses.filter(_._1 == n)
            (n, listedOf(own.map(_._2)), own.head._3)
        }
    end sizes

    /** Message `uid` of the selected `mailbox`, fetched without setting `\Seen`; absent when the server sends no data for it, as for a
      * message expunged since it was listed. The caller asks only for a message whose listed size is within maxResponseLength, so the
      * reply may take that and the lines around the body besides: a message at the limit is read, never refused on every attempt.
      */
    def fetchOne(method: String, mailbox: Email.MailboxName, validity: Email.UidValidity, uid: Long)(using
        Frame
    ): Maybe[EmailReceive.InboxEvent] < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method) {
            command(
                method,
                Chunk(Part.Text(s"UID FETCH $uid (UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[])")),
                budget = maxResponseLength + FramingRoom
            )
                .map(expectOk(method, "FETCH", _))
                .map { reply =>
                    data(reply, _ == uid, "BODY[]").headMaybe match
                        case Absent                        => Maybe.empty[EmailReceive.InboxEvent]
                        case Present((_, items, segments)) =>
                            ImapModel.received(mailbox, validity, items) match
                                case Present(event) => Present(event)
                                case Absent         => Abort.fail(protocol(method, segments))
                }
        }

    /** IDLE until the server announces a message with `EXISTS`, or `renewal` passes on kyo's clock, then `DONE`; at once when
      * `announcements` has moved past `mark` by the continuation. A close or `BYE` before `DONE` is `EmailIdleDroppedException`; after it,
      * the transport failure any command would have, `Timeout` when the completion did not follow within commandTimeout. The connection is
      * closed on every failure.
      */
    def idle(method: String, mailbox: Email.MailboxName, renewal: Duration, mark: Long)(using
        Frame
    ): Unit < (Async & Abort[EmailReceiveFailure]) =
        exclusive(method) {
            state.get.map { current =>
                if current.closed then Abort.fail(transport(method, Kind.ConnectionClosed(Absent)))
                else
                    tags.incrementAndGet.map { n =>
                        val tag = s"A$n"
                        // Issued like any command: its `+` or a refusal arrives within commandTimeout.
                        bounded(method)(
                            connection.write(method, Utf8.encode(s"$tag IDLE\r\n"))
                                .andThen(await(method, tag, Chunk.empty, Absent, stopAtContinuation = true))
                        ).map { (_, completion) =>
                            completion match
                                case Present(reply) => Abort.fail(refused(method, "IDLE", reply.completion))
                                case Absent         => idling(method, mailbox, tag, renewal, mark)
                        }
                    }
            }
        }

    // After IDLE's continuation. The reader reads through the completion and is never interrupted: an interrupt drops whatever it has
    // taken from the connection and not yet kept, and the stream goes on with a hole. `DONE` is written once, by the watch fiber, when an
    // EXISTS is read, `renewal` passes, or `announcements` had already moved past `mark`. A completion that does not follow `DONE` within
    // commandTimeout makes the watch close the connection, which is what ends the read.
    private def idling(method: String, mailbox: Email.MailboxName, tag: String, renewal: Duration, mark: Long)(using
        Frame
    ): Unit < (Async & Abort[EmailReceiveFailure]) =
        for
            sent    <- AtomicBoolean.init(false)
            expired <- AtomicBoolean.init(false)
            exists  <- Latch.init(1)
            now     <- announcements
            _       <- if now != mark then exists.release else Kyo.unit
            watch   <- Fiber.initUnscoped {
                Async.race(Async.sleep(renewal), exists.await).andThen(sent.compareAndSet(false, true)).map { first =>
                    if !first then ()
                    else
                        Abort.run[EmailTransportException](connection.write(method, Utf8.encode("DONE\r\n")))
                            .andThen(Async.sleep(commandTimeout))
                            .andThen(expired.set(true))
                            .andThen(shut)
                }
            }
            read   <- Sync.ensure(watch.interrupt.unit)(Abort.run[EmailTransportException](untilCompletion(method, tag, exists)))
            done   <- sent.getAndSet(true)
            late   <- expired.get
            result <- read match
                case Result.Success(completion) =>
                    if completion.condition == Condition.Ok then Kyo.unit else Abort.fail(refused(method, "IDLE", completion))
                case Result.Failure(ex) =>
                    shut.andThen {
                        if late then Abort.fail(transport(method, Kind.Timeout, Present(commandTimeout)))
                        else if done then Abort.fail(redacted(ex))
                        else
                            val parting = redacted(ex).kind match
                                case Kind.ConnectionClosed(text) => text
                                case _                           => Absent
                            Abort.fail(EmailIdleDroppedException(method, host, mailbox, parting))
                    }
                case Result.Panic(ex) => shut.andThen(Abort.panic(ex))
        yield result
        end for
    end idling

    // The responses while idling through the completion of `tag`, releasing `exists` at each EXISTS; a BYE ends the IDLE as a closed
    // connection does.
    private def untilCompletion(method: String, tag: String, exists: Latch)(using
        Frame
    ): Response.Tagged < (Async & Abort[EmailTransportException]) =
        Loop.foreach {
            next(method, maxResponseLength).map { (response, segments, _) =>
                response match
                    case completion @ Response.Tagged(received, _, _, _) =>
                        if received == tag then Loop.done(completion) else Abort.fail(protocol(method, segments))
                    case Response.Untagged(data @ Data.Exists(_)) => counted(data).andThen(exists.release).andThen(Loop.continue)
                    case Response.Untagged(Data.Status(Condition.Bye, _, text)) =>
                        Abort.fail(transport(method, Kind.ConnectionClosed(Present(text))))
                    case Response.Untagged(data) => counted(data).andThen(Loop.continue)
                    case _                       => Loop.continue
            }
        }

    // One exchange under commandTimeout, closing the session on any transport failure, as `command` does.
    private def bounded[A](method: String)(v: => A < (Async & Abort[EmailTransportException]))(using
        Frame
    ): A < (Async & Abort[EmailTransportException]) =
        Abort.run[Timeout](Async.timeout(commandTimeout)(Abort.run[EmailTransportException](v))).map {
            case Result.Success(Result.Success(value)) => value
            case Result.Success(Result.Failure(ex))    => shut.andThen(Abort.fail(redacted(ex)))
            case Result.Success(Result.Panic(ex))      => shut.andThen(Abort.panic(ex))
            case Result.Failure(_)                     => shut.andThen(Abort.fail(transport(method, Kind.Timeout, Present(commandTimeout))))
            case Result.Panic(ex)                      => shut.andThen(Abort.panic(ex))
        }

    // Runs `f` on each mailbox's UIDs, in the order the mailboxes first appear, after selecting it and checking the UIDs' validity.
    private def perMailbox[A, S](method: String, uids: Chunk[Email.Uid])(f: (Selected, Chunk[Email.Uid]) => Chunk[A] < S)(using
        Frame
    ): Chunk[A] < (
        S & Async &
            Abort[EmailTransportException | EmailImapCommandException | EmailMailboxNotFoundException |
                EmailUidValidityChangedException]
    ) =
        Kyo.foreach(uids.map(_.mailbox).distinct) { mailbox =>
            val group = uids.filter(_.mailbox == mailbox)
            selectFor(method, mailbox).map { selection =>
                Maybe.fromOption(group.find(_.validity != selection.validity)) match
                    case Present(stale) =>
                        Abort.fail(EmailUidValidityChangedException(method, mailbox, stale.validity, selection.validity))
                    case Absent => f(selection, group)
            }
        }.map(_.flatten)

    // A NO [NONEXISTENT] about `mailbox` is EmailMailboxNotFoundException; any other NO or BAD EmailImapCommandException.
    private def existing(method: String, name: String, mailbox: Email.MailboxName, reply: Reply)(using
        Frame
    ): Reply < Abort[EmailMailboxNotFoundException | EmailImapCommandException] =
        reply.completion match
            case Response.Tagged(_, Condition.No, Present(code), _) if code.atom == "NONEXISTENT" =>
                Abort.fail(EmailMailboxNotFoundException(method, mailbox))
            case _ => expectOk(method, name, reply)

    // A COPY or MOVE to a missing destination is answered NO [TRYCREATE] (RFC 9051 section 7.1: "An APPEND, COPY, or MOVE attempt is
    // failing because the target mailbox does not exist"), or by some servers NO [NONEXISTENT] (RFC 5530).
    private def destinationExisting(method: String, name: String, mailbox: Email.MailboxName, reply: Reply)(using
        Frame
    ): Reply < Abort[EmailMailboxNotFoundException | EmailImapCommandException] =
        reply.completion match
            case Response.Tagged(_, Condition.No, Present(code), _) if code.atom == "TRYCREATE" =>
                Abort.fail(EmailMailboxNotFoundException(method, mailbox))
            case _ => existing(method, name, mailbox, reply)

    // The octets of `BODY[<section>]` of `uid`, fetched without setting \Seen; no data for the UID is EmailMessageNotFoundException.
    private def section(method: String, uid: Email.Uid, section: String)(using
        Frame
    ): Span[Byte] < (Async & Abort[EmailTransportException | EmailImapCommandException | EmailMessageNotFoundException]) =
        command(method, Chunk(Part.Text(s"UID FETCH ${uid.value} (UID BODY.PEEK[$section])"))).map(expectOk(method, "FETCH", _)).map {
            reply =>
                data(reply, _ == uid.value, s"BODY[$section]").headMaybe match
                    case Absent                        => Abort.fail(EmailMessageNotFoundException(method, uid))
                    case Present((_, items, segments)) =>
                        Maybe.fromOption(items.find(_._1 == s"BODY[$section]")).map(_._2) match
                            case Present(Value.Str(octets)) => Span.from(octets.toArray)
                            case Present(Value.Nil)         => Span.empty[Byte]
                            case _                          => Abort.fail(protocol(method, segments))
        }

    // The FETCH data of each UID `wanted` accepts whose responses carry `item`, in the order the UIDs first appear, with the segments of
    // the response that carried it. RFC 9051 section 7.5.2 has a server send a FETCH of its own when a flag changes, holding the UID and
    // FLAGS, so one UID can have several responses: each item comes from the first that carries it, and FLAGS, the message's current
    // flags, from the last. A UID whose responses never carry `item` has no data.
    private def data(reply: Reply, wanted: Long => Boolean, item: String): Chunk[(Long, Chunk[(String, Value)], Chunk[Segment])] =
        val responses = fetched(reply).filter((n, _, _) => wanted(n))
        val byUid     = responses.groupBy(_._1)
        responses.map(_._1).distinct.flatMap { n =>
            val own = byUid(n)
            Maybe.fromOption(own.find(_._2.exists(_._1 == item))).toChunk.map { (_, _, segments) =>
                val items = own.foldLeft(Chunk.empty[(String, Value)]) { (known, response) =>
                    val fresh = response._2
                    val flags = fresh.exists(_._1 == "FLAGS")
                    val kept  = known.filter(k => k._1 != "FLAGS" || !flags)
                    val added = fresh.filter(k => k._1 == "FLAGS" || !known.exists(_._1 == k._1))
                    kept.concat(added)
                }
                (n, items, segments)
            }
        }
    end data

    // The FETCH responses of a reply that carry a UID, with it.
    private def fetched(reply: Reply): Chunk[(Long, Chunk[(String, Value)], Chunk[Segment])] =
        reply.untagged.flatMap {
            case Line(Data.Fetch(_, items), segments) =>
                Maybe.fromOption(items.collectFirst { case ("UID", Value.Number(n)) => (n, items, segments) }).toChunk
            case _ => Chunk.empty
        }

    private def header(method: String, octets: Span[Byte], segments: Chunk[Segment])(using
        Frame
    ): Email.Message < Abort[EmailTransportException] =
        Abort.run[EmailParseFailure](Email.Message.parse(octets)).map {
            case Result.Success(message) => message
            case Result.Failure(_)       => Abort.fail(protocol(method, segments))
            case Result.Panic(ex)        => Abort.panic(ex)
        }

    private def find(part: EmailReceive.Part, path: Chunk[Int]): Maybe[EmailReceive.Part] =
        if part.path == path then Present(part)
        else part.children.foldLeft(Maybe.empty[EmailReceive.Part])((found, child) => found.orElse(find(child, path)))

    private def decoded(path: Chunk[Int], part: EmailReceive.Part, octets: Span[Byte])(using
        Frame
    ): Span[Byte] < Abort[EmailTransferDecodeException] =
        TransferEncoding.resolve(part.encoding) match
            case Absent =>
                Abort.fail(EmailTransferDecodeException(
                    path,
                    EmailTransferDecodeException.Problem.UnsupportedTransferEncoding(part.encoding)
                ))
            case Present(kind) =>
                TransferEncoding.decode(kind, octets, 0, octets.size) match
                    case Result.Success(result) =>
                        if result.truncated then
                            Abort.fail(EmailTransferDecodeException(path, EmailTransferDecodeException.Problem.TruncatedBase64))
                        else result.content
                    // A refused piece means kyo.Base64 no longer takes what TransferEncoding builds, a defect no server can cause.
                    case Result.Failure(refused) => Abort.panic(refused)
                    case Result.Panic(cause)     => Abort.panic(cause)

    // An untagged response as it crossed the wire: each literal after its marker's line end.
    private def rendered(segments: Chunk[Segment]): String =
        segments.map {
            case Segment.Text(line)      => Utf8.decode(line)
            case Segment.Literal(octets) => "\r\n" + Utf8.decode(octets)
        }.mkString

    private def missing(method: String, what: String)(using Frame): EmailTransportException =
        transport(method, Kind.Incomplete(what))

    // The characters left for a UID set in a command whose other text takes `fixed`: RFC 7162 section 4's line length, less the tag,
    // its space and the line end.
    private def room(fixed: Int): Int = EmailReceive.Command.MaxLength - TagRoom - fixed

    // What a command's parts take on its line: RFC 7162 section 4 counts quoted strings but not a literal's octets, only its marker.
    private def textLength(parts: Chunk[Part]): Int =
        parts.foldLeft(0) {
            case (sum, Part.Text(text))         => sum + Utf8.encode(text).size
            case (sum, Part.Literal(octets, _)) => sum + s"{${octets.size}+}".length + 2
        }

    private def mailboxName(offered: Set[String], mailbox: Email.MailboxName): Chunk[Part] =
        ImapCodec.mailbox(mailbox, utf8(offered), nonSynchronizing(offered, mailbox.value))

    /** Logs out when no verb holds the session, then closes the connection; a second call only closes again, which costs nothing. */
    def close(using Frame): Unit < Async =
        state.get.map { current =>
            if current.closed then ()
            else Abort.run[EmailTransportException | Closed](mutex.tryRun(command("close", Chunk(Part.Text("LOGOUT")), logout = true))).unit
        }.andThen(shut)

    /** Closes the connection without logging out, for a session whose state is unknown (interrupted mid-command, or mid-IDLE). */
    def abandon(using Frame): Unit < Sync = shut

    private def shut(using Frame): Unit < Sync =
        state.updateAndGet(_.copy(closed = true)).unit.andThen(connection.close)

    // The command as octets to write, cut after each synchronizing literal's marker, where the server's `+` must be awaited.
    private def rounds(tag: String, parts: Chunk[Part]): Chunk[Span[Byte]] =
        val out                       = ChunkBuilder.init[Span[Byte]]
        var current                   = ChunkBuilder.init[Byte]
        def text(value: String): Unit = Utf8.encode(value).foreach(b => discard(current.addOne(b)))
        text(tag)
        text(" ")
        parts.foreach {
            case Part.Text(value)                    => text(value)
            case Part.Literal(octets, synchronizing) =>
                text(s"{${octets.size}${if synchronizing then "" else "+"}}\r\n")
                if synchronizing then
                    discard(out.addOne(Span.from(current.result().toArray)))
                    current = ChunkBuilder.init[Byte]
                octets.foreach(b => discard(current.addOne(b)))
        }
        text("\r\n")
        discard(out.addOne(Span.from(current.result().toArray)))
        out.result()
    end rounds

    private def exchange(method: String, tag: String, parts: Chunk[Part], respond: Maybe[Int => String], logout: Boolean, budget: Long)(
        using Frame
    ): Reply < (Async & Abort[EmailTransportException]) =
        val pieces = rounds(tag, parts)
        // Every piece but the last ends in a synchronizing literal's marker. A server refusing the command answers the marker with its
        // completion instead of `+`, and the rest is never sent.
        Loop(0, Chunk.empty[Line]) { (index, seen) =>
            connection.write(method, pieces(index))
                .andThen(await(method, tag, seen, respond, stopAtContinuation = index < pieces.size - 1, logout, budget))
                .map { (lines, completion) =>
                    completion match
                        case Present(reply) => Loop.done(reply)
                        case Absent         => Loop.continue(index + 1, lines)
                }
        }
    end exchange

    // Reads until the tagged completion or, with `stopAtContinuation`, a continuation (`Absent`), keeping the untagged data read. The data
    // `seen` in earlier rounds of the command counts against `budget` with what this round reads.
    private def await(
        method: String,
        tag: String,
        seen: Chunk[Line],
        respond: Maybe[Int => String],
        stopAtContinuation: Boolean,
        logout: Boolean = false,
        budget: Long = maxResponseLength
    )(using Frame): (Chunk[Line], Maybe[Reply]) < (Async & Abort[EmailTransportException]) =
        Loop(seen, 0, seen.foldLeft(0L)((sum, line) => sum + octets(line.segments))) { (lines, answered, taken) =>
            next(method, budget - taken).map { (response, segments, took) =>
                response match
                    case completion @ Response.Tagged(received, _, _, _) =>
                        if received == tag then Loop.done((lines, Present(Reply(lines, completion))))
                        else Abort.fail(protocol(method, segments))
                    case Response.Untagged(Data.Status(Condition.Bye, _, text)) if !logout =>
                        Abort.fail(transport(method, Kind.ConnectionClosed(Present(text))))
                    case Response.Untagged(data) =>
                        counted(data).andThen(Loop.continue(lines.append(Line(data, segments)), answered, taken + took))
                    case Response.Continuation(_) =>
                        if stopAtContinuation then Loop.done((lines, Absent))
                        else
                            respond match
                                case Present(answer) =>
                                    connection.write(method, Utf8.encode(answer(answered) + "\r\n"))
                                        .andThen(Loop.continue(lines, answered + 1, taken + took))
                                case Absent => Abort.fail(protocol(method, segments))
            }
        }

    private def isUid(n: Long): Boolean = n >= 1 && n <= 0xffffffffL

    // The UIDs of SEARCH `numbers` and ESEARCH `ranges`, every one already checked by `isUid`, ascending and each once. They are expanded
    // into one array of primitive longs, sorted and deduplicated in place, so the result's own UIDs are the only allocation per UID. The
    // count fits an Int: every UID took at least two octets of a reply within maxResponseLength.
    private def merged(selection: Selected, numbers: Chunk[Chunk[Long]], ranges: Chunk[Chunk[(Long, Long)]])(using
        Frame
    ): Chunk[Email.Uid] =
        val count =
            numbers.foldLeft(0L)(_ + _.size) + ranges.foldLeft(0L)((sum, set) => set.foldLeft(sum)((s, r) => s + r._2 - r._1 + 1))
        val all = new Array[Long](count.toInt)
        var at  = 0
        numbers.foreach(_.foreach { n =>
            all(at) = n
            at += 1
        })
        ranges.foreach(_.foreach { (low, high) =>
            var n = low
            while n <= high do
                all(at) = n
                at += 1
                n += 1
            end while
        })
        java.util.Arrays.sort(all)
        val out = ChunkBuilder.init[Email.Uid]
        var i   = 0
        while i < all.length do
            if i == 0 || all(i) != all(i - 1) then
                Email.Uid.read(selection.mailbox, selection.validity, all(i)).foreach(uid => discard(out.addOne(uid)))
            i += 1
        end while
        out.result()
    end merged

    // The octets a plain SEARCH listing of the UIDs `low` to `high` takes: each UID's digits and a space, counted by digit length.
    private def listing(low: Long, high: Long): Long = (1 to 10).foldLeft((0L, 1L)) { case ((sum, first), digits) =>
        val last  = first * 10 - 1
        val count = math.max(0L, math.min(high, last) - math.max(low, first) + 1)
        (sum + count * (digits + 1), first * 10)
    }._1

    // The octets a response took on the wire: each line with its line end, and each literal.
    private def octets(segments: Chunk[Segment]): Long =
        segments.foldLeft(0L) {
            case (sum, Segment.Text(line))      => sum + line.size + 2
            case (sum, Segment.Literal(octets)) => sum + octets.size
        }

    // One response within `remaining` octets, with its segments and the octets it took: a line, and while a line ends in a literal marker,
    // the literal and the line after it. A literal past what remains, or past Int.MaxValue octets, which one array cannot hold, is refused
    // before any of it is read.
    private def next(method: String, remaining: Long)(using
        Frame
    ): (Response, Chunk[Segment], Long) < (Async & Abort[EmailTransportException]) =
        Loop(Chunk.empty[Segment], 0L) { (segments, taken) =>
            connection.readLine(method, LineLimit).map { line =>
                val read    = segments.append(Segment.Text(line))
                val withEnd = taken + line.size + 2
                def past    = protocol(method, Chunk(Segment.Text(line)))
                if withEnd > remaining then Abort.fail(past)
                else
                    (if segments.isEmpty && !carriesLiterals(line) then Absent else literalAt(line)) match
                        case Absent        => Loop.done((read, withEnd))
                        case Present(size) =>
                            if withEnd + size > remaining || size > Int.MaxValue then Abort.fail(past)
                            else
                                connection.readExactly(method, size.toInt)
                                    .map(octets => Loop.continue(read.append(Segment.Literal(octets)), withEnd + size))
                    end match
                end if
            }
        }.map { (segments, taken) =>
            parse(segments) match
                case Result.Success(response) => (response, segments, taken)
                case Result.Failure(shown)    => Abort.fail(transport(method, Kind.Protocol(shown)))
                case Result.Panic(ex)         => Abort.panic(ex)
        }

    private def protocol(method: String, segments: Chunk[Segment])(using Frame): EmailTransportException =
        val first = segments.headMaybe match
            case Present(Segment.Text(line)) => line
            case _                           => Span.empty[Byte]
        transport(method, Kind.Protocol(redactor.redact(LineConnection.shown(first), LineConnection.ShownOctets)))
    end protocol

    // Server text in a transport failure from the connection or the parser is redacted here: every failure of `command` and `greeting`
    // passes through it. A `Protocol` failure the session builds itself is redacted where `protocol` builds it.
    private def redacted(ex: EmailTransportException)(using Frame): EmailTransportException =
        ex.kind match
            case Kind.Protocol(received) => ex.copy(kind = Kind.Protocol(redactor.redact(received, LineConnection.ShownOctets)))
            case Kind.ConnectionClosed(Present(text)) => ex.copy(kind = Kind.ConnectionClosed(Present(redactor.redact(text))))
            case _                                    => ex

    // A response code's arguments are server text like the text beside them, so they are redacted before a failure keeps the code.
    private def responseCode(code: Code): EmailReceive.ResponseCode =
        EmailReceive.ResponseCode.fromWire(code.atom, code.arguments.map(redactor.redact))

    private def transport(method: String, kind: Kind, timeout: Maybe[Duration] = Absent)(using Frame): EmailTransportException =
        EmailTransportException(method, kind, host, port, timeout)

    private def startTlsUnavailable(method: String)(using Frame): EmailConnectException =
        EmailConnectException(method, EmailConnectException.Kind.StartTlsUnavailable, host, port, Absent)(Absent)

    private def handshake(method: String, tls: Email.Tls, netTls: NetTlsConfig, credential: Credential, connectTimeout: Duration)(using
        Frame
    ): Unit < (Async & Abort[EmailConnectFailure]) =
        greeting(method).map { (preauthenticated, advertised) =>
            tls match
                case _: Email.Tls.StartTls =>
                    // A PREAUTH greeting on a plaintext connection leaves no point at which STARTTLS can be sent (RFC 9051 section 7.1.4).
                    if preauthenticated then Abort.fail(startTlsUnavailable(method))
                    else
                        known(method, advertised).map { offered =>
                            if !offered.contains("STARTTLS") then Abort.fail(startTlsUnavailable(method))
                            else
                                command(method, Chunk(Part.Text("STARTTLS")))
                                    .map(expectOk(method, "STARTTLS", _))
                                    .andThen(connection.startTls(method, netTls, connectTimeout))
                                    .andThen(capability(method))
                                    .map(authenticate(method, credential, _))
                        }
                case _: Email.Tls.Implicit =>
                    known(method, advertised).map { offered =>
                        if preauthenticated then () else authenticate(method, credential, offered)
                    }
        }

    private def greeting(method: String)(using Frame): (Boolean, Maybe[Set[String]]) < (Async & Abort[EmailTransportException]) =
        Abort.run[EmailTransportException] {
            Abort.run[Timeout](Async.timeout(commandTimeout)(next(method, maxResponseLength))).map {
                case Result.Success((Response.Untagged(Data.Status(condition, code, text)), segments, _)) =>
                    condition match
                        case Condition.Ok      => (false, advertised(code))
                        case Condition.PreAuth => (true, advertised(code))
                        case Condition.Bye     => Abort.fail(transport(method, Kind.ConnectionClosed(Present(text))))
                        case _                 => Abort.fail(protocol(method, segments))
                case Result.Success((_, segments, _)) => Abort.fail(protocol(method, segments))
                case Result.Failure(_)                => Abort.fail(transport(method, Kind.Timeout, Present(commandTimeout)))
                case Result.Panic(ex)                 => Abort.panic(ex)
            }
        }.map {
            case Result.Success(greeted) => greeted
            case Result.Failure(ex)      => Abort.fail(redacted(ex))
            case Result.Panic(ex)        => Abort.panic(ex)
        }

    private def known(method: String, advertised: Maybe[Set[String]])(using
        Frame
    ): Set[String] < (Async & Abort[EmailTransportException | EmailImapCommandException]) =
        advertised match
            case Present(offered) => state.updateAndGet(_.copy(capabilities = offered)).unit.andThen(offered)
            case Absent           => capability(method)

    private def capability(method: String)(using
        Frame
    ): Set[String] < (Async & Abort[EmailTransportException | EmailImapCommandException]) =
        command(method, Chunk(Part.Text("CAPABILITY"))).map(expectOk(method, "CAPABILITY", _)).map { reply =>
            val offered = reply.untagged.flatMap {
                case Line(Data.Capability(names), _) => names.map(name => Ascii.toUpper(name))
                case _                               => Chunk.empty
            }.toSet
            state.updateAndGet(_.copy(capabilities = offered)).unit.andThen(offered)
        }

    private def authenticate(method: String, credential: Credential, offered: Set[String])(using
        Frame
    ): Unit < (Async & Abort[EmailConnectFailure]) =
        credential match
            case Credential.Password(user, password) =>
                if offered.contains("AUTH=PLAIN") then
                    sasl(method, offered, "PLAIN", Sasl.plain(user, password), "*", user, Email.Auth.Mechanism.Plain)
                else if !offered.contains("LOGINDISABLED") then
                    val parts = Chunk(Part.Text("LOGIN "))
                        .concat(astring(user, nonSynchronizing(offered, user)))
                        .append(Part.Text(" "))
                        .concat(astring(password.value, nonSynchronizing(offered, password.value)))
                    authenticated(method, user, Email.Auth.Mechanism.Login, "LOGIN", command(method, parts))
                else unavailable(method, offered, Chunk(Email.Auth.Mechanism.Plain, Email.Auth.Mechanism.Login))
            case Credential.Token(user, token) =>
                // An XOAUTH2 failure arrives as a continuation holding a JSON error; the empty answer lets the server send its `NO`.
                if offered.contains("AUTH=XOAUTH2") then
                    sasl(method, offered, "XOAUTH2", Sasl.xoauth2(user, token), "", user, Email.Auth.Mechanism.XOAuth2)
                else unavailable(method, offered, Chunk(Email.Auth.Mechanism.XOAuth2))

    // `AUTHENTICATE` with the initial response on the command line under SASL-IR (RFC 4959), else after the first `+`; any further `+`
    // is answered with `afterwards`.
    private def sasl(
        method: String,
        offered: Set[String],
        mechanism: String,
        initial: String,
        afterwards: String,
        user: String,
        kind: Email.Auth.Mechanism
    )(using Frame): Unit < (Async & Abort[EmailConnectFailure]) =
        val inline = offered.contains("SASL-IR")
        val line   = if inline then s"AUTHENTICATE $mechanism $initial" else s"AUTHENTICATE $mechanism"
        val answer = (index: Int) => if !inline && index == 0 then initial else afterwards
        authenticated(method, user, kind, "AUTHENTICATE", command(method, Chunk(Part.Text(line)), Present(answer)))
    end sasl

    private def authenticated(
        method: String,
        user: String,
        mechanism: Email.Auth.Mechanism,
        name: String,
        reply: Reply < (Async & Abort[EmailTransportException])
    )(using Frame): Unit < (Async & Abort[EmailConnectFailure]) =
        reply.map { done =>
            done.completion match
                case Response.Tagged(_, Condition.Ok, code, _)    => known(method, advertised(code)).unit
                case Response.Tagged(_, Condition.No, code, text) =>
                    Abort.fail(EmailAuthenticationException(
                        method,
                        host,
                        user,
                        mechanism,
                        EmailAuthenticationException.Reply.Imap(code.map(responseCode), redactor.redact(text))
                    ))
                case completion => Abort.fail(refused(method, name, completion))
        }

    private def unavailable(method: String, offered: Set[String], wanted: Chunk[Email.Auth.Mechanism])(using
        Frame
    ): Unit < Abort[EmailConnectFailure] =
        Abort.fail(EmailAuthMechanismUnavailableException(
            method,
            EmailException.Protocol.Imap,
            host,
            wanted,
            Chunk.from(offered.filter(_.startsWith("AUTH=")).map(_.drop(5)).toSeq.sorted)
        ))

end ImapSession

private[kyo] object ImapSession:

    /** The longest line read without a literal; longer is `Protocol`, protecting the client from a server that never ends a line. */
    inline val LineLimit = 1 << 20

    /** A tag (`A` and a `Long`), the space after it and the CRLF: what a command line holds besides its own text. */
    inline val TagRoom = 23

    /** What a FETCH response of one message holds besides its body: the line up to the body's literal and the line after it, each at most
      * `LineLimit` with its line end.
      */
    inline val FramingRoom = 2L * (LineLimit + 2)

    final case class Selected(mailbox: Email.MailboxName, validity: Email.UidValidity) derives CanEqual

    /** What a listing said of a message's size: a number of octets, a value that is not a number, or nothing. */
    enum Listed derives CanEqual:
        case Octets(size: Long)
        case Unreadable
        case Unsized
    end Listed

    /** `announced` is the number of untagged `EXISTS` read on the connection, in any reply and while idling; `messages` is the selected
      * mailbox's message count as the last `EXISTS` and the `EXPUNGE`s after it leave it.
      */
    final case class State(capabilities: Set[String], selected: Maybe[Selected], closed: Boolean, announced: Long = 0, messages: Long = 0)

    /** One untagged response, parsed, with the segments it was read from. */
    final case class Line(data: Data, segments: Chunk[Segment])

    final case class Reply(untagged: Chunk[Line], completion: Response.Tagged)

    private enum Credential:
        case Password(user: String, password: Email.Password)
        case Token(user: String, token: Email.OAuthToken)

    /** Connects, reads the greeting, negotiates STARTTLS when configured, and authenticates. The OAuth token is computed first, outside
      * every deadline, so its failure opens no connection; the redactor is built from it before the first byte. The connection is closed
      * when any later step fails or is interrupted.
      */
    def open(method: String, config: EmailImapConfig)(using Frame): ImapSession < (Async & Abort[EmailConnectFailure]) =
        credential(config.account).map { credential =>
            val implicitTls = config.tls match
                case _: Email.Tls.Implicit => Present(TlsOptions.netConfig(config.tls))
                case _: Email.Tls.StartTls => Absent
            LineConnection.open(method, config.host, config.resolvedPort, implicitTls, config.connectTimeout).ensureMap { connection =>
                owned(connection)(started(method, config, credential, connection))
            }
        }

    /** A session over `connection`, already open to the config's server and, under `Email.Tls.Implicit`, taken to be secured. */
    private[kyo] def over(method: String, config: EmailImapConfig, connection: LineConnection)(using
        Frame
    ): ImapSession < (Async & Abort[EmailConnectFailure]) =
        credential(config.account).map(credential => owned(connection)(started(method, config, credential, connection)))

    // `v` with `connection` closed if it fails or is interrupted. The caller reaches it through `ensureMap`, so no preemption point lies
    // between the connection existing and this obligation being registered (kyo-kernel `Pending.ensureMap`).
    private def owned[A](connection: LineConnection)(v: => A < (Async & Abort[EmailConnectFailure]))(using
        Frame
    ): A < (Async & Abort[EmailConnectFailure]) =
        Sync.ensure[A, EmailConnectFailure, Async]((error: Maybe[Result.Error[Any]]) => if error.isEmpty then () else connection.close)(v)

    private def started(method: String, config: EmailImapConfig, credential: Credential, connection: LineConnection)(using
        Frame
    ): ImapSession < (Async & Abort[EmailConnectFailure]) =
        val redactor = credential match
            case Credential.Password(user, password) => Redactor.password(user, password)
            case Credential.Token(user, token)       => Redactor.token(user, token)
        for
            mutex <- Meter.initMutexUnscoped
            tags  <- AtomicLong.init
            state <- AtomicRef.init(State(Set.empty, Absent, closed = false))
            session = new ImapSession(
                config.host,
                config.resolvedPort,
                connection,
                redactor,
                mutex,
                config.commandTimeout,
                StreamCoreExtensions.readBufferCapacity(config.maxResponseLength).toLong,
                tags,
                state
            )
            _ <- session.handshake(method, config.tls, TlsOptions.netConfig(config.tls), credential, config.connectTimeout)
        yield session
        end for
    end started

    private def credential(account: Email.Account)(using Frame): Credential < (Async & Abort[EmailTokenException]) =
        account.auth match
            case Email.Auth.Password(password) => Credential.Password(account.user, password)
            case Email.Auth.OAuth2(token)      => token.map(Credential.Token(account.user, _))

    private def advertised(code: Maybe[Code]): Maybe[Set[String]] =
        code.filter(_.atom == "CAPABILITY").map(c =>
            c.arguments.getOrElse("").split(' ').filter(_.nonEmpty).map(name => Ascii.toUpper(name)).toSet
        )

    // Mailbox names go out and come back as UTF-8 only when the server speaks IMAP4rev2 alone; with IMAP4rev1 offered it answers as rev1.
    private def utf8(offered: Set[String]): Boolean = offered.contains("IMAP4REV2") && !offered.contains("IMAP4REV1")

    private def announcedValidity(reply: Reply): Maybe[Email.UidValidity] =
        announced(reply, "UIDVALIDITY").flatMap(Email.UidValidity.read)

    // The number an untagged `OK [atom n]` of the reply carries (`UIDVALIDITY`, `UIDNEXT`).
    private def announced(reply: Reply, atom: String): Maybe[Long] =
        Maybe.fromOption(reply.untagged.collectFirst {
            case Line(Data.Status(Condition.Ok, Present(Code(`atom`, Present(value))), _), _) => value
        }).flatMap(ImapCodec.number)

    // RFC 7888: `LITERAL+` sends any literal without waiting; `LITERAL-`, which IMAP4rev2 includes, only up to 4096 octets.
    private def nonSynchronizing(offered: Set[String], value: String): Boolean =
        offered.contains("LITERAL+") ||
            ((offered.contains("LITERAL-") || offered.contains("IMAP4REV2")) && Utf8.encode(value).size <= 4096)

end ImapSession
