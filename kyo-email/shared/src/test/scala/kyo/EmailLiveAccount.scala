package kyo

import kyo.EmailImap.InboxEvent.Received
import kyo.EmailImap.Start

/** A user of an [[EmailLiveServer]] that a live leaf sends from and reads back. */
final case class EmailLiveAccount(address: Email.Address, imap: EmailImapConfig[Nothing], smtp: EmailSmtpConfig[Nothing])

object EmailLiveAccount:

    val inbox: Email.MailboxName = Email.MailboxName.Inbox

    // A live leaf's kyo-test timeout, the one real-clock bound in the module's tests. Delivery on a local server is immediate; the bound
    // covers the container's start without letting a lost message hang the run.
    val Timeout: Duration = 3.minutes

    /** Runs `send` and answers the message the receive loop delivers with `subject`, with its UID. The loop starts after the inbox's last
      * UID before the send, so the message cannot arrive before the loop is listening for it; the leaf's `Timeout` bounds the wait.
      */
    def arrival[A](account: EmailLiveAccount, subject: String)(send: => A < (Async & Abort[Throwable]))(using
        Frame
    ): (A, Email.Uid, Email.Message) < (Async & Abort[Throwable]) =
        // The loop's fiber is scoped, so the leaf's timeout or a failure of the send interrupts it too, not only the delivery.
        Scope.run {
            for
                before <- EmailImap.let(account.imap)(EmailImap.status(inbox))
                start =
                    if before.uidNext <= 1 then Start.All(inbox)
                    else Start.After(EmailLiterals.uidOf(inbox, before.uidValidity, before.uidNext - 1))
                arrived <- Promise.init[(Email.Uid, Email.Message), Any]
                _       <- Fiber.init(EmailImap.run(account.imap, start) {
                    case received: Received =>
                        Abort.run[EmailParseFailure](received.message).map {
                            case Result.Success(message) if message.subject == subject =>
                                arrived.completeDiscard(Result.succeed((received.uid, message)))
                            case _ => Kyo.unit
                        }
                    case _ => Kyo.unit
                })
                sent      <- send
                delivered <- arrived.get
            yield (sent, delivered._1, delivered._2)
        }

end EmailLiveAccount
