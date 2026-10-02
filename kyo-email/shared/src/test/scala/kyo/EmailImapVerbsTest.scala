package kyo

import kyo.EmailTransportException.Kind
import kyo.internal.email.imap.ImapTestServer
import kyo.internal.email.imap.ImapTestServer.*
import kyo.internal.email.net.LineConnectionFixture
import kyo.internal.email.net.LineConnectionFixture.scripted
import kyo.net.NetException

class EmailImapVerbsTest extends kyo.test.Test[Any]:

    private val secret = "s3cret-pass"
    private val byPass = EmailLiterals.passwordAuthOf(User, EmailLiterals.passwordOf(secret))

    // Longer than the 200 octets a Protocol failure shows, in the quoted form a LOGIN line carries it.
    private val longSecret = "hunter2-\"q\"-" * 20
    private val longPass   = EmailLiterals.passwordAuthOf(User, EmailLiterals.passwordOf(longSecret))
    private val longQuoted = "\"" + longSecret.replace("\"", "\\\"") + "\""
    private val inbox      = EmailLiterals.mailboxOf("INBOX")
    private val archive    = EmailLiterals.mailboxOf("Archive")
    private val validity   = EmailLiterals.uidValidityOf(7)

    private def uid(n: Long, mailbox: Email.MailboxName = inbox, v: Long = 7): Email.Uid =
        EmailLiterals.uidOf(mailbox, EmailLiterals.uidValidityOf(v), n)

    private type Verb[A] = A < (Async & Abort[EmailException] & Env[EmailImap])

    // Opens a session to a server that authenticates it and then runs `script`, runs `verb`, and answers with its outcome and every line
    // the server received after the authentication.
    private def session[A](
        script: Peer => Any < (Async & Abort[LineConnectionFixture.Failure]),
        authenticated: String = "IMAP4rev1",
        auth: Email.Auth[Nothing] = byPass,
        configure: EmailImapConfig[Nothing] => EmailImapConfig[Nothing] = identity
    )(
        verb: Verb[A]
    )(using Frame): (Result[EmailException, A], Chunk[String]) < (Async & Abort[NetException]) =
        Scope.run {
            for
                server <- serve() { peer =>
                    peer.ready(authenticated = authenticated)
                        .andThen(Abort.run[LineConnectionFixture.Failure](script(peer)))
                        .andThen(peer.untilClosed)
                }
                config <- ImapTestServer.config(server, auth).map(configure)
                result <- Abort.run[EmailException](EmailImap.let(config)(verb))
                _      <- server.awaitClose
                seen   <- server.received
            yield (result, seen.drop(1))
        }

    private def succeeded[A](outcome: (Result[EmailException, A], Chunk[String])): A =
        outcome._1 match
            case Result.Success(value) => value
            case other                 => throw new IllegalStateException(s"expected success: $other")

    private def failed[A](outcome: (Result[EmailException, A], Chunk[String])): EmailException =
        outcome._1 match
            case Result.Failure(ex) => ex
            case other              => throw new IllegalStateException(s"expected a failure: $other")

    // A FETCH response carrying a summary's items: its header section as a literal, then a single-part body structure.
    private def summary(sequence: Int, n: Long, subject: String): Seq[String] =
        val header = s"Subject: $subject\r\n\r\n"
        Seq(
            s"* $sequence FETCH (UID $n FLAGS (\\Seen) INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE 44 BODY[HEADER] {${header.length}}",
            header + " BODYSTRUCTURE (\"TEXT\" \"PLAIN\" NIL NIL NIL \"7BIT\" 3 1))"
        )
    end summary

    private def literal(prefix: String, content: String, suffix: String = ")"): Seq[String] =
        Seq(s"$prefix {${content.getBytes("UTF-8").length}}", content + suffix)

    private val structure =
        "* 1 FETCH (UID 2 BODYSTRUCTURE ((\"TEXT\" \"PLAIN\" NIL NIL NIL \"QUOTED-PRINTABLE\" 10 1)" +
            "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL \"BASE64\" 8 NIL (\"ATTACHMENT\" (\"FILENAME\" \"a.bin\")) NIL NIL)" +
            "(\"APPLICATION\" \"OCTET-STREAM\" NIL NIL NIL \"X-UUENCODE\" 8 NIL NIL NIL NIL) \"MIXED\"))"

    "listMailboxes" - {
        "every mailbox, names decoded from modified UTF-7, attributes read" in scripted {
            session(peer =>
                peer.answer(
                    "* LIST (\\HasNoChildren) \"/\" INBOX",
                    "* LIST (\\HasNoChildren \\Sent) \"/\" \"Sent Items\"",
                    "* LIST () \"/\" Entw&APw-rfe",
                    "TAG OK LIST done"
                )
            )(EmailImap.listMailboxes).map { outcome =>
                assert(succeeded(outcome) == Chunk(
                    EmailImap.Mailbox(inbox, Present('/'), Set(EmailImap.Mailbox.Attribute.HasNoChildren)),
                    EmailImap.Mailbox(
                        EmailLiterals.mailboxOf("Sent Items"),
                        Present('/'),
                        Set(EmailImap.Mailbox.Attribute.HasNoChildren, EmailImap.Mailbox.Attribute.Sent)
                    ),
                    EmailImap.Mailbox(EmailLiterals.mailboxOf("Entwürfe"), Present('/'), Set.empty)
                ))
                assert(outcome._2 == Chunk("A2 LIST \"\" \"*\"", "A3 LOGOUT"))
            }
        }
        "asks for special uses when the server has SPECIAL-USE (RFC 6154)" in scripted {
            session(peer => peer.answer("TAG OK LIST done"), authenticated = "IMAP4rev1 SPECIAL-USE")(EmailImap.listMailboxes).map {
                outcome => assert(outcome._2.head == "A2 LIST \"\" \"*\" RETURN (SPECIAL-USE)")
            }
        }
        "names are UTF-8 on a server that speaks only IMAP4rev2" in scripted {
            session(peer => peer.answer("* LIST () \"/\" \"Entwürfe\"", "TAG OK LIST done"), authenticated = "IMAP4rev2")(
                EmailImap.listMailboxes
            ).map(outcome => assert(succeeded(outcome).map(_.name.value) == Chunk("Entwürfe")))
        }
        "LIST answered NO is EmailImapCommandException" in scripted {
            session(peer => peer.answer("TAG NO denied"))(EmailImap.listMailboxes).map { outcome =>
                assert(failed(outcome) == EmailImapCommandException(
                    "listMailboxes",
                    "LIST",
                    EmailImapCommandException.Status.No,
                    Absent,
                    "denied"
                ))
            }
        }
        "a name that is not canonical modified UTF-7 is listed verbatim and addressed by the octets the server sent" in scripted {
            val status = "(MESSAGES UNSEEN UIDNEXT UIDVALIDITY)"
            session(peer =>
                peer.answer("* LIST () \"/\" Tom&Jerry", "* LIST () \"/\" Archive", "TAG OK LIST done")
                    .andThen(peer.answer("* STATUS Tom&Jerry (MESSAGES 1 UNSEEN 0 UIDNEXT 2 UIDVALIDITY 7)", "TAG OK STATUS done"))
                    .andThen(peer.answer("TAG NO [NONEXISTENT] no such mailbox"))
            )(
                for
                    listed <- EmailImap.listMailboxes
                    found  <- EmailImap.status(listed.head.name)
                    typed  <- Abort.run[EmailException](EmailImap.status(EmailLiterals.mailboxOf("Tom&Jerry")))
                yield (listed, found, typed)
            ).map { outcome =>
                val (listed, found, typed) = succeeded(outcome)
                assert(listed.map(m => (m.name.value, m.name.verbatim)) == Chunk(("Tom&Jerry", true), ("Archive", false)))
                assert(listed.head.name != EmailLiterals.mailboxOf("Tom&Jerry"))
                assert(found.messages == 1L)
                assert(typed == Result.fail(EmailMailboxNotFoundException("status", EmailLiterals.mailboxOf("Tom&Jerry"))))
                assert(outcome._2.slice(1, 3) == Chunk(s"A3 STATUS Tom&Jerry $status", s"A4 STATUS Tom&-Jerry $status"))
            }
        }
    }

    "status" - {
        "a completion without the STATUS response, or a SELECT without UIDVALIDITY, is Incomplete naming what is missing" in scripted {
            session(peer => peer.answer("TAG OK STATUS done"))(EmailImap.status(inbox)).map { outcome =>
                session(peer => peer.answer("* 3 EXISTS", "TAG OK [READ-WRITE] selected"))(EmailImap.search(
                    inbox,
                    EmailImap.Search.All
                )).map {
                    selected =>
                        Chunk(failed(outcome), failed(selected)) match
                            case Chunk(a: EmailTransportException, b: EmailTransportException) =>
                                assert(a.kind == Kind.Incomplete("STATUS") && a.method == "status")
                                assert(b.kind == Kind.Incomplete("UIDVALIDITY") && b.method == "search")
                            case other => fail(other.toString)
                }
            }
        }
        "status response text ending like a literal marker is text, since its grammar has no literal (RFC 9051 section 9)" in scripted {
            session(peer =>
                peer.answer(
                    "* OK Quota {5}",
                    "* STATUS INBOX (MESSAGES 4 UNSEEN 1 UIDNEXT 10 UIDVALIDITY 7)",
                    "TAG OK STATUS done {3}"
                )
            )(EmailImap.status(inbox)).map(outcome => assert(succeeded(outcome).messages == 4L))
        }
        "the counts, the name encoded as the server reads it" in scripted {
            session(peer =>
                peer.answer("* STATUS \"Sent Items\" (MESSAGES 4 UNSEEN 1 UIDNEXT 10 UIDVALIDITY 7)", "TAG OK STATUS done")
                    .andThen(peer.answer("* STATUS Entw&APw-rfe (MESSAGES 0 UNSEEN 0 UIDNEXT 1 UIDVALIDITY 9)", "TAG OK STATUS done"))
            )(EmailImap.status(EmailLiterals.mailboxOf("Sent Items")).map(s =>
                EmailImap.status(EmailLiterals.mailboxOf("Entwürfe")).map((s, _))
            ))
                .map { outcome =>
                    assert(succeeded(outcome)._1 == EmailImap.MailboxStatus(EmailLiterals.mailboxOf("Sent Items"), 4, 1, 10, validity))
                    assert(succeeded(outcome)._2.uidValidity == EmailLiterals.uidValidityOf(9))
                    assert(outcome._2.take(2) == Chunk(
                        "A2 STATUS \"Sent Items\" (MESSAGES UNSEEN UIDNEXT UIDVALIDITY)",
                        "A3 STATUS Entw&APw-rfe (MESSAGES UNSEEN UIDNEXT UIDVALIDITY)"
                    ))
                }
        }
        "NO [NONEXISTENT] is EmailMailboxNotFoundException, any other NO EmailImapCommandException" in scripted {
            session(peer => peer.answer("TAG NO [NONEXISTENT] no such mailbox"))(EmailImap.status(archive)).map { outcome =>
                assert(failed(outcome) == EmailMailboxNotFoundException("status", archive))
            }.andThen {
                session(peer => peer.answer("TAG NO [NOPERM] denied"))(EmailImap.status(archive)).map { outcome =>
                    assert(failed(outcome) == EmailImapCommandException(
                        "status",
                        "STATUS",
                        EmailImapCommandException.Status.No,
                        Present(EmailImap.ResponseCode.NoPerm),
                        "denied"
                    ))
                }
            }
        }
    }

    "search" - {
        "selects the mailbox once, then answers the UIDs ascending" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer("* SEARCH 5 3", "TAG OK SEARCH done"))
                    .andThen(peer.answer("* SEARCH", "TAG OK SEARCH done"))
            )(EmailImap.search(inbox, EmailImap.Search.All).map(all =>
                EmailImap.search(inbox, EmailImap.Search.Unseen).map((all, _))
            )).map {
                outcome =>
                    assert(succeeded(outcome) == (Chunk(uid(3), uid(5)), Chunk.empty))
                    assert(outcome._2 == Chunk("A2 SELECT INBOX", "A3 UID SEARCH ALL", "A4 UID SEARCH UNSEEN", "A5 LOGOUT"))
            }
        }
        "an ESEARCH set counts against maxResponseLength as the SEARCH listing of its UIDs would, and past it is Protocol" in scripted {
            val line = "* ESEARCH UID ALL 1:100000"
            session(
                peer => peer.select(7).andThen(peer.answer(line, "TAG OK SEARCH done")),
                configure = c => EmailLiterals.valid(c.maxResponseLength(4096.bytes))
            )(
                EmailImap.search(inbox, EmailImap.Search.All)
            ).map { outcome =>
                failed(outcome) match
                    case ex: EmailTransportException => assert(ex.kind == Kind.Protocol(line))
                    case other                       => fail(other.toString)
            }
        }
        "an ESEARCH range outside the UIDs, or past the default maxResponseLength, is Protocol before it is expanded" in scripted {
            Kyo.foreach(Chunk("* ESEARCH UID ALL 0:5", "* ESEARCH UID ALL 1:4294967295")) { line =>
                session(peer => peer.select(7).andThen(peer.answer(line, "TAG OK SEARCH done")))(EmailImap.search(
                    inbox,
                    EmailImap.Search.All
                ))
                    .map(outcome => (line, failed(outcome)))
            }.map { outcomes =>
                outcomes.foreach {
                    case (line, ex: EmailTransportException) => assert(ex.kind == Kind.Protocol(line))
                    case (_, other)                          => fail(other.toString)
                }
                succeed
            }
        }
        "reads an ESEARCH answer (RFC 4731)" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("* ESEARCH (TAG \"A3\") UID ALL 8:9,4", "TAG OK SEARCH done")))(
                EmailImap.search(inbox, EmailImap.Search.All)
            ).map(outcome => assert(succeeded(outcome) == Chunk(uid(4), uid(8), uid(9))))
        }
        "UIDs across SEARCH and ESEARCH lines are answered once each, ascending; a SEARCH number outside the UIDs is Protocol" in scripted {
            session(peer =>
                peer.select(7).andThen(peer.answer("* SEARCH 9 2 9", "* ESEARCH UID ALL 8:9,1:3", "* SEARCH 3", "TAG OK SEARCH done"))
            )(EmailImap.search(inbox, EmailImap.Search.All)).map { outcome =>
                assert(succeeded(outcome) == Chunk(uid(1), uid(2), uid(3), uid(8), uid(9)))
            }.andThen {
                session(peer => peer.select(7).andThen(peer.answer("* SEARCH 2 4294967296", "TAG OK SEARCH done")))(
                    EmailImap.search(inbox, EmailImap.Search.All)
                ).map { outcome =>
                    failed(outcome) match
                        case ex: EmailTransportException => assert(ex.kind == Kind.Protocol("* SEARCH 2 4294967296"))
                        case other                       => fail(other.toString)
                }
            }
        }
        "text that is not ASCII is a literal under CHARSET UTF-8" in scripted {
            session(
                peer => peer.select(7).andThen(peer.command).andThen(peer.receive).andThen(peer.send("A3 OK SEARCH done")),
                authenticated = "IMAP4rev1 LITERAL+"
            )(EmailImap.search(inbox, EmailImap.Search.Body("café"))).map { outcome =>
                assert(outcome._2.take(3) == Chunk("A2 SELECT INBOX", "A3 UID SEARCH CHARSET UTF-8 BODY {5+}", "café"))
            }
        }
        "CHARSET UTF-8 answered NO or BAD [BADCHARSET] is EmailCapabilityMissingException for UTF-8 search" in scripted {
            Kyo.foreachDiscard(Seq("NO [BADCHARSET (US-ASCII)] charset", "BAD [BADCHARSET] charset")) { answer =>
                session(
                    peer => peer.select(7).andThen(peer.command).andThen(peer.receive).andThen(peer.send(s"A3 $answer")),
                    authenticated = "IMAP4rev1 LITERAL+"
                )(EmailImap.search(inbox, EmailImap.Search.Body("café"))).map { outcome =>
                    assert(
                        failed(outcome) ==
                            EmailCapabilityMissingException(
                                "search",
                                "127.0.0.1",
                                EmailCapabilityMissingException.Capability.ImapUtf8Search
                            ),
                        answer
                    )
                }
            }.andThen(succeed)
        }
        "an ASCII search, sent with no CHARSET, answered BAD [BADCHARSET] is EmailImapCommandException" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG BAD [BADCHARSET (US-ASCII)] charset")))(
                EmailImap.search(inbox, EmailImap.Search.All)
            ).map { outcome =>
                assert(failed(outcome) == EmailImapCommandException(
                    "search",
                    "SEARCH",
                    EmailImapCommandException.Status.Bad,
                    Present(EmailImap.ResponseCode.BadCharset(Chunk("US-ASCII"))),
                    "charset"
                ))
            }
        }
        "a SELECT answered NO [NONEXISTENT] is EmailMailboxNotFoundException" in scripted {
            session(peer => peer.answer("TAG NO [NONEXISTENT] no such mailbox"))(EmailImap.search(archive, EmailImap.Search.All)).map {
                outcome => assert(failed(outcome) == EmailMailboxNotFoundException("search", archive))
            }
        }
    }

    "fetch" - {
        "summaries per mailbox in UID order, the mailboxes in the order their first UID appears, nothing marked seen" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer(summary(1, 2, "two") ++ summary(2, 4, "four") :+ "TAG OK FETCH done"*))
                    .andThen(peer.select(7))
                    .andThen(peer.answer(summary(1, 9, "nine") :+ "TAG OK FETCH done"*))
            )(EmailImap.fetch(Seq(uid(4), uid(9, archive), uid(2)))).map { outcome =>
                val summaries = succeeded(outcome)
                assert(summaries.map(_.uid) == Chunk(uid(2), uid(4), uid(9, archive)))
                assert(summaries.map(_.header.subject) == Chunk("two", "four", "nine"))
                assert(summaries.head.flags == Set[Email.Flag](Email.Flag.Seen))
                assert(summaries.head.internalDate == EmailLiterals.instantOf("1996-07-17T09:44:25Z"))
                assert(summaries.head.size == 44L.bytes)
                assert(summaries.head.structure.path == Chunk(1))
                val items = "(UID FLAGS INTERNALDATE RFC822.SIZE BODY.PEEK[HEADER] BODYSTRUCTURE)"
                assert(outcome._2.take(4) ==
                    Chunk("A2 SELECT INBOX", s"A3 UID FETCH 2,4 $items", "A4 SELECT Archive", s"A5 UID FETCH 9 $items"))
            }
        }
        "a flag that is not an IMAP atom is Other, a structure type that is not a token is text/plain; charset=us-ascii, and it round-trips" in
            scripted {
                val header = "Subject: odd\r\n\r\n"
                session(peer =>
                    peer.select(7).andThen(peer.answer(
                        s"* 1 FETCH (UID 2 FLAGS (\\Seen $$a%b) INTERNALDATE \"17-Jul-1996 02:44:25 -0700\" RFC822.SIZE 44 BODY[HEADER] {${header.length}}",
                        header + " BODYSTRUCTURE (\"TEXT\" \"PL AIN\" (\"CHARSET\" \"UTF-8\") NIL NIL \"7BIT\" 3 1))",
                        "TAG OK FETCH done"
                    ))
                )(EmailImap.fetch(Seq(uid(2)))).map { outcome =>
                    val summary = succeeded(outcome).head
                    assert(summary.flags.map(_.wire) == Set("\\Seen", "$a%b"))
                    assert(summary.flags.exists(flag => flag.isInstanceOf[Email.Flag.Other] && flag.wire == "$a%b"))
                    assert(summary.structure.mediaType == EmailLiterals.mediaTypeOf("text", "plain", "charset" -> "us-ascii"))
                    assert(Json.decode[EmailImap.Summary](Json.encode(summary)) == Result.succeed(summary))
                }
            }
        "an expunged UID has no summary, nor has a UID asked for whose only FETCH is a flag update, and a UID not asked for is ignored" in
            scripted {
                session(peer =>
                    peer.select(7).andThen(peer.answer(
                        "* 3 FETCH (UID 3 FLAGS (\\Seen))" +: "* 4 FETCH (UID 77 FLAGS ())" +: summary(1, 2, "two") :+ "TAG OK FETCH done"*
                    ))
                )(EmailImap.fetch(Seq(uid(2), uid(3), uid(5)))).map(outcome => assert(succeeded(outcome).map(_.uid) == Chunk(uid(2))))
            }
        "an unsolicited flag update for a UID asked for, as RFC 9051 7.5.2 has it carry the UID, gives the summary its current flags" in
            scripted {
                val update = "* 1 FETCH (UID 2 FLAGS (\\Seen \\Flagged))"
                session(peer => peer.select(7).andThen(peer.answer(summary(1, 2, "two") :+ update :+ "TAG OK FETCH done"*)))(
                    EmailImap.fetch(Seq(uid(2)))
                ).map { after =>
                    session(peer => peer.select(7).andThen(peer.answer(update +: summary(1, 2, "two") :+ "TAG OK FETCH done"*)))(
                        EmailImap.fetch(Seq(uid(2)))
                    ).map { before =>
                        assert(succeeded(after).map(s => (s.header.subject, s.flags)) ==
                            Chunk(("two", Set[Email.Flag](Email.Flag.Seen, Email.Flag.Flagged))))
                        assert(succeeded(before).map(s => (s.header.subject, s.flags)) == Chunk(("two", Set[Email.Flag](Email.Flag.Seen))))
                    }
                }
            }
        "two FETCH responses for one UID give one summary, from the first" in scripted {
            session(peer => peer.select(7).andThen(peer.answer(summary(1, 2, "first") ++ summary(1, 2, "second") :+ "TAG OK FETCH done"*)))(
                EmailImap.fetch(Seq(uid(2)))
            ).map(outcome => assert(succeeded(outcome).map(_.header.subject) == Chunk("first")))
        }
        "a UID from an earlier UIDVALIDITY is EmailUidValidityChangedException, before the FETCH is sent" in scripted {
            session(peer => peer.select(8))(EmailImap.fetch(Seq(uid(2)))).map { outcome =>
                assert(failed(outcome) == EmailUidValidityChangedException("fetch", inbox, validity, EmailLiterals.uidValidityOf(8)))
                assert(outcome._2 == Chunk("A2 SELECT INBOX", "A3 LOGOUT"))
            }
        }
        "a UIDVALIDITY the server announces during a command makes the next verb select again" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer("* OK [UIDVALIDITY 8] renumbered", "TAG OK SEARCH done"))
                    .andThen(peer.select(8))
            )(EmailImap.search(inbox, EmailImap.Search.All).andThen(EmailImap.fetch(Seq(uid(2))))).map { outcome =>
                assert(failed(outcome) == EmailUidValidityChangedException("fetch", inbox, validity, EmailLiterals.uidValidityOf(8)))
                assert(outcome._2.take(3) == Chunk("A2 SELECT INBOX", "A3 UID SEARCH ALL", "A4 SELECT INBOX"))
            }
        }
        "UID FETCH answered NO is EmailImapCommandException" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG NO failed")))(EmailImap.fetch(Seq(uid(2)))).map { outcome =>
                assert(failed(outcome) ==
                    EmailImapCommandException("fetch", "FETCH", EmailImapCommandException.Status.No, Absent, "failed"))
            }
        }
        "no UIDs sends nothing" in scripted {
            session(_ => ())(EmailImap.fetch(Seq.empty)).map { outcome =>
                assert(succeeded(outcome).isEmpty && outcome._2 == Chunk("A2 LOGOUT"))
            }
        }
    }

    "fetchMessage" - {
        "the whole message, parsed, without marking it seen" in scripted {
            val message = "From: ada@example.com\r\nSubject: hello\r\n\r\nbody\r\n"
            session(peer => peer.select(7).andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[]", message) :+ "TAG OK FETCH done"*)))(
                EmailImap.fetchMessage(uid(2))
            ).map { outcome =>
                assert(succeeded(outcome).subject == "hello")
                assert(succeeded(outcome).text == "body\n")
                assert(outcome._2(1) == "A3 UID FETCH 2 (UID BODY.PEEK[])")
            }
        }
        "an expunged UID is EmailMessageNotFoundException" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG OK FETCH done")))(EmailImap.fetchMessage(uid(2))).map { outcome =>
                assert(failed(outcome) == EmailMessageNotFoundException("fetchMessage", uid(2)))
            }
        }
        "an unsolicited flag update for the UID before its data is passed over" in scripted {
            val message = "Subject: hello\r\n\r\nbody\r\n"
            session(peer =>
                peer.select(7).andThen(peer.answer(
                    "* 1 FETCH (UID 2 FLAGS (\\Seen))" +: literal("* 1 FETCH (UID 2 BODY[]", message) :+ "TAG OK FETCH done"*
                ))
            )(EmailImap.fetchMessage(uid(2))).map(outcome => assert(succeeded(outcome).subject == "hello"))
        }
        "a response off the grammar that echoes a long password shows no fragment of it" in scripted {
            session(
                peer => peer.select(7).andThen(peer.answer(s"* 1 FETCH (UID 2 X $longQuoted BODY[] 5)", "TAG OK FETCH done")),
                auth = longPass
            )(EmailImap.fetchMessage(uid(2))).map { outcome =>
                failed(outcome) match
                    case ex: EmailTransportException =>
                        assert(!Seq(ex.kind.toString, ex.getMessage, ex.toString).exists(t => longQuoted.sliding(20).exists(t.contains)))
                        assert(ex.kind == Kind.Protocol("* 1 FETCH (UID 2 X <redacted> BODY[] 5)"))
                    case other => fail(other.toString)
            }
        }
        "a message nested past the parser's limit is EmailMimeException" in scripted {
            val nested = (1 to 101).map(i => s"Content-Type: multipart/mixed; boundary=b$i\r\n\r\n--b$i\r\n").mkString
            session(peer => peer.select(7).andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[]", nested) :+ "TAG OK FETCH done"*)))(
                EmailImap.fetchMessage(uid(2))
            ).map { outcome =>
                failed(outcome) match
                    case ex: EmailMimeException => assert(ex.problem == EmailMimeException.Problem.NestingTooDeep(100))
                    case other                  => fail(other.toString)
            }
        }
    }

    "fetchPart" - {
        "unsolicited flag updates for the UID before its body structure and before its part are passed over" in scripted {
            val update = "* 1 FETCH (UID 2 FLAGS (\\Seen))"
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer(update, structure, "TAG OK FETCH done"))
                    .andThen(peer.answer(update +: literal("* 1 FETCH (UID 2 BODY[2]", "aGVsbG8=") :+ "TAG OK FETCH done"*))
            )(EmailImap.fetchPart(uid(2), Chunk(2))).map(outcome => assert(new String(succeeded(outcome).toArray, "UTF-8") == "hello"))
        }
        "a base64 part decoded, found in the body structure first" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer(structure, "TAG OK FETCH done"))
                    .andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[2]", "aGVsbG8=") :+ "TAG OK FETCH done"*))
            )(EmailImap.fetchPart(uid(2), Chunk(2))).map { outcome =>
                assert(new String(succeeded(outcome).toArray, "UTF-8") == "hello")
                assert(outcome._2.slice(1, 3) == Chunk("A3 UID FETCH 2 (UID BODYSTRUCTURE)", "A4 UID FETCH 2 (UID BODY.PEEK[2])"))
            }
        }
        "a quoted-printable part decoded" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer(structure, "TAG OK FETCH done"))
                    .andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[1]", "caf=C3=A9") :+ "TAG OK FETCH done"*))
            )(EmailImap.fetchPart(uid(2), Chunk(1))).map(outcome => assert(new String(succeeded(outcome).toArray, "UTF-8") == "café"))
        }
        "the empty path is the whole message as stored, with no structure fetched" in scripted {
            session(peer =>
                peer.select(7).andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[]", "Subject: x\r\n\r\ny") :+ "TAG OK FETCH done"*))
            )(
                EmailImap.fetchPart(uid(2), Chunk.empty)
            ).map { outcome =>
                assert(new String(succeeded(outcome).toArray, "UTF-8") == "Subject: x\r\n\r\ny")
                assert(outcome._2(1) == "A3 UID FETCH 2 (UID BODY.PEEK[])")
            }
        }
        "a path the structure does not have is EmailPartNotFoundException, and no part is fetched" in scripted {
            session(peer => peer.select(7).andThen(peer.answer(structure, "TAG OK FETCH done")))(EmailImap.fetchPart(
                uid(2),
                Chunk(4)
            )).map {
                outcome =>
                    assert(failed(outcome) == EmailPartNotFoundException("fetchPart", uid(2), Chunk(4)))
                    assert(outcome._2 == Chunk("A2 SELECT INBOX", "A3 UID FETCH 2 (UID BODYSTRUCTURE)", "A4 LOGOUT"))
            }
        }
        "base64 whose alphabet characters end with a single one is EmailTransferDecodeException" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer(structure, "TAG OK FETCH done"))
                    .andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[2]", "aGVsb") :+ "TAG OK FETCH done"*))
            )(EmailImap.fetchPart(uid(2), Chunk(2))).map { outcome =>
                assert(failed(outcome) == EmailTransferDecodeException(Chunk(2), EmailTransferDecodeException.Problem.TruncatedBase64))
            }
        }
        "an encoding RFC 2045 does not define is EmailTransferDecodeException" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer(structure, "TAG OK FETCH done"))
                    .andThen(peer.answer(literal("* 1 FETCH (UID 2 BODY[3]", "begin 644 a") :+ "TAG OK FETCH done"*))
            )(EmailImap.fetchPart(uid(2), Chunk(3))).map { outcome =>
                assert(failed(outcome) == EmailTransferDecodeException(
                    Chunk(3),
                    EmailTransferDecodeException.Problem.UnsupportedTransferEncoding("x-uuencode")
                ))
            }
        }
        "an expunged UID is EmailMessageNotFoundException" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG OK FETCH done")))(EmailImap.fetchPart(uid(2), Chunk(1))).map {
                outcome => assert(failed(outcome) == EmailMessageNotFoundException("fetchPart", uid(2)))
            }
        }
    }

    "flags" - {
        "addFlags and removeFlags store silently, the flags in a stable order" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG OK STORE done")).andThen(peer.answer("TAG OK STORE done")))(
                EmailImap.addFlags(Seq(uid(1), uid(2), uid(3)), Set(Email.Flag.Seen, EmailLiterals.keywordOf("$Junk")))
                    .andThen(EmailImap.removeFlags(Seq(uid(3)), Set(Email.Flag.Flagged)))
            ).map { outcome =>
                assert(outcome._2.take(3) == Chunk(
                    "A2 SELECT INBOX",
                    "A3 UID STORE 1:3 +FLAGS.SILENT ($Junk \\Seen)",
                    "A4 UID STORE 3 -FLAGS.SILENT (\\Flagged)"
                ))
            }
        }
        "STORE answered NO [NOPERM] is EmailImapCommandException" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG NO [NOPERM] read-only")))(
                EmailImap.removeFlags(Seq(uid(1)), Set(Email.Flag.Seen))
            ).map { outcome =>
                assert(failed(outcome) == EmailImapCommandException(
                    "removeFlags",
                    "STORE",
                    EmailImapCommandException.Status.No,
                    Present(EmailImap.ResponseCode.NoPerm),
                    "read-only"
                ))
            }
        }
        "no flags, or no UIDs, sends nothing" in scripted {
            session(_ => ())(EmailImap.addFlags(
                Seq(uid(1)),
                Set.empty
            ).andThen(EmailImap.removeFlags(Seq.empty, Set(Email.Flag.Seen)))).map {
                outcome => assert(outcome._1 == Result.succeed(()) && outcome._2 == Chunk("A2 LOGOUT"))
            }
        }
    }

    "move" - {
        "UID MOVE when the server has MOVE (RFC 6851)" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG OK MOVE done")), authenticated = "IMAP4rev1 MOVE")(
                EmailImap.move(Seq(uid(1), uid(2)), archive)
            ).map(outcome => assert(outcome._2.take(2) == Chunk("A2 SELECT INBOX", "A3 UID MOVE 1:2 Archive")))
        }
        "copy, flag and expunge exactly the moved UIDs under UIDPLUS (RFC 4315)" in scripted {
            session(
                peer =>
                    peer.select(7)
                        .andThen(peer.answer("TAG OK COPY done"))
                        .andThen(peer.answer("TAG OK STORE done"))
                        .andThen(peer.answer("TAG OK EXPUNGE done")),
                authenticated = "IMAP4rev1 UIDPLUS"
            )(EmailImap.move(Seq(uid(1), uid(2)), archive)).map { outcome =>
                assert(outcome._2.take(4) == Chunk(
                    "A2 SELECT INBOX",
                    "A3 UID COPY 1:2 Archive",
                    "A4 UID STORE 1:2 +FLAGS.SILENT (\\Deleted)",
                    "A5 UID EXPUNGE 1:2"
                ))
            }
        }
        "a server with neither is EmailCapabilityMissingException, before anything is sent" in scripted {
            session(_ => ())(EmailImap.move(Seq(uid(1)), archive)).map { outcome =>
                assert(failed(outcome) ==
                    EmailCapabilityMissingException("move", "127.0.0.1", EmailCapabilityMissingException.Capability.ImapMove))
                assert(outcome._2 == Chunk("A2 LOGOUT"))
            }
        }
        "UID MOVE answered NO [TRYCREATE] (RFC 9051 7.1) or NO [NONEXISTENT] (RFC 5530) is EmailMailboxNotFoundException for the destination" in
            scripted {
                Kyo.foreachDiscard(Seq("TRYCREATE", "NONEXISTENT")) { code =>
                    session(
                        peer => peer.select(7).andThen(peer.answer(s"TAG NO [$code] no such mailbox")),
                        authenticated = "IMAP4rev1 MOVE"
                    )(
                        EmailImap.move(Seq(uid(1)), archive)
                    ).map { outcome =>
                        assert(failed(outcome) == EmailMailboxNotFoundException("move", archive), code)
                        assert(outcome._2.take(2) == Chunk("A2 SELECT INBOX", "A3 UID MOVE 1 Archive"), code)
                    }
                }.andThen(succeed)
            }
        "UID COPY answered NO [TRYCREATE] or NO [NONEXISTENT] is EmailMailboxNotFoundException, and nothing is flagged" in scripted {
            Kyo.foreachDiscard(Seq("TRYCREATE", "NONEXISTENT")) { code =>
                session(
                    peer => peer.select(7).andThen(peer.answer(s"TAG NO [$code] no such mailbox")),
                    authenticated = "IMAP4rev1 UIDPLUS"
                )(
                    EmailImap.move(Seq(uid(1)), archive)
                ).map { outcome =>
                    assert(failed(outcome) == EmailMailboxNotFoundException("move", archive), code)
                    assert(outcome._2.take(2) == Chunk("A2 SELECT INBOX", "A3 UID COPY 1 Archive"), code)
                    assert(!outcome._2.exists(line => line.contains("STORE") || line.contains("EXPUNGE")), code)
                }
            }.andThen(succeed)
        }
        "UID MOVE answered NO with another code is EmailImapCommandException carrying it" in scripted {
            session(peer => peer.select(7).andThen(peer.answer("TAG NO [NOPERM] denied")), authenticated = "IMAP4rev1 MOVE")(
                EmailImap.move(Seq(uid(1)), archive)
            ).map { outcome =>
                assert(failed(outcome) == EmailImapCommandException(
                    "move",
                    "MOVE",
                    EmailImapCommandException.Status.No,
                    Present(EmailImap.ResponseCode.NoPerm),
                    "denied"
                ))
            }
        }
    }

    "custom" - {
        "sends the line with a tag and answers the untagged responses, literals inline, and the completion text" in scripted {
            session(peer => peer.answer(Seq("* 4 EXISTS") ++ literal("* 1 FETCH (BODY[]", "ab") :+ "TAG OK NOOP done"*))(
                EmailImap.custom(EmailLiterals.imapCommandOf("NOOP"))
            ).map { outcome =>
                assert(succeeded(outcome) == EmailImap.Reply(Chunk("* 4 EXISTS", "* 1 FETCH (BODY[] {2}\r\nab)"), "NOOP done"))
                assert(outcome._2.head == "A2 NOOP")
            }
        }
        "NO is EmailImapCommandException naming the command's first word" in scripted {
            session(peer => peer.answer("TAG NO [CANNOT] refused"))(EmailImap.custom(
                EmailLiterals.imapCommandOf("GETQUOTAROOT INBOX")
            )).map {
                outcome =>
                    assert(failed(outcome) == EmailImapCommandException(
                        "custom",
                        "GETQUOTAROOT",
                        EmailImapCommandException.Status.No,
                        Present(EmailImap.ResponseCode.Cannot),
                        "refused"
                    ))
            }
        }
        "the selected mailbox is forgotten, so the next verb selects again" in scripted {
            session(peer =>
                peer.select(7)
                    .andThen(peer.answer("TAG OK SEARCH done"))
                    .andThen(peer.answer("TAG OK done"))
                    .andThen(peer.select(7))
                    .andThen(peer.answer("TAG OK SEARCH done"))
            )(EmailImap.search(inbox, EmailImap.Search.All).andThen(EmailImap.custom(EmailLiterals.imapCommandOf("SELECT Archive")))
                .andThen(EmailImap.search(inbox, EmailImap.Search.All))).map { outcome =>
                assert(outcome._2.take(5) ==
                    Chunk("A2 SELECT INBOX", "A3 UID SEARCH ALL", "A4 SELECT Archive", "A5 SELECT INBOX", "A6 UID SEARCH ALL"))
            }
        }
        "an untagged line echoing the credential is redacted" in scripted {
            session(peer => peer.answer(s"* NO echo $secret", "TAG OK done"))(EmailImap.custom(EmailLiterals.imapCommandOf("NOOP"))).map {
                outcome =>
                    assert(succeeded(outcome).untagged == Chunk("* NO echo <redacted>"))
            }
        }
    }

    "a UID set that renders past 8192 octets is split, every command line within them (RFC 7162 section 4)" - {
        val many                                                          = Chunk.from((0 until 1500).map(i => uid(10001L + 2 * i)))
        val expected                                                      = many.map(_.value).toSet
        def sets(lines: Chunk[String], command: String): Chunk[Set[Long]] =
            lines.filter(_.drop(3).startsWith(command)).map { line =>
                line.split(' ').find(_.headOption.exists(_.isDigit)).fold(Set.empty[Long])(_.split(',').map(_.toLong).toSet)
            }
        final case class Case(name: String, capabilities: String, verb: Verb[Any], commands: Seq[String])
        Seq(
            Case("fetch", "IMAP4rev1", EmailImap.fetch(many), Seq("UID FETCH")),
            Case("addFlags", "IMAP4rev1", EmailImap.addFlags(many, Set(Email.Flag.Seen)), Seq("UID STORE")),
            Case("move", "IMAP4rev1 MOVE", EmailImap.move(many, archive), Seq("UID MOVE")),
            Case("move without MOVE", "IMAP4rev1 UIDPLUS", EmailImap.move(many, archive), Seq("UID COPY", "UID STORE", "UID EXPUNGE"))
        ).foreach { c =>
            c.name in scripted {
                session(peer => peer.select(7), authenticated = c.capabilities)(c.verb).map { outcome =>
                    assert(outcome._1.isSuccess)
                    assert(outcome._2.forall(_.getBytes("UTF-8").length + 2 <= 8192))
                    c.commands.foreach { command =>
                        val split = sets(outcome._2, command)
                        assert(split.size >= 2)
                        assert(split.flatten.toSet == expected && split.map(_.size).sum == expected.size)
                    }
                }
            }
        }
    }

    "the session" - {
        "verbs on one session from two fibers never interleave their commands" in scripted {
            session(peer =>
                Kyo.foreachDiscard(1 to 2)(_ => peer.select(7).andThen(peer.answer("* SEARCH 1", "TAG OK SEARCH done")))
            )(Async.zip(EmailImap.search(inbox, EmailImap.Search.All), EmailImap.search(archive, EmailImap.Search.All))).map { outcome =>
                val commands = outcome._2.take(4).map(_.drop(3))
                assert(commands == Chunk("SELECT INBOX", "UID SEARCH ALL", "SELECT Archive", "UID SEARCH ALL") ||
                    commands == Chunk("SELECT Archive", "UID SEARCH ALL", "SELECT INBOX", "UID SEARCH ALL"))
            }
        }
        "a verb after a command's deadline fails with ConnectionClosed without writing" in scripted {
            Scope.run {
                Latch.init(1).map { commanded =>
                    serve()(peer => peer.ready().andThen(peer.command).andThen(commanded.release).andThen(peer.untilClosed)).map { server =>
                        ImapTestServer.config(server, byPass).map { config =>
                            Clock.withTimeControl { control =>
                                EmailImap.initUnscoped(config).map { imap =>
                                    Fiber.initUnscoped(Abort.run[EmailException](EmailImap.use(imap)(EmailImap.status(inbox)))).map {
                                        fiber =>
                                            for
                                                _      <- commanded.await.andThen(control.awaitPendingSleepers(1))
                                                _      <- control.advance(config.commandTimeout)
                                                first  <- fiber.get
                                                second <- Abort.run[EmailException](EmailImap.use(imap)(EmailImap.listMailboxes))
                                                _      <- server.awaitClose
                                                seen   <- server.received
                                            yield
                                                first match
                                                    case Result.Failure(ex: EmailTransportException) =>
                                                        assert(ex.kind == Kind.Timeout && ex.method == "status")
                                                    case other => fail(other.toString)
                                                end match
                                                second match
                                                    case Result.Failure(ex: EmailTransportException) =>
                                                        assert(ex.kind == Kind.ConnectionClosed(Absent) && ex.method == "listMailboxes")
                                                    case other => fail(other.toString)
                                                end match
                                                assert(seen.drop(1) == Chunk("A2 STATUS INBOX (MESSAGES UNSEEN UIDNEXT UIDVALIDITY)"))
                                            end for
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        // The server sends nothing after the marker, so reading the literal would wait out commandTimeout instead.
        "a literal announced past the default maxResponseLength is Protocol, and nothing is read for it" in scripted {
            session(peer => peer.answer("* LIST () \"/\" {200000000}"))(EmailImap.listMailboxes).map { outcome =>
                failed(outcome) match
                    case ex: EmailTransportException => assert(ex.kind == Kind.Protocol("* LIST () \"/\" {200000000}"))
                    case other                       => fail(other.toString)
            }
        }
        "a literal one octet past maxResponseLength is Protocol, and nothing is read for it" in scripted {
            session(peer => peer.answer("* LIST () \"/\" {4097}"), configure = c => EmailLiterals.valid(c.maxResponseLength(4096.bytes)))(
                EmailImap.listMailboxes
            ).map { outcome =>
                failed(outcome) match
                    case ex: EmailTransportException => assert(ex.kind == Kind.Protocol("* LIST () \"/\" {4097}"))
                    case other                       => fail(other.toString)
            }
        }
        "untagged responses past maxResponseLength, together, are Protocol, though each is small" in scripted {
            val line = "* SEARCH " + (1 to 30).mkString(" ")
            session(
                peer => peer.select(7).andThen(peer.answer(Seq.fill(60)(line) :+ "TAG OK SEARCH done"*)),
                configure = c => EmailLiterals.valid(c.maxResponseLength(4096.bytes))
            )(EmailImap.search(inbox, EmailImap.Search.All)).map { outcome =>
                failed(outcome) match
                    case ex: EmailTransportException => assert(ex.kind == Kind.Protocol(line))
                    case other                       => fail(other.toString)
            }
        }
        "a line longer than 1 MiB is Protocol, even when it ends" in scripted {
            session(peer => peer.answer("* OK " + "x" * (1 << 20)))(EmailImap.listMailboxes).map { outcome =>
                failed(outcome) match
                    case ex: EmailTransportException => assert(ex.kind == Kind.Protocol("* OK " + "x" * 195))
                    case other                       => fail(other.toString)
            }
        }
    }

    // Every verb's transport pairs of section 4.2: the server closes the connection, sends BYE, or answers with what is not IMAP, on the
    // verb's own command (after the SELECT that verbs addressing messages send first).
    "transport failures of every verb" - {
        final case class Case(method: String, selects: Boolean, capabilities: String, verb: Verb[Any])
        val cases = Seq(
            Case("listMailboxes", selects = false, "IMAP4rev1", EmailImap.listMailboxes),
            Case("status", selects = false, "IMAP4rev1", EmailImap.status(inbox)),
            Case("search", selects = true, "IMAP4rev1", EmailImap.search(inbox, EmailImap.Search.All)),
            Case("fetch", selects = true, "IMAP4rev1", EmailImap.fetch(Seq(uid(2)))),
            Case("fetchMessage", selects = true, "IMAP4rev1", EmailImap.fetchMessage(uid(2))),
            Case("fetchPart", selects = true, "IMAP4rev1", EmailImap.fetchPart(uid(2), Chunk(1))),
            Case("addFlags", selects = true, "IMAP4rev1", EmailImap.addFlags(Seq(uid(2)), Set(Email.Flag.Seen))),
            Case("removeFlags", selects = true, "IMAP4rev1", EmailImap.removeFlags(Seq(uid(2)), Set(Email.Flag.Seen))),
            Case("move", selects = true, "IMAP4rev1 MOVE", EmailImap.move(Seq(uid(2)), archive)),
            Case("custom", selects = false, "IMAP4rev1", EmailImap.custom(EmailLiterals.imapCommandOf("NOOP")))
        )
        def failure(c: Case)(respond: Peer => Any < (Async & Abort[LineConnectionFixture.Failure]))(using Frame) =
            session(
                peer => (if c.selects then peer.select(7).unit else Kyo.unit).andThen(peer.command).andThen(respond(peer)),
                authenticated = c.capabilities
            )(c.verb).map(failed)
        cases.foreach { c =>
            s"${c.method}: a close, with and without BYE, is ConnectionClosed; an answer that is not IMAP is Protocol" in scripted {
                for
                    closed  <- failure(c)(peer => peer.line.close)
                    bye     <- failure(c)(peer => peer.send("* BYE shutting down"))
                    garbage <- failure(c)(peer => peer.send("?? what"))
                yield Chunk(closed, bye, garbage) match
                    case Chunk(a: EmailTransportException, b: EmailTransportException, g: EmailTransportException) =>
                        assert(a.method == c.method && b.method == c.method && g.method == c.method)
                        assert(a.kind == Kind.ConnectionClosed(Absent))
                        assert(b.kind == Kind.ConnectionClosed(Present("shutting down")))
                        assert(g.kind == Kind.Protocol("?? what"))
                    case other => fail(other.toString)
            }
            s"${c.method}: a command the server never answers is Timeout after commandTimeout, on kyo's clock" in scripted {
                Scope.run {
                    Latch.init(1).map { commanded =>
                        serve() { peer =>
                            peer.ready(authenticated = c.capabilities)
                                .andThen(if c.selects then peer.select(7).unit else Kyo.unit)
                                .andThen(peer.command)
                                .andThen(commanded.release)
                                .andThen(peer.untilClosed)
                        }.map { server =>
                            ImapTestServer.config(server, byPass).map { config =>
                                Clock.withTimeControl { control =>
                                    EmailImap.initUnscoped(config).map { imap =>
                                        Fiber.initUnscoped(Abort.run[EmailException](EmailImap.use(imap)(c.verb))).map { fiber =>
                                            commanded.await.andThen(control.awaitPendingSleepers(1)).andThen(
                                                control.advance(config.commandTimeout)
                                            ).andThen(fiber.get).map {
                                                case Result.Failure(ex: EmailTransportException) =>
                                                    assert(ex.method == c.method && ex.kind == Kind.Timeout)
                                                    assert(ex.timeout == Present(config.commandTimeout))
                                                case other => fail(other.toString)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

end EmailImapVerbsTest
