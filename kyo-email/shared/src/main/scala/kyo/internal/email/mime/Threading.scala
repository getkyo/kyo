package kyo.internal.email.mime

import kyo.*
import kyo.internal.Ascii

/** The fields that make a message a reply to another (RFC 5322 section 3.6.4), filled only where the caller left them empty. */
private[kyo] object Threading:

    def reply(original: Email.Message, reply: Email.Message): Email.Message =
        val parent = original.messageId.toChunk
        // RFC 5322 section 3.6.4: the parent's References, or its In-Reply-To when that names a single message and there are no
        // References, then the parent's own id.
        val ancestors =
            if original.references.nonEmpty then original.references
            else if original.inReplyTo.size == 1 then original.inReplyTo
            else Chunk.empty
        val addressed = reply.to.nonEmpty || reply.cc.nonEmpty || reply.bcc.nonEmpty
        reply.copy(
            inReplyTo = if reply.inReplyTo.nonEmpty then reply.inReplyTo else parent,
            references = if reply.references.nonEmpty then reply.references else ancestors.concat(parent),
            subject = if reply.subject.nonEmpty then reply.subject else subjectOf(original.subject),
            to = if addressed then reply.to else if original.replyTo.nonEmpty then original.replyTo else original.from
        )
    end reply

    private def subjectOf(original: String): String =
        if Ascii.equalsIgnoreCase(original.take(3), "Re:") then original else s"Re: $original"

end Threading
