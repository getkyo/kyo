package kyo

import kyo.EmailLiveAccount.*
import kyo.EmailLiveServer.User
import kyo.EmailReceive.InboxEvent.Received
import kyo.EmailReceive.Start

/** `EmailReceive.receive` against a real server: messages delivered by Postfix, pushed by Dovecot, and a server that disconnects the loop
  * or replaces the mailbox under it.
  */
class EmailReceiveLoopLiveTest extends EmailLiveSuite:

    private def send(account: EmailLiveAccount, subject: String)(using Frame): Email.MessageId < (Async & Abort[Throwable]) =
        EmailSend.run(account.smtp) {
            EmailSend.send(Email.Message(from = Chunk(account.address), to = Chunk(account.address), subject = subject, text = subject))
        }

    /** Starts the receive loop on a fiber scoped to the leaf, handing each received message's subject and UID to the answered channel. */
    private def receiving(config: EmailImapConfig, start: Start)(using
        Frame
    ): Channel[(String, Email.Uid)] < (Async & Scope) =
        Channel.init[(String, Email.Uid)](16).map { events =>
            Fiber.init(EmailReceive.run(config)(EmailReceive.receive(start) {
                case received: Received => received.message.map(message => events.put((message.subject, received.uid)))
                case _                  => Kyo.unit
            })).andThen(events)
        }

    "a message past maxResponseLength is Unreadable with its size, and the loop goes on to the next" in server() { mail =>
        val account = mail.account(User.Test)
        val config  = account.imap.maxResponseLength(ByteSize.fromBytes(EmailLiveServer.SizeLimit.toBytes / 8))
        val big     = Span.from(Array.fill((EmailLiveServer.SizeLimit.toBytes / 4).toInt)(7.toByte))
        val large   = Email.Message(
            from = Chunk(account.address),
            to = Chunk(account.address),
            subject = "large",
            text = "Large.",
            attachments = Chunk(Email.Attachment(EmailLiterals.mediaTypeOf("application", "octet-stream"), Present("big.bin"), big))
        )
        for
            events <- Channel.init[EmailReceive.InboxEvent](16)
            _      <- Fiber.init(EmailReceive.run(config)(EmailReceive.receive(Start.All(inbox))(events.put)))
            _      <- EmailSend.run(account.smtp)(EmailSend.send(large))
            first  <- events.take
            _      <- send(account, "small")
            second <- events.take
        yield
            first match
                case EmailReceive.InboxEvent.Unreadable(_, EmailReceive.InboxEvent.Unreadable.Item.TooLarge(size, max)) =>
                    assert(max == config.maxResponseLength)
                    assert(size > max)
                case other => fail(s"expected the large message to be Unreadable, got $other")
            end match
            second match
                case received: Received => received.message.map(message => assert(message.subject == "small"))
                case other              => fail(s"expected the small message, got $other")
        end for
    }

    "a burst of messages sent at once is delivered once each, in UID order" in server() { mail =>
        val account  = mail.account(User.Test)
        val subjects = Chunk.tabulate(8)(i => s"burst $i")
        for
            events    <- receiving(account.imap, Start.All(inbox))
            _         <- Async.foreachDiscard(subjects)(subject => send(account, subject))
            delivered <- Kyo.fill(subjects.size)(events.take)
        yield
            assert(delivered.map(_._1).sorted == subjects.sorted)
            assert(delivered.map(_._2.value) == delivered.map(_._2.value).sorted.distinct)
        end for
    }

    "with polling and IDLE renewal past the leaf's timeout, a message sent while the loop waits arrives through IDLE" in server() { mail =>
        val account = mail.account(User.Test)
        val config  =
            EmailLiterals.valid(
                account.imap.pollInterval(EmailImapConfig.maxPollInterval).flatMap(_.idleRenewal(EmailImapConfig.maxIdleRenewal))
            )
        for
            events <- receiving(config, Start.All(inbox))
            _      <- send(account, "first")
            first  <- events.take
            _      <- send(account, "second")
            second <- events.take
        yield assert(Chunk(first._1, second._1) == Chunk("first", "second"))
        end for
    }

    "Start.All delivers the mailbox oldest first, and Start.After resumes after the UID it names" in server() { mail =>
        val account  = mail.account(User.Test)
        val subjects = Chunk("one", "two", "three")
        for
            live    <- receiving(account.imap, Start.All(inbox))
            arrived <- Kyo.foreach(subjects)(subject => send(account, subject).andThen(live.take))
            backlog <- receiving(account.imap, Start.All(inbox))
            all     <- Kyo.fill(3)(backlog.take)
            resumed <- receiving(account.imap, Start.After(arrived(1)._2))
            after   <- resumed.take
        yield
            assert(arrived.map(_._1) == subjects)
            assert(all == arrived)
            assert(all.map(_._2.value) == all.map(_._2.value).sorted)
            assert(after == arrived(2))
        end for
    }

    "after the server disconnects the loop, it reconnects and delivers each later message once" in server() { mail =>
        val account = mail.account(User.Test)
        for
            events <- receiving(account.imap, Start.All(inbox))
            _      <- send(account, "before")
            before <- events.take
            kicked <- mail.doveadm("kick", mail.login(User.Test))
            _      <- send(account, "after")
            after  <- events.take
            _      <- send(account, "later")
            later  <- events.take
        yield
            assert(kicked.trim == "1 connections kicked")
            assert(Chunk(before, after, later).map(_._1) == Chunk("before", "after", "later"))
            assert(Chunk(before, after, later).map(_._2).distinct.size == 3)
        end for
    }

    "resuming in a mailbox deleted and created again fails with its old and new UIDVALIDITY" in server() { mail =>
        val account = mail.account(User.Test)
        val watched = EmailLiterals.mailboxOf("Watched")
        for
            _       <- mail.doveadm("mailbox", "create", "-u", mail.login(User.Test), watched.value)
            old     <- EmailReceive.run(account.imap)(EmailReceive.status(watched))
            _       <- mail.doveadm("mailbox", "delete", "-u", mail.login(User.Test), watched.value)
            _       <- mail.doveadm("mailbox", "create", "-u", mail.login(User.Test), watched.value)
            current <- EmailReceive.run(account.imap)(EmailReceive.status(watched))
            resume = Start.After(EmailLiterals.uidOf(watched, old.uidValidity, 1))
            result <- Abort.run[EmailReceiveFailure](EmailReceive.run(account.imap)(EmailReceive.receive(resume)(_ => Kyo.unit)))
        yield
            assert(current.uidValidity != old.uidValidity)
            result match
                case Result.Failure(changed: EmailUidValidityChangedException) =>
                    assert(changed.mailbox == watched)
                    assert(changed.expected == old.uidValidity)
                    assert(changed.current == current.uidValidity)
                case other => fail(s"expected the UIDVALIDITY change, got $other")
            end match
        end for
    }

end EmailReceiveLoopLiveTest
