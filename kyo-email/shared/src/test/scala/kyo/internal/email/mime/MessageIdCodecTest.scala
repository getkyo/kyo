package kyo.internal.email.mime

import kyo.*
import kyo.Email.MessageId
import kyo.EmailRfc5322Examples.*

class MessageIdCodecTest extends kyo.test.Test[Any]:

    private def fields(message: String): Chunk[HeaderCodec.Field] =
        HeaderCodec.readSection(Span.from(message.getBytes("UTF-8")), 0).fields

    private def field(message: String, name: String): String = fields(message).find(_.name == name).map(_.value).getOrElse("")

    private def ids(value: String): Chunk[String] = MessageIdCodec.parse(value).map(_.value)

    "RFC 5322 appendix A" - {
        "A.1.1: one Message-ID" in {
            assert(ids(field(simple, "Message-ID")) == Chunk("1234@local.machine.example"))
        }
        "A.2: In-Reply-To and References of the third message" in {
            assert(ids(field(replyToReply, "Message-ID")) == Chunk("abcd.1234@local.machine.test"))
            assert(ids(field(replyToReply, "In-Reply-To")) == Chunk("3456@example.net"))
            assert(ids(field(replyToReply, "References")) == Chunk("1234@local.machine.example", "3456@example.net"))
        }
        "A.5: white space before the id" in {
            assert(ids(field(oddities, "Message-ID")) == Chunk("testabcd.1234@silly.test"))
        }
        "A.6.3: CFWS inside the obsolete id is not part of it" in {
            assert(ids(field(obsoleteWhiteSpace, "Message-ID")) == Chunk("1234@local.machine.example"))
        }
    }

    "reading" - {
        "phrases between ids are ignored" in {
            assert(ids("Your message of \"Mon, 1 Jan\" <a@b.example> and (see) <c@d.example>") == Chunk("a@b.example", "c@d.example"))
        }
        "a < inside a comment or a quoted string opens no id" in {
            assert(ids("(<x@y>) \"<z@w>\" <a@b>") == Chunk("a@b"))
        }
        "<> and an id the model rejects are skipped" in {
            assert(ids("<> <\"a b\"@c> <ok@d>") == Chunk("ok@d"))
        }
        "an unclosed < runs to the end" in {
            assert(ids("<a@b> <c@d") == Chunk("a@b", "c@d"))
        }
        "pathological input: runs of < and (, an unclosed quoted string, and ten thousand ids" in {
            assert(ids("<" * 10000) == Chunk.empty)
            assert(ids("(" * 10000) == Chunk.empty)
            assert(ids("\"<a@b>") == Chunk.empty)
            assert(ids("<a@b>" * 10000).size == 10000)
        }
        "a value with no < is one id when it is one word, CFWS removed" in {
            assert(ids("   malformed@id.machine.example \t ") == Chunk("malformed@id.machine.example"))
            assert(ids("(comment) m") == Chunk("m"))
            assert(ids("two words") == Chunk.empty)
            assert(ids("") == Chunk.empty)
        }
    }

    "rendering" - {
        "each id in brackets, every one after the first starting with SP, and read back equal" in {
            val written =
                MessageIdCodec.render(Chunk(EmailLiterals.messageIdOf("a@b.example"), EmailLiterals.messageIdOf("c.d@[192.0.2.1]")))
            assert(written == Chunk("<a@b.example>", " <c.d@[192.0.2.1]>"))
            val field = HeaderCodec.fold("References", written) match
                case Result.Success(f) => f
                case other             => throw new IllegalStateException(other.toString)
            assert(ids(fields(field + "\r\n")(0).value) == Chunk("a@b.example", "c.d@[192.0.2.1]"))
        }
    }

    "Stalwart id.json" - {
        import MessageIdCodecStalwart.*

        lazy val listed = parse(EmbeddedStalwartIdDifferencesTsv.text)

        "every vector is read" in {
            assert(vectors.size == 11)
        }
        "every difference from Stalwart is listed, with its reason" in {
            val unlisted = observed.filterNot(listed.contains)
            assert(unlisted.isEmpty, s"${unlisted.size} differences are not listed:\n${render(unlisted)}")
        }
        "every listed difference still occurs" in {
            val stale = listed.filterNot(observed.contains)
            assert(stale.isEmpty, s"${stale.size} listed differences no longer occur:\n${render(stale)}")
        }
        "the list has no duplicate rows, and every other vector agrees" in {
            assert(listed.distinct.size == listed.size)
            val agreeing = vectors.zipWithIndex.filterNot((_, i) => listed.exists(_.index == i))
            assert(agreeing.forall((v, _) => moduleReading(v) == referenceReading(v)))
            assert(agreeing.size + listed.size == 11)
        }
    }

end MessageIdCodecTest
