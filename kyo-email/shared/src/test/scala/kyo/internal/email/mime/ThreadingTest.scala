package kyo.internal.email.mime

import kyo.*

class ThreadingTest extends kyo.test.Test[Any]:

    private def id(value: String): Email.MessageId = EmailLiterals.messageIdOf(value)

    private val alice = Email.Address("alice@example.com")
    private val bob   = Email.Address("bob@example.com")
    private val list  = Email.Address("list@example.org")
    private val carol = Email.Address("carol@example.net")

    private val original = Email.Message(
        from = Chunk(alice),
        to = Chunk(bob),
        subject = "Lunch",
        messageId = Present(id("<m3@example.com>")),
        inReplyTo = Chunk(id("<m2@example.com>")),
        references = Chunk(id("<m1@example.com>"), id("<m2@example.com>"))
    )

    "ids" - {
        "In-Reply-To is the original's id, References its References then its id (RFC 5322 section 3.6.4)" in {
            val threaded = Threading.reply(original, Email.Message(text = "yes"))
            assert(threaded.inReplyTo == Chunk(id("<m3@example.com>")))
            assert(threaded.references == Chunk(id("<m1@example.com>"), id("<m2@example.com>"), id("<m3@example.com>")))
            assert(threaded.text == "yes")
        }
        "without References, a single In-Reply-To starts them" in {
            val threaded = Threading.reply(original.copy(references = Chunk.empty), Email.Message())
            assert(threaded.references == Chunk(id("<m2@example.com>"), id("<m3@example.com>")))
        }
        "without References, an In-Reply-To of several ids does not" in {
            val several  = original.copy(references = Chunk.empty, inReplyTo = Chunk(id("<a@example.com>"), id("<b@example.com>")))
            val threaded = Threading.reply(several, Email.Message())
            assert(threaded.references == Chunk(id("<m3@example.com>")))
        }
        "an original without an id gives no In-Reply-To, and References without it" in {
            val threaded = Threading.reply(original.copy(messageId = Absent), Email.Message())
            assert(threaded.inReplyTo == Chunk.empty)
            assert(threaded.references == Chunk(id("<m1@example.com>"), id("<m2@example.com>")))
            assert(Threading.reply(Email.Message(), Email.Message()).references == Chunk.empty)
        }
        "ids the caller set are kept" in {
            val own      = Email.Message(inReplyTo = Chunk(id("<x@example.com>")), references = Chunk(id("<y@example.com>")))
            val threaded = Threading.reply(original, own)
            assert(threaded.inReplyTo == Chunk(id("<x@example.com>")))
            assert(threaded.references == Chunk(id("<y@example.com>")))
        }
    }

    "subject" - {
        "Re: is added once, whatever the case of an existing one" in {
            assert(Threading.reply(original, Email.Message()).subject == "Re: Lunch")
            assert(Threading.reply(original.copy(subject = "Re: Lunch"), Email.Message()).subject == "Re: Lunch")
            assert(Threading.reply(original.copy(subject = "RE:Lunch"), Email.Message()).subject == "RE:Lunch")
            assert(Threading.reply(original.copy(subject = "Reply needed"), Email.Message()).subject == "Re: Reply needed")
            assert(Threading.reply(original.copy(subject = ""), Email.Message()).subject == "Re: ")
        }
        "a subject the caller set is kept" in {
            assert(Threading.reply(original, Email.Message(subject = "Dinner instead")).subject == "Dinner instead")
        }
    }

    "recipients" - {
        "the original's Reply-To, else its From, when the reply names none" in {
            assert(Threading.reply(original, Email.Message()).to == Chunk(alice))
            assert(Threading.reply(original.copy(replyTo = Chunk(list)), Email.Message()).to == Chunk(list))
        }
        "recipients the caller set in any of To, Cc or Bcc are kept, and none is added" in {
            val withCc = Threading.reply(original, Email.Message(cc = Chunk(carol)))
            assert(withCc.to == Chunk.empty)
            assert(withCc.cc == Chunk(carol))
            assert(Threading.reply(original, Email.Message(bcc = Chunk(carol))).to == Chunk.empty)
            assert(Threading.reply(original, Email.Message(to = Chunk(carol))).to == Chunk(carol))
        }
    }

end ThreadingTest
