package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.Security
import kyo.EmailLiveServer.User
import kyo.EmailReceive.Mailbox.Attribute

/** The `EmailReceive` verbs against real servers, on messages the account sent itself. */
class EmailReceiveLiveTest extends EmailLiveSuite:

    private def parts(part: EmailReceive.Part): Chunk[EmailReceive.Part] = Chunk(part).concat(part.children.flatMap(parts))

    private def trash(using Frame): Email.MailboxName < (Async & Abort[Throwable] & EmailReceive) =
        EmailReceive.listMailboxes.map { all =>
            Maybe.fromOption(all.find(_.attributes.contains(Attribute.Trash))) match
                case Present(found) => found.name
                case Absent         => Abort.fail(new IllegalStateException(s"no \\Trash mailbox among ${all.map(_.name.value)}"))
        }

    /** Sends a message from `account` to itself and answers its UID once the receive loop has it. */
    private def delivered(account: EmailLiveAccount, subject: String)(using Frame): Email.Uid < (Async & Abort[Throwable]) =
        val message = Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = subject, text = subject)
        arrival(account, subject)(EmailSend.run(account.smtp)(EmailSend.send(message))).map((_, uid, _) => uid)

    "the session verbs find, read, flag and move the message" in server() { mail =>
        val account  = mail.account(User.Test)
        val attached = Span.from("the invoice".getBytes("UTF-8"))
        val message  = Email.Message(
            from = Chunk(account.address),
            to = Chunk(account.address),
            subject = "verbs",
            text = "Verbs.",
            attachments = Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("application", "pdf"), Present("invoice.pdf"), attached))
        )
        arrival(account, "verbs")(EmailSend.run(account.smtp)(EmailSend.send(message))).map { (_, uid, _) =>
            EmailReceive.run(account.imap) {
                for
                    found     <- EmailReceive.search(inbox, EmailReceive.Search.Subject("verbs"))
                    summaries <- EmailReceive.fetch(Seq(uid))
                    whole     <- EmailReceive.fetchMessage(uid)
                    pdf = summaries.flatMap(s => parts(s.structure)).filter(_.mediaType.baseType == "application/pdf")
                    content   <- Kyo.foreach(pdf)(part => EmailReceive.fetchPart(uid, part.path))
                    _         <- EmailReceive.addFlags(Seq(uid), Set(Email.Flag.Flagged))
                    flagged   <- EmailReceive.fetch(Seq(uid))
                    _         <- EmailReceive.removeFlags(Seq(uid), Set(Email.Flag.Flagged))
                    unflagged <- EmailReceive.fetch(Seq(uid))
                    noop      <- EmailReceive.custom(EmailLiterals.imapCommandOf("NOOP"))
                    target    <- trash
                    _         <- EmailReceive.move(Seq(uid), target)
                    left      <- EmailReceive.search(inbox, EmailReceive.Search.All)
                    moved     <- EmailReceive.search(target, EmailReceive.Search.Subject("verbs"))
                yield
                    assert(found == Chunk(uid))
                    assert(summaries.map(_.header.subject) == Chunk("verbs"))
                    assert(whole.subject == "verbs")
                    assert(content.map(c => Chunk.from(c.toArray)) == Chunk(Chunk.from(attached.toArray)))
                    assert(flagged.exists(_.flags.contains(Email.Flag.Flagged)))
                    assert(!unflagged.exists(_.flags.contains(Email.Flag.Flagged)))
                    assert(noop.text.nonEmpty)
                    assert(left.isEmpty)
                    assert(moved.size == 1)
            }
        }
    }

    "a session opens over implicit TLS on 993 and over STARTTLS on 143" in server() { mail =>
        Kyo.foreach(Chunk(Security.Implicit, Security.StartTls)) { security =>
            EmailReceive.run(mail.imap(User.Test, mail.tls(security)))(EmailReceive.status(inbox))
        }.map { statuses =>
            assert(statuses.map(_.mailbox) == Chunk(inbox, inbox))
            assert(statuses.map(_.uidValidity).distinct.size == 1)
        }
    }

    "the special-use mailboxes are listed with their attributes" in server() { mail =>
        EmailReceive.run(mail.imap(User.Test))(EmailReceive.listMailboxes).map { all =>
            val byUse = Chunk(Attribute.Drafts, Attribute.Junk, Attribute.Sent, Attribute.Trash).map { use =>
                use -> all.filter(_.attributes.contains(use)).map(_.name.value)
            }
            assert(byUse == Chunk(
                Attribute.Drafts -> Chunk("Drafts"),
                Attribute.Junk   -> Chunk("Junk"),
                Attribute.Sent   -> Chunk("Sent"),
                Attribute.Trash  -> Chunk("Trash")
            ))
        }
    }

    "a mailbox named past ASCII is listed, counted and moved into under its own name" in server() { mail =>
        val account = mail.account(User.Test)
        val name    = EmailLiterals.mailboxOf("Réunion 日本 & co")
        mail.doveadm("mailbox", "create", "-u", mail.login(User.Test), name.value).andThen {
            delivered(account, "utf-8 mailbox").map { uid =>
                EmailReceive.run(account.imap) {
                    for
                        all    <- EmailReceive.listMailboxes
                        _      <- EmailReceive.move(Seq(uid), name)
                        status <- EmailReceive.status(name)
                        found  <- EmailReceive.search(name, EmailReceive.Search.Subject("utf-8 mailbox"))
                    yield
                        assert(all.map(_.name).contains(name))
                        assert(status.messages == 1)
                        assert(found.size == 1)
                }
            }
        }
    }

    "move without MOVE copies, flags and expunges, leaving the message only in the target" in server() { mail =>
        val account = mail.account(User.NoMove)
        delivered(account, "no move").map { uid =>
            EmailReceive.run(account.imap) {
                for
                    capability <- EmailReceive.custom(EmailLiterals.imapCommandOf("CAPABILITY"))
                    target     <- trash
                    _          <- EmailReceive.move(Seq(uid), target)
                    left       <- EmailReceive.search(inbox, EmailReceive.Search.All)
                    moved      <- EmailReceive.search(target, EmailReceive.Search.Subject("no move"))
                yield
                    val offered = capability.untagged.flatMap(_.split(' ').toSeq)
                    assert(offered.contains("UIDPLUS"))
                    assert(!offered.contains("MOVE"))
                    assert(left.isEmpty)
                    assert(moved.size == 1)
            }
        }
    }

    "move with neither MOVE nor UIDPLUS fails naming the capability, and leaves the message where it was" in server() { mail =>
        val account = mail.account(User.Bare)
        delivered(account, "bare").map { uid =>
            EmailReceive.run(account.imap) {
                trash.map { target =>
                    Abort.run[EmailMoveFailure](EmailReceive.move(Seq(uid), target)).map { result =>
                        EmailReceive.search(inbox, EmailReceive.Search.All).map { left =>
                            result match
                                case Result.Failure(missing: EmailCapabilityMissingException) =>
                                    assert(missing.capability == EmailCapabilityMissingException.Capability.ImapMove)
                                case other => fail(s"expected the move to need a capability, got $other")
                            end match
                            assert(left == Chunk(uid))
                        }
                    }
                }
            }
        }
    }

    "a wrong password is rejected with the server's AUTHENTICATIONFAILED" in ownServer() { mail =>
        val wrong = EmailLiterals.passwordAccountOf(mail.login(User.Test), EmailLiterals.passwordOf(mail.password + "x"))
        Abort.run[EmailStatusFailure](EmailReceive.run(mail.imap(User.Test).account(wrong))(EmailReceive.status(inbox))).map {
            case Result.Failure(rejected: EmailAuthenticationException) =>
                assert(rejected.user == mail.login(User.Test))
                rejected.reply match
                    case EmailAuthenticationException.Reply.Imap(code, _) =>
                        assert(code == Present(EmailReceive.ResponseCode.AuthenticationFailed))
                    case other => fail(s"expected an IMAP reply, got $other")
                end match
            case other => fail(s"expected the credential to be rejected, got $other")
        }
    }

    "reading a message leaves it unseen: fetch, fetchMessage and fetchPart set no \\Seen" in server() { mail =>
        val account = mail.account(User.Test)
        delivered(account, "unseen").map { uid =>
            EmailReceive.run(account.imap) {
                for
                    summaries <- EmailReceive.fetch(Seq(uid))
                    _         <- EmailReceive.fetchMessage(uid)
                    _         <- EmailReceive.fetchPart(uid, Chunk.empty)
                    after     <- EmailReceive.fetch(Seq(uid))
                    unseen    <- EmailReceive.search(inbox, EmailReceive.Search.Unseen)
                yield
                    assert(summaries.map(_.flags.contains(Email.Flag.Seen)) == Chunk(false))
                    assert(after.map(_.flags.contains(Email.Flag.Seen)) == Chunk(false))
                    assert(unseen == Chunk(uid))
            }
        }
    }

    "a keyword and \\Seen are stored, found by search, and removed" in server() { mail =>
        val account = mail.account(User.Test)
        val keyword = EmailLiterals.keywordOf("kyo_Invoice")
        delivered(account, "keyword").map { uid =>
            EmailReceive.run(account.imap) {
                for
                    _       <- EmailReceive.addFlags(Seq(uid), Set(keyword, Email.Flag.Seen))
                    flagged <- EmailReceive.fetch(Seq(uid))
                    found   <- EmailReceive.search(inbox, EmailReceive.Search.Keyword(keyword))
                    seen    <- EmailReceive.search(inbox, EmailReceive.Search.Seen)
                    _       <- EmailReceive.removeFlags(Seq(uid), Set(keyword, Email.Flag.Seen))
                    cleared <- EmailReceive.fetch(Seq(uid))
                    none    <- EmailReceive.search(inbox, EmailReceive.Search.Keyword(keyword))
                yield
                    assert(flagged.map(s => Set[Email.Flag](keyword, Email.Flag.Seen).subsetOf(s.flags)) == Chunk(true))
                    assert(found == Chunk(uid))
                    assert(seen == Chunk(uid))
                    assert(cleared.map(s => s.flags.contains(keyword) || s.flags.contains(Email.Flag.Seen)) == Chunk(false))
                    assert(none.isEmpty)
            }
        }
    }

    "search finds a subject past ASCII" in server() { mail =>
        val account = mail.account(User.Test)
        delivered(account, "ascii").andThen(delivered(account, "Réunion à 10h, 日本")).map { uid =>
            EmailReceive.run(account.imap)(EmailReceive.search(inbox, EmailReceive.Search.Subject("à 10h, 日本"))).map { found =>
                assert(found == Chunk(uid))
            }
        }
    }

    "a UID expunged from its mailbox has no summary, and fetching it is not a failure" in server() { mail =>
        val account = mail.account(User.Test)
        delivered(account, "expunged").map { uid =>
            EmailReceive.run(account.imap) {
                for
                    target  <- trash
                    _       <- EmailReceive.move(Seq(uid), target)
                    summary <- EmailReceive.fetch(Seq(uid))
                yield assert(summary.isEmpty)
            }
        }
    }

    Chunk((User.Test, "MOVE"), (User.NoMove, "the COPY fallback")).foreach { (user, how) =>
        s"several messages move in one call, through $how" in server() { mail =>
            val account = mail.account(user)
            Kyo.foreach(Chunk("one", "two", "three"))(subject => delivered(account, subject)).map { uids =>
                EmailReceive.run(account.imap) {
                    for
                        target <- trash
                        _      <- EmailReceive.move(uids, target)
                        left   <- EmailReceive.search(inbox, EmailReceive.Search.All)
                        moved  <- EmailReceive.search(target, EmailReceive.Search.All)
                        heads  <- EmailReceive.fetch(moved)
                    yield
                        assert(left.isEmpty)
                        assert(heads.map(_.header.subject) == Chunk("one", "two", "three"))
                }
            }
        }

        s"a move to a mailbox that does not exist is EmailMailboxNotFoundException through $how, and the message stays" in server() {
            mail =>
                val account = mail.account(user)
                val missing = EmailLiterals.mailboxOf("Missing")
                delivered(account, "stays").map { uid =>
                    EmailReceive.run(account.imap) {
                        Abort.run[EmailMoveFailure](EmailReceive.move(Seq(uid), missing)).map { result =>
                            EmailReceive.search(inbox, EmailReceive.Search.All).map { left =>
                                assert(result == Result.fail(EmailMailboxNotFoundException("move", missing)))
                                assert(left == Chunk(uid))
                            }
                        }
                    }
                }
        }
    }

    "a mailbox that does not exist is EmailMailboxNotFoundException for status and search" in server() { mail =>
        val missing = EmailLiterals.mailboxOf("Missing")
        EmailReceive.run(mail.imap(User.Test)) {
            for
                status <- Abort.run[EmailStatusFailure](EmailReceive.status(missing))
                search <- Abort.run[EmailSearchFailure](EmailReceive.search(missing, EmailReceive.Search.All))
            yield
                assert(status == Result.fail(EmailMailboxNotFoundException("status", missing)))
                assert(search == Result.fail(EmailMailboxNotFoundException("search", missing)))
        }
    }

end EmailReceiveLiveTest
