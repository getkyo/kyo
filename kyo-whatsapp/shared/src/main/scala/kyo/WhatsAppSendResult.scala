package kyo

import kyo.schema.catchAll
import kyo.schema.rename
import kyo.schema.tagOnly

/** The answer of a send: Meta's JSON for it, the resolved recipients and the messages created.
  *
  * A send creates one message, so an answer holds at least one: `init` refuses an empty `messages`, and so does the `Schema`, as a
  * `ConstructorRejectedException` at the answer. `messageId` is the WAMID of the message sent, the stable identifier used for reply
  * context, reactions, and mark-as-read; `contactWaId` the recipient's normalized wa_id when Meta answers one; `status` its
  * `message_status`, `Absent` when Meta does not send it.
  */
final case class WhatsAppSendResult private (
    contacts: Chunk[WhatsAppSendResult.Contact] = Chunk.empty,
    messages: Chunk[WhatsAppSendResult.Message]
) derives CanEqual:
    def messageId: WhatsAppId.MessageId          = messages.head.id
    def contactWaId: Maybe[WhatsAppId.WaId]      = contacts.headMaybe.flatMap(_.waId)
    def status: Maybe[WhatsAppSendResult.Status] = messages.head.status
end WhatsAppSendResult

object WhatsAppSendResult:

    /** The answer holding `contacts` and `messages`, or why it cannot be one: a send answers at least one message. */
    def init(contacts: Chunk[Contact], messages: Chunk[Message]): Result[String, WhatsAppSendResult] =
        if messages.isEmpty then Result.fail("a send's answer holds the message it created")
        else Result.succeed(new WhatsAppSendResult(contacts, messages))

    /** A recipient as Meta resolved it: the `input` the send named and its `wa_id`. */
    final case class Contact(input: Maybe[String] = Absent, @rename("wa_id") waId: Maybe[WhatsAppId.WaId] = Absent)
        derives CanEqual, Schema

    /** A message the send created: its WAMID and, when Meta sends one, its `message_status`. */
    final case class Message(id: WhatsAppId.MessageId, @rename("message_status") status: Maybe[Status] = Absent) derives CanEqual, Schema

    given Schema[WhatsAppSendResult] =
        Schema.derivedVia((contacts: Chunk[Contact], messages: Chunk[Message]) => init(contacts, messages))

    /** Meta's `message_status`. Known values map to typed case objects; any other string maps to `Other(value)` so a future value does
      * not collapse to a misleading known state.
      *
      * Known values: `Accepted` (request received), `HeldForQualityAssessment` (held for review), `Paused` (sending paused due to
      * quality).
      */
    @tagOnly()
    sealed trait Status derives CanEqual
    object Status:
        @rename("accepted") case object Accepted                                    extends Status
        @rename("held_for_quality_assessment") case object HeldForQualityAssessment extends Status
        @rename("paused") case object Paused                                        extends Status
        @catchAll() final case class Other(value: String)                           extends Status derives CanEqual
        given Schema[Status] = Schema.derived[Status]
    end Status
end WhatsAppSendResult
