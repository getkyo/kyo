package kyo

import kyo.EmailReceive.InboxEvent.Received
import kyo.EmailReceive.Start
import kyo.EmailTransportException.Kind
import kyo.internal.email.imap.ImapTestServer
import kyo.internal.email.imap.ImapTestServer.*
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.Certificate
import kyo.internal.email.net.LineConnectionFixture.scripted
import kyo.net.NetException

class EmailReceiveLoopTest extends kyo.test.Test[Any]:

    private val byPass   = EmailLiterals.passwordAccountOf(User, EmailLiterals.passwordOf("s3cret-pass"))
    private val inbox    = EmailLiterals.mailboxOf("INBOX")
    private val archive  = EmailLiterals.mailboxOf("Archive")
    private val validity = EmailLiterals.uidValidityOf(7)
    private val items    = "(UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[])"
    private val idling   = "IMAP4rev1 IDLE"

    private def uid(n: Long, v: Long = 7): Email.Uid = EmailLiterals.uidOf(inbox, EmailLiterals.uidValidityOf(v), n)

    private def raw(subject: String): String = s"Subject: $subject\r\n\r\nbody\r\n"

    // A FETCH response carrying a whole message as a literal.
    private def message(sequence: Int, n: Long, subject: String): Seq[String] =
        val content = raw(subject)
        Seq(
            s"* $sequence FETCH (UID $n FLAGS (\\Seen) INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE ${content.length} BODY[] {${content.length}}",
            content + ")"
        )
    end message

    private def fetched(lines: Seq[String]*): Seq[String] = lines.flatten :+ "TAG OK FETCH done"

    private val listing = "(UID RFC822.SIZE)"

    // A round's listing: each pending UID with its size.
    private def listed(uids: Long*): Seq[String] =
        uids.zipWithIndex.map((n, i) => s"* ${i + 1} FETCH (UID $n RFC822.SIZE ${raw("x").length})") :+ "TAG OK FETCH done"

    // A round on the server: the listing of `messages`, then each one's body as the client asks for it.
    private def round(peer: Peer, messages: (Long, String)*)(using Frame) =
        peer.answer(listed(messages.map(_._1)*)*).andThen(Kyo.foreachDiscard(messages.zipWithIndex) { case ((n, subject), i) =>
            peer.answer(fetched(message(i + 1, n, subject))*)
        })

    // A server whose script is followed, whatever becomes of it, by answering until the client closes.
    private def loopServer(script: Peer => Any < (Async & Abort[LineConnectionFixture.Failure]))(using
        Frame
    ): Server < (Async & Scope & Abort[NetException]) =
        serve()(peer => Abort.run[LineConnectionFixture.Failure](script(peer)).andThen(peer.untilClosed))

    private def configFor(server: Server, reconnect: Schedule = Schedule.never)(using Frame): EmailImapConfig < Sync =
        ImapTestServer.config(server, byPass).map(_.reconnect(reconnect))

    // The loop over a handler of readable messages: every leaf outside "unreadable messages" scripts only those, so another event fails.
    private def receive[E2, S](config: EmailImapConfig, start: Start)(
        handler: Received => Unit < (Async & Abort[E2] & EmailReceive & S)
    )(using Frame): Unit < (Async & Abort[EmailReceiveFailure | E2] & S) =
        EmailReceive.run(config)(EmailReceive.receive(start) {
            case r: Received => handler(r)
            case other       => Abort.panic(new AssertionError(s"unscripted $other"))
        })

    private def subjects(delivered: Seq[Received])(using Frame): Chunk[String] < Abort[EmailParseFailure] =
        Kyo.foreach(Chunk.from(delivered))(_.message.map(_.subject))

    "one message at a time" - {
        "pending messages are listed, then fetched one UID at a time, each delivered before the next body is asked for" in scripted {
            Scope.run {
                for
                    idle   <- Latch.init(1)
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7))
                            .andThen(round(peer, (1L, "one"), (2L, "two"), (3L, "three")))
                            .andThen(peer.command).andThen(idle.release)
                    }
                    config <- configFor(server)
                    order  <- AtomicRef.init(Chunk.empty[(Long, Int)])
                    fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received =>
                        server.received.map(seen => order.updateAndGet(_.append((r.uid.value, seen.size))).unit)
                    })
                    _    <- idle.await
                    _    <- fiber.interrupt
                    ran  <- order.get
                    seen <- server.received
                yield
                    assert(seen.slice(2, 6) ==
                        Chunk(s"A3 UID FETCH 1:* $listing", s"A4 UID FETCH 1 $items", s"A5 UID FETCH 2 $items", s"A6 UID FETCH 3 $items"))
                    assert(ran == Chunk((1L, 4), (2L, 5), (3L, 6)))
            }
        }
        "a drop after the first delivery loses nothing delivered and redelivers nothing: the reconnect lists from the second UID" in
            scripted {
                Scope.run {
                    for
                        idle   <- Latch.init(1)
                        server <- loopServer { peer =>
                            if peer.index == 1 then
                                peer.ready(authenticated = idling).andThen(peer.select(7))
                                    .andThen(peer.answer(listed(1L, 2L, 3L)*))
                                    .andThen(peer.answer(fetched(message(1, 1L, "one"))*))
                                    .andThen(peer.command).andThen(peer.line.close)
                            else
                                peer.ready(authenticated = idling).andThen(peer.select(7))
                                    .andThen(round(peer, (2L, "two"), (3L, "three")))
                                    .andThen(peer.command).andThen(idle.release)
                        }
                        config <- configFor(server, Schedule.repeat(1))
                        uids   <- AtomicRef.init(Chunk.empty[Long])
                        fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received =>
                            uids.updateAndGet(_.append(r.uid.value)).unit
                        })
                        _         <- idle.await
                        _         <- fiber.interrupt
                        delivered <- uids.get
                        seen      <- server.received
                    yield
                        assert(delivered == Chunk(1L, 2L, 3L))
                        assert(seen.filter(_.endsWith(listing)) == Chunk(s"A3 UID FETCH 1:* $listing", s"A3 UID FETCH 2:* $listing"))
                }
            }
    }

    "a mailbox larger than one listing" - {
        // A server holding the messages of `uids`, numbered from 1 in UID order, that answers the loop's listings, probes and fetches from
        // them, and releases `idle` when the loop idles. Fetching a UID `expunging` names first expunges that many messages from the
        // lowest sequence number, reported as EXPUNGE in the fetch's reply. A `UID MOVE` removes its message, reported as its EXPUNGE.
        def holding(peer: Peer, uids: Seq[Long], idle: Latch, expunging: Map[Long, Int] = Map.empty)(using Frame) =
            def range(set: String, last: Long): Seq[Long] =
                set.split(",").toSeq.flatMap { part =>
                    part.split(":") match
                        case Array(a)    => Seq(a.toLong)
                        case Array(a, b) => (a.toLong to (if b == "*" then last else b.toLong)).toSeq
                        case _           => Seq.empty
                }
            AtomicRef.init(uids).map { model =>
                peer.ready(authenticated = s"$idling MOVE")
                    .andThen(peer.answer(
                        s"* ${uids.size} EXISTS",
                        "* OK [UIDVALIDITY 7] v",
                        s"* OK [UIDNEXT ${uids.last + 1}] n",
                        "TAG OK selected"
                    ))
                    .andThen(Loop.foreach {
                        peer.command.map { (tag, rest) =>
                            model.get.map { held =>
                                val count            = held.size.toLong
                                def sized(seq: Long) =
                                    s"* $seq FETCH (UID ${held((seq - 1).toInt)} RFC822.SIZE ${raw(s"s${held((seq - 1).toInt)}").length})"
                                def done(lines: Seq[String]) =
                                    Kyo.foreachDiscard(lines :+ s"$tag OK done")(peer.send).andThen(Loop.continue)
                                rest match
                                    case "IDLE"                              => idle.release.andThen(Loop.done(()))
                                    case s"UID FETCH $set (UID RFC822.SIZE)" =>
                                        val low = set.takeWhile(_ != ':').toLong
                                        done(held.zipWithIndex.filter(_._1 >= low).map((_, i) => sized(i + 1L)))
                                    case s"FETCH $set (UID RFC822.SIZE)" => done(range(set, count).filter(_ <= count).map(sized))
                                    case s"FETCH $set (UID)"             =>
                                        done(range(set, count).filter(_ <= count).map(seq =>
                                            s"* $seq FETCH (UID ${held((seq - 1).toInt)})"
                                        ))
                                    case s"UID MOVE $uid $_" =>
                                        val at = held.indexOf(uid.toLong)
                                        model.set(held.filter(_ != uid.toLong)).andThen(done(Seq(s"* ${at + 1} EXPUNGE")))
                                    case s"UID FETCH $uid $_" =>
                                        val expunged = expunging.getOrElse(uid.toLong, 0)
                                        val after    = held.drop(expunged)
                                        model.set(after).andThen(done(
                                            Seq.fill(expunged)("* 1 EXPUNGE") ++
                                                message(after.indexOf(uid.toLong) + 1, uid.toLong, s"s$uid")
                                        ))
                                    case _ => peer.send(s"$tag BAD unknown").andThen(Loop.continue)
                                end match
                            }
                        }
                    })
            }
        end holding
        // What the handler received, the commands the server read, and whether the loop reached IDLE, which a failed round does not. With
        // `moving` the handler moves each message it receives to another mailbox.
        def delivered(uids: Seq[Long], expunging: Map[Long, Int], moving: Boolean = false)(using
            Frame
        ): (Chunk[Long], Chunk[String], Boolean) < (Async & Scope & Abort[kyo.net.NetException]) =
            val archive = EmailLiterals.mailboxOf("Archive")
            for
                idle   <- Latch.init(1)
                server <- loopServer(peer => holding(peer, uids, idle, expunging))
                config <-
                    configFor(server).map(c => EmailLiterals.valid(c.maxResponseLength(ByteSize.fromBytes(1024))): EmailImapConfig)
                got   <- AtomicRef.init(Chunk.empty[Long])
                fiber <- Fiber.initUnscoped(Abort.run[EmailReceiveFailure | EmailMoveFailure](receive(config, Start.All(inbox)) {
                    case r: Received =>
                        got.updateAndGet(_.append(r.uid.value)).andThen(if moving then EmailReceive.move(Seq(r.uid), archive) else ())
                }))
                _         <- Async.race(idle.await, fiber.getResult.unit)
                _         <- fiber.interrupt
                delivered <- got.get
                seen      <- server.received
                idled     <- idle.pending.map(_ == 0)
            yield (delivered, seen, idled)
            end for
        end delivered
        // 40 messages under a 1024-octet maxResponseLength are listed 8 at a time.
        val uids = (1L to 40L).map(_ * 3)
        "with more pending messages than one listing may hold, every one is delivered in UID order, a window at a time" in scripted {
            Scope.run {
                delivered(uids, Map.empty).map { (delivered, seen, idled) =>
                    assert(delivered == Chunk.from(uids))
                    assert(!seen.exists(_.endsWith(s"UID FETCH 1:* $listing")))
                    assert(seen.exists(_.endsWith("FETCH 8:16 (UID RFC822.SIZE)")))
                    assert(idled)
                }
            }
        }
        "messages expunged below the window are not a reason to skip the ones they shift below it" in scripted {
            Scope.run {
                // Fetching message 12 expunges messages 1 to 3, so the next window's first message sits at 14, below its cursor at 17.
                delivered(uids, Map(uids(11) -> 3)).map { (delivered, _, idled) =>
                    assert(delivered == Chunk.from(uids))
                    assert(idled)
                }
            }
        }
        "messages expunged past the round's end are not a reason to end it before the ones they shift below it" in scripted {
            Scope.run {
                // Fetching message 28 expunges messages 1 to 9, so 31 remain and the last window's cursor at 33 is past them.
                delivered(uids, Map(uids(27) -> 9)).map { (delivered, _, idled) =>
                    assert(delivered == Chunk.from(uids))
                    assert(idled)
                }
            }
        }
        "messages a handler moves away, each reported by an EXPUNGE in the move's reply, shift no pending one out of the round" in
            scripted {
                Scope.run {
                    // Each move expunges the message at sequence number 1, so every window after the first starts below its cursor.
                    delivered(uids, Map.empty, moving = true).map { (delivered, seen, idled) =>
                        assert(delivered == Chunk.from(uids))
                        assert(seen.count(_.contains("UID MOVE")) == uids.size)
                        assert(idled)
                    }
                }
            }
    }

    "unreadable messages" - {
        def delivered(
            script: Peer => Any < (Async & Abort[LineConnectionFixture.Failure]),
            configure: EmailImapConfig => EmailImapConfig
        )(
            using Frame
        ): (Chunk[EmailReceive.InboxEvent], Chunk[String]) < (Async & Abort[NetException]) =
            Scope.run {
                for
                    idle   <- Latch.init(1)
                    server <- loopServer(peer => script(peer).andThen(peer.command).andThen(idle.release))
                    config <- configFor(server).map(configure)
                    events <- AtomicRef.init(Chunk.empty[EmailReceive.InboxEvent])
                    fiber  <-
                        Fiber.initUnscoped(Abort.run[EmailReceiveFailure](EmailReceive.run(config)(EmailReceive.receive(Start.All(inbox)) {
                            event => events.updateAndGet(_.append(event)).unit
                        })))
                    _    <- Async.race(idle.await, fiber.getResult.unit)
                    _    <- fiber.interrupt
                    got  <- events.get
                    seen <- server.received
                yield (got, seen)
            }
        "a FLAGS list the module cannot read is Unreadable(uid, Flags), delivered in its place, and the loop moves on" in scripted {
            val content = raw("two")
            delivered(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer(listed(1L, 2L, 3L)*))
                        .andThen(peer.answer(fetched(message(1, 1, "one"))*))
                        .andThen(peer.answer(
                            s"* 2 FETCH (UID 2 FLAGS (\\Seen \"odd\") INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE ${content.length} BODY[] {${content.length}}",
                            content + ")",
                            "TAG OK FETCH done"
                        ))
                        .andThen(peer.answer(fetched(message(3, 3, "three"))*)),
                identity
            ).map { (events, _) =>
                assert(events.map {
                    case r: Received                              => (r.uid, "received")
                    case EmailReceive.InboxEvent.Unreadable(u, i) => (u, i.toString)
                } == Chunk((uid(1), "received"), (uid(2), "Flags"), (uid(3), "received")))
            }
        }
        "a flag that is not an IMAP atom arrives as Other, as sent, and the delivery round-trips through its Schema; a flag with a space is Unreadable" in
            scripted {
                val one  = raw("one")
                val two  = raw("two")
                val date = "INTERNALDATE \"17-Jul-1996 02:44:25 -0700\""
                delivered(
                    peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7))
                            .andThen(peer.answer(listed(1L, 2L)*))
                            .andThen(peer.answer(
                                s"* 1 FETCH (UID 1 FLAGS (\\Seen $$a%b) $date RFC822.SIZE ${one.length} BODY[] {${one.length}}",
                                one + ")",
                                "TAG OK FETCH done"
                            ))
                            .andThen(peer.answer(
                                s"* 2 FETCH (UID 2 FLAGS (a[b c]) $date RFC822.SIZE ${two.length} BODY[] {${two.length}}",
                                two + ")",
                                "TAG OK FETCH done"
                            )),
                    identity
                ).map { (events, _) =>
                    assert(events.size == 2)
                    events.head match
                        case received: Received =>
                            assert(received.flags.map(_.wire) == Set("\\Seen", "$a%b"))
                            assert(received.flags.exists(flag => flag.isInstanceOf[Email.Flag.Other] && flag.wire == "$a%b"))
                            assert(Json.decode[EmailReceive.InboxEvent](Json.encode[EmailReceive.InboxEvent](received)) ==
                                Result.succeed(received))
                        case other => fail(s"expected a delivery, got $other")
                    end match
                    assert(events(1) == EmailReceive.InboxEvent.Unreadable(uid(2), EmailReceive.InboxEvent.Unreadable.Item.Flags))
                }
            }
        "a message listed past maxResponseLength is Unreadable(uid, TooLarge(size, max)), and its body is never asked for" in scripted {
            delivered(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer("* 1 FETCH (UID 1 RFC822.SIZE 5000)", "TAG OK FETCH done")),
                c => EmailLiterals.valid(c.maxResponseLength(ByteSize.fromBytes(1024))): EmailImapConfig
            ).map { (events, seen) =>
                assert(events == Chunk(EmailReceive.InboxEvent.Unreadable(
                    uid(1),
                    EmailReceive.InboxEvent.Unreadable.Item.TooLarge(ByteSize.fromBytes(5000), ByteSize.fromBytes(1024))
                )))
                assert(seen.slice(2, 4) == Chunk(s"A3 UID FETCH 1:* $listing", "A4 IDLE"))
            }
        }
        "a listed size that is not a number is Unreadable(uid, Size), and its body is never asked for" in scripted {
            delivered(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer("* 1 FETCH (UID 1 RFC822.SIZE NIL)", "TAG OK FETCH done")),
                identity
            ).map { (events, seen) =>
                assert(events == Chunk(EmailReceive.InboxEvent.Unreadable(uid(1), EmailReceive.InboxEvent.Unreadable.Item.Size)))
                assert(seen.slice(2, 4) == Chunk(s"A3 UID FETCH 1:* $listing", "A4 IDLE"))
            }
        }
        "a listed UID without a size is asked for its size once more: still none is Unreadable(uid, Size), one is fetched as usual" in
            scripted {
                def scripted(second: String)(peer: Peer) =
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer(
                            s"* 1 FETCH (UID 1 RFC822.SIZE ${raw("one").length})",
                            "* 2 FETCH (UID 2)",
                            s"* 3 FETCH (UID 3 RFC822.SIZE ${raw("three").length})",
                            "TAG OK FETCH done"
                        ))
                        .andThen(peer.answer(fetched(message(1, 1, "one"))*))
                        .andThen(peer.answer(second, "TAG OK FETCH done"))
                def shape(events: Chunk[EmailReceive.InboxEvent]) = events.map {
                    case r: Received                              => (r.uid, "received")
                    case EmailReceive.InboxEvent.Unreadable(u, i) => (u, i.toString)
                }
                delivered(
                    peer => scripted("* 2 FETCH (UID 2)")(peer).andThen(peer.answer(fetched(message(3, 3, "three"))*)),
                    identity
                ).map { (events, seen) =>
                    assert(shape(events) == Chunk((uid(1), "received"), (uid(2), "Size"), (uid(3), "received")))
                    assert(seen.contains(s"A5 UID FETCH 2 $listing"))
                }.andThen {
                    delivered(
                        peer =>
                            scripted(s"* 2 FETCH (UID 2 RFC822.SIZE ${raw("two").length})")(peer)
                                .andThen(peer.answer(fetched(message(2, 2, "two"))*))
                                .andThen(peer.answer(fetched(message(3, 3, "three"))*)),
                        identity
                    ).map { (events, _) =>
                        assert(shape(events) == Chunk((uid(1), "received"), (uid(2), "received"), (uid(3), "received")))
                    }
                }
            }
        "a message of exactly maxResponseLength is read whole, its FETCH lines allowed besides" in scripted {
            val content = "Subject: s\r\n\r\n" + "x" * (1024 - 16) + "\r\n"
            delivered(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer(s"* 1 FETCH (UID 1 RFC822.SIZE ${content.length})", "TAG OK FETCH done"))
                        .andThen(peer.answer(
                            s"* 1 FETCH (UID 1 FLAGS () INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE ${content.length} BODY[] {${content.length}}",
                            content + ")",
                            "TAG OK FETCH done"
                        )),
                c => EmailLiterals.valid(c.maxResponseLength(ByteSize.fromBytes(1024))): EmailImapConfig
            ).map { (events, _) =>
                assert(content.length == 1024)
                assert(events.map {
                    case r: Received => r.size
                    case other       => fail(other.toString)
                } == Chunk(ByteSize.fromBytes(1024)))
            }
        }
    }

    "mail announced outside IDLE" - {
        // RFC 9051 section 5.2: a server announces a new message in the reply to whatever command it is processing when it sees it.
        def announced(
            script: Peer => Any < (Async & Abort[LineConnectionFixture.Failure]),
            handler: Received => Unit < (Async & EmailReceive)
        )(
            using Frame
        ): Chunk[String] < (Async & Abort[NetException]) =
            Scope.run {
                for
                    idle   <- Latch.init(1)
                    server <- loopServer(peer => script(peer).andThen(peer.command).andThen(idle.release))
                    config <- configFor(server)
                    fiber  <- Fiber.initUnscoped(Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) {
                        case r: Received => handler(r)
                    }))
                    _    <- Async.race(idle.await, fiber.getResult.unit)
                    _    <- fiber.interrupt
                    seen <- server.received
                yield seen
            }
        "an EXISTS in a FETCH reply starts the next round at once, without idling" in scripted {
            announced(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer(listed(1L)*))
                        .andThen(peer.answer(fetched(message(1, 1, "one"), Seq("* 2 EXISTS"))*))
                        .andThen(round(peer, (2L, "two"))),
                _ => Kyo.unit
            ).map { seen =>
                assert(seen.slice(2, 7) == Chunk(
                    s"A3 UID FETCH 1:* $listing",
                    s"A4 UID FETCH 1 $items",
                    s"A5 UID FETCH 2:* $listing",
                    s"A6 UID FETCH 2 $items",
                    "A7 IDLE"
                ))
            }
        }
        "an EXISTS in the reply to a handler's command starts the next round at once, without idling" in scripted {
            announced(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(round(peer, (1L, "one")))
                        .andThen(peer.answer("* 2 EXISTS", "TAG OK STORE done"))
                        .andThen(round(peer, (2L, "two"))),
                r =>
                    if r.uid.value == 1 then
                        Abort.run[EmailAddFlagsFailure](EmailReceive.addFlags(Seq(r.uid), Set(Email.Flag.Flagged))).unit
                    else Kyo.unit
            ).map { seen =>
                assert(seen.slice(2, 8) == Chunk(
                    s"A3 UID FETCH 1:* $listing",
                    s"A4 UID FETCH 1 $items",
                    "A5 UID STORE 1 +FLAGS.SILENT (\\Flagged)",
                    s"A6 UID FETCH 2:* $listing",
                    s"A7 UID FETCH 2 $items",
                    "A8 IDLE"
                ))
            }
        }
        "an EXISTS before IDLE's continuation ends the IDLE at once" in scripted {
            announced(
                peer =>
                    peer.ready(authenticated = idling).andThen(peer.select(7))
                        .andThen(peer.answer(listed()*))
                        .andThen(peer.command.map((tag, _) =>
                            peer.send("* 1 EXISTS").andThen(peer.send("+ idling")).andThen(peer.receive).andThen(
                                peer.send(s"$tag OK IDLE terminated")
                            )
                        ))
                        .andThen(round(peer, (1L, "one"))),
                _ => Kyo.unit
            ).map { seen =>
                assert(seen.slice(2, 7) ==
                    Chunk(s"A3 UID FETCH 1:* $listing", "A4 IDLE", "DONE", s"A5 UID FETCH 1:* $listing", s"A6 UID FETCH 1 $items"))
            }
        }
    }

    "delivery" - {
        "Start.New delivers what arrives after the loop starts, in UID order, and drops the answer n:* gives below n" in scripted {
            Scope.run {
                for
                    delivered <- Channel.init[Received](16)
                    server    <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.select(7))
                            .andThen(peer.answer(listed(9L)*))
                            .andThen(peer.idle("* 5 EXISTS"))
                            .andThen(peer.answer(listed(11L, 10L)*))
                            .andThen(peer.answer(fetched(message(4, 10, "ten"))*))
                            .andThen(peer.answer(fetched(message(5, 11, "eleven"))*))
                    }
                    config <- configFor(server)
                    fiber  <- Fiber.initUnscoped(receive(config, Start.New(inbox)) { case r: Received => delivered.put(r) })
                    first  <- delivered.take
                    second <- delivered.take
                    _      <- fiber.interrupt
                    _      <- server.awaitClose
                    seen   <- server.received
                    names  <- subjects(Seq(first, second))
                yield
                    assert(Chunk(first.uid, second.uid) == Chunk(uid(10), uid(11)))
                    assert(names == Chunk("ten", "eleven"))
                    assert(first.flags == Set[Email.Flag](Email.Flag.Seen))
                    assert(first.internalDate == EmailLiterals.instantOf("1996-07-17T09:44:25Z"))
                    assert(first.size == raw("ten").length.toLong.bytes)
                    assert(seen.slice(1, 8) == Chunk(
                        "A2 SELECT INBOX",
                        s"A3 UID FETCH 10:* $listing",
                        "A4 IDLE",
                        "DONE",
                        s"A5 UID FETCH 10:* $listing",
                        s"A6 UID FETCH 10 $items",
                        s"A7 UID FETCH 11 $items"
                    ))
            }
        }
        "Start.All delivers from the first UID" in scripted {
            Scope.run {
                for
                    delivered <- Channel.init[Received](16)
                    server    <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.select(7))
                            .andThen(round(peer, (1L, "one")))
                    }
                    config <- configFor(server)
                    all    <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received => delivered.put(r) })
                    first  <- delivered.take
                    _      <- all.interrupt
                    _      <- server.awaitClose
                    seen   <- server.received
                yield
                    assert(first.uid == uid(1))
                    assert(seen.slice(2, 4) == Chunk(s"A3 UID FETCH 1:* $listing", s"A4 UID FETCH 1 $items"))
            }
        }
        "unsolicited flag updates are passed over: a message is delivered once, and a UID with no data is not delivered" in scripted {
            Scope.run {
                for
                    idle   <- Latch.init(1)
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.select(7))
                            .andThen(peer.answer(
                                "* 1 FETCH (UID 1 FLAGS ())",
                                s"* 1 FETCH (UID 1 RFC822.SIZE ${raw("one").length})",
                                "* 2 FETCH (UID 2 FLAGS (\\Seen))",
                                "TAG OK FETCH done"
                            ))
                            .andThen(peer.answer(fetched(
                                Seq("* 1 FETCH (UID 1 FLAGS ())"),
                                message(1, 1, "one"),
                                Seq("* 2 FETCH (UID 2 FLAGS (\\Seen))")
                            )*))
                            .andThen(peer.command)
                            .andThen(idle.release)
                    }
                    config <- configFor(server)
                    uids   <- AtomicRef.init(Chunk.empty[(Email.Uid, Set[Email.Flag])])
                    fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received =>
                        uids.updateAndGet(_.append((r.uid, r.flags))).unit
                    })
                    _    <- idle.await
                    _    <- fiber.interrupt
                    seen <- uids.get
                yield assert(seen == Chunk((uid(1), Set[Email.Flag](Email.Flag.Seen))))
            }
        }
        "Start.New on a SELECT without UIDNEXT is Incomplete, not retried" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.answer("* 3 EXISTS", "* OK [UIDVALIDITY 7] UIDs valid", "TAG OK [READ-WRITE] selected"))
                    }
                    config <- configFor(server, Schedule.repeat(5))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.New(inbox)) { case _: Received => Kyo.unit })
                yield result match
                    case Result.Failure(ex: EmailTransportException) =>
                        assert(ex.kind == Kind.Incomplete("UIDNEXT") && ex.method == "receive")
                    case other => fail(other.toString)
            }
        }
        "Start.After asks for the UIDs above its own" in scripted {
            Scope.run {
                for
                    asked  <- Latch.init(1)
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.command).andThen(asked.release)
                    }
                    config <- configFor(server)
                    fiber  <- Fiber.initUnscoped(receive(config, Start.After(uid(5))) { case _: Received => Kyo.unit })
                    _      <- asked.await
                    _      <- fiber.interrupt
                    _      <- server.awaitClose
                    seen   <- server.received
                yield assert(seen(2) == s"A3 UID FETCH 6:* $listing")
            }
        }
        "Start.After with a UID of another UIDVALIDITY is EmailUidValidityChangedException" in scripted {
            Scope.run {
                for
                    server <- loopServer(peer => peer.ready(authenticated = idling).andThen(peer.select(7)))
                    config <- configFor(server)
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.After(uid(5, v = 6))) { case _: Received => Kyo.unit })
                yield assert(result ==
                    Result.fail(EmailUidValidityChangedException("receive", inbox, EmailLiterals.uidValidityOf(6), validity)))
            }
        }
        "without IDLE the loop polls every pollInterval, on kyo's clock" in scripted {
            Scope.run {
                for
                    delivered <- Channel.init[Received](16)
                    polled    <- Latch.init(1)
                    server    <- loopServer { peer =>
                        peer.ready()
                            .andThen(peer.select(7))
                            .andThen(round(peer, (1L, "one")))
                            .andThen(round(peer, (2L, "two")))
                            .andThen(polled.release)
                    }
                    config  <- configFor(server)
                    outcome <- Clock.withTimeControl { control =>
                        for
                            fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received => delivered.put(r) })
                            first  <- delivered.take
                            _      <- control.awaitPendingSleepers(1)
                            before <- server.received
                            // One nanosecond short of pollInterval no sleep armed after the first round can wake, so nothing is sent.
                            _      <- control.advance(config.pollInterval.minusOrZero(1.nanos))
                            early  <- server.received
                            _      <- control.advance(1.nanos)
                            _      <- polled.await
                            second <- delivered.take
                            _      <- fiber.interrupt
                        yield (first.uid, second.uid, before, early)
                    }
                    _    <- server.awaitClose
                    seen <- server.received
                yield
                    assert(outcome._1 == uid(1) && outcome._2 == uid(2))
                    assert(outcome._3 == seen.take(4))
                    assert(outcome._4 == outcome._3)
                    assert(!seen.exists(_.endsWith("IDLE")))
                    assert(seen.slice(2, 6) == Chunk(
                        s"A3 UID FETCH 1:* $listing",
                        s"A4 UID FETCH 1 $items",
                        s"A5 UID FETCH 2:* $listing",
                        s"A6 UID FETCH 2 $items"
                    ))
            }
        }
        "an IDLE with no new message, whatever else the server reports, is renewed after idleRenewal, on kyo's clock" in scripted {
            Scope.run {
                for
                    idle    <- Latch.init(1)
                    renewed <- Latch.init(1)
                    server  <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.select(7))
                            .andThen(peer.answer(listed()*))
                            .andThen(peer.command)
                            .map((tag, _) =>
                                peer.send("+ idling").andThen(peer.send("* 2 EXPUNGE")).andThen(peer.send("* 1 FETCH (FLAGS (\\Seen))"))
                                    .andThen(idle.release).andThen(peer.receive).andThen(
                                        peer.send(s"$tag OK IDLE terminated")
                                    )
                            )
                            .andThen(peer.answer(listed()*))
                            .andThen(renewed.release)
                    }
                    // No command deadline, so the renewal is the one sleep armed while the IDLE runs.
                    config <- configFor(server).map(c => EmailLiterals.valid(c.commandTimeout(Duration.Infinity)): EmailImapConfig)
                    early  <- Clock.withTimeControl { control =>
                        Fiber.initUnscoped(receive(config, Start.All(inbox)) { case _: Received => Kyo.unit }).map { fiber =>
                            for
                                _     <- idle.await.andThen(control.awaitPendingSleepers(1))
                                _     <- control.advance(config.idleRenewal.minusOrZero(1.nanos))
                                early <- server.received
                                _     <- control.advance(1.nanos)
                                _     <- renewed.await
                                _     <- fiber.interrupt
                            yield early
                        }
                    }
                    _    <- server.awaitClose
                    seen <- server.received
                yield
                    assert(!early.contains("DONE"))
                    assert(seen.slice(2, 6) == Chunk(s"A3 UID FETCH 1:* $listing", "A4 IDLE", "DONE", s"A5 UID FETCH 1:* $listing"))
            }
        }
    }

    "the handler" - {
        "runs its verbs on the loop's session; the loop selects its mailbox again and lists before idling, as mail may have come meanwhile" in
            scripted {
                Scope.run {
                    for
                        done   <- Latch.init(1)
                        server <- loopServer { peer =>
                            peer.ready(authenticated = idling)
                                .andThen(peer.select(7))
                                .andThen(round(peer, (1L, "one")))
                                .andThen(peer.answer("TAG OK STORE done"))
                                .andThen(peer.select(3))
                                .andThen(peer.answer("* SEARCH", "TAG OK SEARCH done"))
                                .andThen(peer.select(7))
                                .andThen(peer.answer(listed()*))
                                .andThen(peer.command)
                                .andThen(done.release)
                        }
                        config <- configFor(server)
                        fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received =>
                            EmailReceive.addFlags(
                                Seq(r.uid),
                                Set(Email.Flag.Flagged)
                            ).andThen(EmailReceive.search(archive, EmailReceive.Search.All)).unit
                        })
                        _    <- Async.race(done.await, fiber.getResult.unit)
                        _    <- fiber.interrupt
                        _    <- server.awaitClose
                        seen <- server.received
                    yield assert(seen.slice(2, 11) == Chunk(
                        s"A3 UID FETCH 1:* $listing",
                        s"A4 UID FETCH 1 $items",
                        "A5 UID STORE 1 +FLAGS.SILENT (\\Flagged)",
                        "A6 SELECT Archive",
                        "A7 UID SEARCH ALL",
                        "A8 SELECT INBOX",
                        s"A9 UID FETCH 2:* $listing",
                        "A10 IDLE"
                    ))
                }
            }
        "a failure of its own ends the loop, reaching the caller as it was raised, and closes the loop's connection" in scripted {
            Scope.run {
                for
                    server <- loopServer(peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(round(peer, (1L, "one")))
                    )
                    config <- configFor(server, Schedule.repeat(5))
                    result <- Abort.run[EmailReceiveFailure | String](receive(config, Start.All(inbox)) { case _: Received =>
                        Abort.fail("stop")
                    })
                    _ <- server.awaitClose
                yield assert(result.equals(Result.fail("stop")))
            }
        }
        "a new UIDVALIDITY when the loop selects its mailbox again is EmailUidValidityChangedException, not retried" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.select(7))
                            .andThen(round(peer, (1L, "one")))
                            .andThen(peer.select(3))
                            .andThen(peer.answer("* SEARCH", "TAG OK SEARCH done"))
                            .andThen(peer.select(8))
                    }
                    config <- configFor(server, Schedule.repeat(5))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received =>
                        EmailReceive.search(archive, EmailReceive.Search.All).unit
                    })
                    _    <- server.awaitClose
                    seen <- server.received
                yield
                    assert(result ==
                        Result.fail(EmailUidValidityChangedException("receive", inbox, validity, EmailLiterals.uidValidityOf(8))))
                    assert(seen.count(_.endsWith("SELECT INBOX")) == 2)
            }
        }
        "a message that does not parse fails only its own message call, and the loop moves on" in scripted {
            val nested = (1 to 101).map(i => s"Content-Type: multipart/mixed; boundary=b$i\r\n\r\n--b$i\r\n").mkString
            Scope.run {
                for
                    outcomes <- Channel.init[Boolean](16)
                    server   <- loopServer { peer =>
                        peer.ready(authenticated = idling)
                            .andThen(peer.select(7))
                            .andThen(peer.answer(listed(1L, 2L)*))
                            .andThen(peer.answer(fetched(Seq(
                                s"* 1 FETCH (UID 1 FLAGS () INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE 1 BODY[] {${nested.length}}",
                                nested + ")"
                            ))*))
                            .andThen(peer.answer(fetched(message(2, 2, "two"))*))
                    }
                    config <- configFor(server)
                    fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received =>
                        Abort.run[EmailParseFailure](r.message).map(parsed => outcomes.put(parsed.isSuccess))
                    })
                    first  <- outcomes.take
                    second <- outcomes.take
                    _      <- fiber.interrupt
                    _      <- server.awaitClose
                yield assert(!first && second)
            }
        }
    }

    "reconnecting" - {
        "a dropped IDLE reconnects and resumes after the last delivered UID, redelivering nothing" in scripted {
            Scope.run {
                for
                    delivered <- Channel.init[Received](16)
                    server    <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling)
                                .andThen(peer.select(7))
                                .andThen(round(peer, (1L, "one")))
                                .andThen(peer.command)
                                .andThen(peer.send("+ idling"))
                                .andThen(peer.line.close)
                        else
                            peer.ready(authenticated = idling)
                                .andThen(peer.select(7))
                                .andThen(round(peer, (2L, "two")))
                    }
                    config <- configFor(server, Schedule.repeat(3))
                    fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case r: Received => delivered.put(r) })
                    first  <- delivered.take
                    second <- delivered.take
                    _      <- fiber.interrupt
                    seen   <- server.received
                yield
                    assert(Chunk(first.uid, second.uid) == Chunk(uid(1), uid(2)))
                    assert(seen.filter(_.endsWith(listing)).take(2) == Chunk(s"A3 UID FETCH 1:* $listing", s"A3 UID FETCH 2:* $listing"))
            }
        }
        "Start.New dropped before any delivery resumes from the first connection's UIDNEXT, not the reconnect's" in scripted {
            Scope.run {
                for
                    relisted <- Latch.init(1)
                    server   <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling).andThen(peer.select(7, uidNext = 10)).andThen(peer.answer(listed()*))
                                .andThen(peer.command).andThen(peer.send("+ idling")).andThen(peer.line.close)
                        else
                            peer.ready(authenticated = idling).andThen(peer.select(7, uidNext = 12)).andThen(peer.answer(listed()*))
                                .andThen(relisted.release)
                    }
                    config <- configFor(server, Schedule.repeat(1))
                    fiber  <- Fiber.initUnscoped(receive(config, Start.New(inbox)) { case _: Received => Kyo.unit })
                    _      <- Async.race(relisted.await, fiber.getResult.unit)
                    _      <- fiber.interrupt
                    seen   <- server.received
                yield assert(seen.filter(_.endsWith(listing)) == Chunk(s"A3 UID FETCH 10:* $listing", s"A3 UID FETCH 10:* $listing"))
            }
        }
        "Start.After across a reconnect resumes after the last delivered UID, not after the start's" in scripted {
            Scope.run {
                for
                    relisted <- Latch.init(1)
                    server   <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(round(peer, (6L, "six")))
                                .andThen(peer.command).andThen(peer.send("+ idling")).andThen(peer.line.close)
                        else
                            peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*))
                                .andThen(relisted.release)
                    }
                    config <- configFor(server, Schedule.repeat(1))
                    uids   <- AtomicRef.init(Chunk.empty[Email.Uid])
                    fiber  <- Fiber.initUnscoped(receive(config, Start.After(uid(5))) { case r: Received =>
                        uids.updateAndGet(_.append(r.uid)).unit
                    })
                    _         <- Async.race(relisted.await, fiber.getResult.unit)
                    _         <- fiber.interrupt
                    delivered <- uids.get
                    seen      <- server.received
                yield
                    assert(delivered == Chunk(uid(6)))
                    assert(seen.filter(_.endsWith(listing)) == Chunk(s"A3 UID FETCH 6:* $listing", s"A3 UID FETCH 7:* $listing"))
            }
        }
        "the loop waits the schedule's delay on kyo's clock before reconnecting" in scripted {
            Scope.run {
                for
                    dropped     <- Latch.init(1)
                    reconnected <- Latch.init(1)
                    server      <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*))
                                .andThen(peer.command).andThen(peer.line.close).andThen(dropped.release)
                        else reconnected.release
                    }
                    // No command deadline, so once the connection drops the schedule's delay is the one sleep armed.
                    config <- configFor(server, Schedule.fixed(30.seconds))
                        .map(c => EmailLiterals.valid(c.commandTimeout(Duration.Infinity)): EmailImapConfig)
                    waiting <- Clock.withTimeControl { control =>
                        Fiber.initUnscoped(receive(config, Start.All(inbox)) { case _: Received => Kyo.unit }).map { fiber =>
                            for
                                _       <- dropped.await.andThen(control.awaitPendingSleepers(1))
                                _       <- control.advance(30.seconds.minusOrZero(1.nanos))
                                pending <- reconnected.pending
                                _       <- control.advance(1.nanos)
                                _       <- reconnected.await
                                _       <- fiber.interrupt
                            yield pending == 1
                        }
                    }
                yield assert(waiting: Boolean)
            }
        }
        "a dropped IDLE with no attempt left is EmailIdleDroppedException" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*))
                            .andThen(peer.command).andThen(peer.send("+ idling")).andThen(peer.line.close)
                    }
                    config <- configFor(server)
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                yield assert(result == Result.fail(EmailIdleDroppedException("receive", "127.0.0.1", inbox, Absent)))
            }
        }
        "a completed round restarts the schedule, so a later drop is retried again" in scripted {
            def dropIdle(peer: Peer) = peer.command.andThen(peer.send("+ idling")).andThen(peer.line.close)
            Scope.run {
                for
                    server <- loopServer { peer =>
                        val opened = peer.ready(authenticated = idling).andThen(peer.select(7))
                        peer.index match
                            case 1 => opened.andThen(round(peer, (1L, "one"))).andThen(dropIdle(peer))
                            case 2 =>
                                opened.andThen(round(peer, (2L, "two"))).andThen(peer.idle("* 3 EXISTS"))
                                    .andThen(peer.answer(listed()*)).andThen(dropIdle(peer))
                            case _ => opened.andThen(round(peer, (3L, "three")))
                        end match
                    }
                    config <- configFor(server, Schedule.repeat(1))
                    uids   <- AtomicRef.init(Chunk.empty[Long])
                    result <- Abort.run[EmailReceiveFailure | String](receive(config, Start.All(inbox)) { case r: Received =>
                        uids.updateAndGet(_.append(r.uid.value)).map(seen => if seen.size == 3 then Abort.fail("done") else ())
                    })
                    seen <- uids.get
                yield
                    assert(result.equals(Result.fail("done")))
                    assert(seen == Chunk(1L, 2L, 3L))
            }
        }
        "a reconnect that fails to connect is retried, and the next one resumes" in scripted {
            def upgraded(peer: Peer) =
                peer.send("* OK [CAPABILITY IMAP4rev1 STARTTLS] ready")
                    .andThen(peer.ok())
                    .andThen(peer.startTls())
                    .andThen(peer.answer("* CAPABILITY IMAP4rev1 AUTH=PLAIN SASL-IR IDLE", "TAG OK done"))
                    .andThen(peer.ok(s"CAPABILITY $idling"))
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        Abort.run[LineConnectionFixture.Failure] {
                            peer.index match
                                case 1 =>
                                    upgraded(peer).andThen(peer.select(7)).andThen(round(peer, (1L, "one")))
                                        .andThen(peer.command).andThen(peer.send("+ idling")).andThen(peer.line.close)
                                // No STARTTLS offered: the reconnect fails with StartTlsUnavailable before any credential.
                                case 2 => peer.send(s"* OK [CAPABILITY $Capabilities] ready")
                                case _ =>
                                    upgraded(peer).andThen(peer.select(7)).andThen(round(peer, (2L, "two")))
                        }.andThen(peer.untilClosed)
                    }
                    config <- ImapTestServer.config(server, byPass, startTls = true).map(_.reconnect(Schedule.repeat(3)))
                    uids   <- AtomicRef.init(Chunk.empty[Email.Uid])
                    result <- Abort.run[EmailReceiveFailure | String](receive(config, Start.All(inbox)) { case r: Received =>
                        uids.updateAndGet(_.append(r.uid)).map(seen => if seen.size == 2 then Abort.fail("done") else ())
                    })
                    seen <- uids.get
                yield
                    assert(result.equals(Result.fail("done")))
                    assert(seen == Chunk(uid(1), uid(2)))
            }
        }
        "a BYE while idling is EmailIdleDroppedException with the server's text, redacted, though the server keeps the connection open" in
            scripted {
                def byeWith(text: String) =
                    Scope.run {
                        for
                            server <- loopServer { peer =>
                                peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*))
                                    .andThen(peer.command).andThen(peer.send("+ idling")).andThen(peer.send(s"* BYE $text"))
                                    .andThen(peer.receive)
                            }
                            config <- configFor(server)
                            result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                        yield result
                    }
                byeWith("shutting down").map { result =>
                    assert(result == Result.fail(EmailIdleDroppedException("receive", "127.0.0.1", inbox, Present("shutting down"))))
                }.andThen(byeWith("no more s3cret-pass").map { result =>
                    assert(result == Result.fail(EmailIdleDroppedException("receive", "127.0.0.1", inbox, Present("no more <redacted>"))))
                })
            }
        "a new UIDVALIDITY after a reconnect is EmailUidValidityChangedException, before anything under it is delivered" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(round(peer, (1L, "one")))
                                .andThen(peer.command).andThen(peer.line.close)
                        else
                            peer.ready(authenticated = idling).andThen(peer.select(8))
                                .andThen(round(peer, (2L, "renumbered")))
                    }
                    config    <- configFor(server, Schedule.repeat(3))
                    delivered <- AtomicRef.init(Chunk.empty[Long])
                    result    <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case r: Received =>
                        delivered.updateAndGet(_.append(r.uid.value)).unit
                    })
                    uids <- delivered.get
                yield
                    assert(result ==
                        Result.fail(EmailUidValidityChangedException("receive", inbox, validity, EmailLiterals.uidValidityOf(8))))
                    assert(uids == Chunk(1L))
            }
        }
        "NO [UNAVAILABLE] is retried, and is the failure once the schedule gives up" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*))
                                .andThen(peer.command).andThen(peer.line.close)
                        else peer.ready(authenticated = idling).andThen(peer.answer("TAG NO [UNAVAILABLE] try later"))
                    }
                    config <- configFor(server, Schedule.repeat(2))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    seen   <- server.received
                yield
                    assert(result == Result.fail(EmailImapCommandException(
                        "receive",
                        "SELECT",
                        EmailImapCommandException.Status.No,
                        Present(EmailReceive.ResponseCode.Unavailable),
                        "try later"
                    )))
                    assert(seen.count(_ == "A2 SELECT INBOX") == 3)
            }
        }
        "any other refusal after a reconnect ends the loop at once, with attempts left" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        if peer.index == 1 then
                            peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*))
                                .andThen(peer.command).andThen(peer.line.close)
                        else peer.ready(authenticated = idling).andThen(peer.answer("TAG NO [NOPERM] denied"))
                    }
                    config <- configFor(server, Schedule.repeat(5))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    seen   <- server.received
                yield
                    assert(result == Result.fail(EmailImapCommandException(
                        "receive",
                        "SELECT",
                        EmailImapCommandException.Status.No,
                        Present(EmailReceive.ResponseCode.NoPerm),
                        "denied"
                    )))
                    assert(seen.count(_ == "A2 SELECT INBOX") == 2)
            }
        }
        "a transport failure outside IDLE is retried, and is the failure once the schedule gives up" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.command).andThen(peer.send("?? what"))
                    }
                    config <- configFor(server, Schedule.repeat(1))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    seen   <- server.received
                yield
                    result match
                        case Result.Failure(ex: EmailTransportException) =>
                            assert(ex.method == "receive" && ex.kind == Kind.Protocol("?? what"))
                        case other => fail(other.toString)
                    end match
                    assert(seen.count(_.startsWith("A3 UID FETCH")) == 2)
            }
        }
        "a command that times out outside IDLE is retried, and is the failure once the schedule gives up, on kyo's clock" in scripted {
            Scope.run {
                for
                    asked  <- Kyo.fill(2)(Latch.init(1))
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.command)
                            .andThen(asked(peer.index - 1).release).andThen(peer.receive)
                    }
                    config <- configFor(server, Schedule.repeat(1))
                    // The clock moves only while a FETCH is known to be waiting, so no connect or SELECT can expire instead.
                    result <- Clock.withTimeControl { control =>
                        Fiber.initUnscoped(Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received =>
                            Kyo.unit
                        })).map { fiber =>
                            asked(0).await
                                .andThen(control.awaitPendingSleepers(1))
                                .andThen(control.advance(config.commandTimeout))
                                .andThen(asked(1).await)
                                .andThen(control.awaitPendingSleepers(1))
                                .andThen(control.advance(config.commandTimeout))
                                .andThen(fiber.get)
                        }
                    }
                    seen <- server.received
                yield
                    result match
                        case Result.Failure(ex: EmailTransportException) =>
                            assert(ex.method == "receive" && ex.kind == Kind.Timeout && ex.timeout == Present(config.commandTimeout))
                        case other => fail(other.toString)
                    end match
                    assert(seen.count(_.startsWith("A3 UID FETCH")) == 2)
            }
        }
        "the token computation's failure is EmailTokenException on the loop's row, and is never retried" in scripted {
            Scope.run {
                for
                    server <- loopServer(peer => peer.ready(authenticated = idling))
                    runs   <- AtomicInt.init
                    revoked = EmailTokenException("the grant was revoked")
                    config <- ImapTestServer.config(
                        server,
                        EmailLiterals.oauth2AccountOf(User, runs.incrementAndGet.andThen(Abort.fail(revoked)))
                    )
                        .map(_.reconnect(Schedule.repeat(5)))
                    result      <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    computed    <- runs.get
                    connections <- server.connections
                yield
                    assert(result == Result.fail(revoked))
                    assert(computed == 1 && connections == 0)
            }
        }
        "a token failure on a reconnect, after the first session selected its mailbox, ends the loop without a retry" in scripted {
            Scope.run {
                for
                    server <- loopServer(peer =>
                        peer.ready(capabilities = "IMAP4rev1 AUTH=XOAUTH2 SASL-IR", authenticated = idling).andThen(peer.select(7))
                            .andThen(peer.answer(listed()*)).andThen(peer.command).andThen(peer.line.close)
                    )
                    runs <- AtomicInt.init
                    revoked = EmailTokenException("the grant was revoked")
                    token   = runs.incrementAndGet.map(n => if n == 1 then EmailLiterals.tokenOf("first") else Abort.fail(revoked))
                    config <- ImapTestServer.config(server, EmailLiterals.oauth2AccountOf(User, token)).map(_.reconnect(Schedule.repeat(5)))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    computed    <- runs.get
                    connections <- server.connections
                yield
                    assert(result == Result.fail(revoked), result.toString)
                    assert(computed == 2 && connections == 1, s"computed $computed, connections $connections")
            }
        }
        "verbs the handler calls run on the loop's connection, not the run's own session" in scripted {
            Scope.run {
                for
                    server <- loopServer { peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(round(peer, (1L, "one")))
                            .andThen(peer.answer("* STATUS INBOX (MESSAGES 1 UNSEEN 0 UIDNEXT 2 UIDVALIDITY 7)", "TAG OK STATUS done"))
                    }
                    config <- configFor(server)
                    result <- Abort.run[EmailReceiveFailure | EmailStatusFailure | String](receive(config, Start.All(inbox)) {
                        case _: Received =>
                            EmailReceive.status(inbox).map(status => Abort.fail(s"messages ${status.messages}"))
                    })
                    connections <- server.connections
                    seen        <- server.received
                yield
                    assert(result.equals(Result.fail("messages 1")))
                    assert(connections == 1)
                    assert(seen.count(_.startsWith("A1 AUTHENTICATE")) == 1)
            }
        }
    }

    "failures on the first connection are not retried" - {
        // The failure of a loop that had five attempts left, after asserting the server saw one connection.
        def first(script: Peer => Any < (Async & Abort[LineConnectionFixture.Failure]))(using
            Frame,
            kyo.test.AssertScope
        ): Result[EmailReceiveFailure, Unit] < (Async & Abort[NetException]) =
            Scope.run {
                for
                    server      <- loopServer(script)
                    config      <- configFor(server, Schedule.repeat(5))
                    result      <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    connections <- server.connections
                yield
                    assert(connections == 1, s"$connections connections")
                    result
            }
        "a failure retried on a reconnect fails the first connection at once, with attempts left" in scripted {
            Scope.run {
                for
                    server <- loopServer(peer => peer.ready(authenticated = idling).andThen(peer.answer("TAG NO [UNAVAILABLE] try later")))
                    config <- configFor(server, Schedule.repeat(5))
                    result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    seen   <- server.received
                yield
                    assert(result == Result.fail(EmailImapCommandException(
                        "receive",
                        "SELECT",
                        EmailImapCommandException.Status.No,
                        Present(EmailReceive.ResponseCode.Unavailable),
                        "try later"
                    )))
                    assert(seen.count(_ == "A2 SELECT INBOX") == 1)
            }
        }
        "a refused connection is Connect" in scripted {
            for
                server <- Scope.run(serve()(_ => ()))
                config <- configFor(server, Schedule.repeat(5))
                result <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
            yield result match
                case Result.Failure(ex: EmailConnectException) =>
                    assert(ex.kind == EmailConnectException.Kind.Connect && ex.method == "receive")
                case other => fail(other.toString)
        }
        "a STARTTLS server that does not offer the upgrade is StartTlsUnavailable, with no credential sent" in scripted {
            Scope.run {
                for
                    server      <- serve(tls = false)(peer => peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.untilClosed))
                    config      <- ImapTestServer.config(server, byPass, startTls = true).map(_.reconnect(Schedule.repeat(5)))
                    result      <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    _           <- server.awaitClose
                    seen        <- server.received
                    connections <- server.connections
                yield
                    result match
                        case Result.Failure(ex: EmailConnectException) =>
                            assert(ex.kind == EmailConnectException.Kind.StartTlsUnavailable && ex.method == "receive")
                        case other => fail(other.toString)
                    end match
                    assert(seen.isEmpty)
                    assert(connections == 1)
            }
        }
        "a certificate for another host is Tls, and the first connection's failure is not retried" in scripted {
            Scope.run {
                for
                    server <- serve(tls = false) { peer =>
                        peer.send(s"* OK [CAPABILITY IMAP4rev1 STARTTLS] ready")
                            .andThen(peer.ok())
                            .andThen(peer.startTls(Certificate.WrongHost))
                    }
                    config      <- ImapTestServer.config(server, byPass, startTls = true).map(_.reconnect(Schedule.repeat(5)))
                    result      <- Abort.run[EmailReceiveFailure](receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    connections <- server.connections
                yield
                    result match
                        case Result.Failure(ex: EmailConnectException) =>
                            assert(ex.kind == EmailConnectException.Kind.Tls && ex.method == "receive")
                        case other => fail(other.toString)
                    end match
                    assert(connections == 1)
            }
        }
        "a refused authentication is EmailAuthenticationException" in scripted {
            first(peer =>
                peer.send(s"* OK [CAPABILITY $Capabilities] ready").andThen(peer.answer("TAG NO [AUTHENTICATIONFAILED] no"))
            ).map {
                result =>
                    assert(result == Result.fail(EmailAuthenticationException(
                        "receive",
                        "127.0.0.1",
                        User,
                        Email.Auth.Mechanism.Plain,
                        EmailAuthenticationException.Reply.Imap(Present(EmailReceive.ResponseCode.AuthenticationFailed), "no")
                    )))
            }
        }
        "no usable mechanism is EmailAuthMechanismUnavailableException" in scripted {
            first(peer => peer.send("* OK [CAPABILITY IMAP4rev1 LOGINDISABLED] ready")).map { result =>
                assert(result == Result.fail(EmailAuthMechanismUnavailableException(
                    "receive",
                    EmailException.Protocol.Imap,
                    "127.0.0.1",
                    Chunk(Email.Auth.Mechanism.Plain, Email.Auth.Mechanism.Login),
                    Chunk.empty
                )))
            }
        }
        "SELECT answered NO [NOPERM] is EmailImapCommandException, and NO [NONEXISTENT] EmailMailboxNotFoundException" in scripted {
            first(peer => peer.ready(authenticated = idling).andThen(peer.answer("TAG NO [NOPERM] denied"))).map { result =>
                assert(result == Result.fail(EmailImapCommandException(
                    "receive",
                    "SELECT",
                    EmailImapCommandException.Status.No,
                    Present(EmailReceive.ResponseCode.NoPerm),
                    "denied"
                )))
            }.andThen {
                first(peer => peer.ready(authenticated = idling).andThen(peer.answer("TAG NO [NONEXISTENT] none"))).map { result =>
                    assert(result == Result.fail(EmailMailboxNotFoundException("receive", inbox)))
                }
            }
        }
        "a close with and without BYE is ConnectionClosed" in scripted {
            first(peer => peer.ready(authenticated = idling).andThen(peer.command).andThen(peer.send("* BYE going away"))).map {
                case Result.Failure(ex: EmailTransportException) =>
                    assert(ex.kind == Kind.ConnectionClosed(Present("going away")) && ex.method == "receive")
                case other => fail(other.toString)
            }.andThen {
                first(peer => peer.ready(authenticated = idling).andThen(peer.command).andThen(peer.line.close)).map {
                    case Result.Failure(ex: EmailTransportException) => assert(ex.kind == Kind.ConnectionClosed(Absent))
                    case other                                       => fail(other.toString)
                }
            }
        }
    }

    "ending" - {
        "interrupting the loop closes its connection, which the server sees" in scripted {
            Scope.run {
                for
                    idle   <- Latch.init(1)
                    server <- loopServer(peer =>
                        peer.ready(authenticated = idling).andThen(peer.select(7)).andThen(peer.answer(listed()*)).andThen(
                            peer.command
                        ).andThen(peer.send("+ idling")).andThen(idle.release).andThen(peer.receive)
                    )
                    config <- configFor(server)
                    fiber  <- Fiber.initUnscoped(receive(config, Start.All(inbox)) { case _: Received => Kyo.unit })
                    _      <- idle.await
                    _      <- fiber.interrupt
                    _      <- server.awaitClose
                yield succeed
            }
        }
    }

end EmailReceiveLoopTest
