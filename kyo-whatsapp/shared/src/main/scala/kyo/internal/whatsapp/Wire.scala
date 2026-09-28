package kyo.internal.whatsapp

import kyo.*

/** The wire DTO layer: one flat-product case class per Cloud API JSON object, mirroring
  * the wire shape 1:1. Field names use snake_case to match the Cloud API JSON keys
  * directly; kyo-schema serializes each field under its literal name. A `Maybe[T] = Absent`
  * field is omitted from the JSON, so a flat envelope with exactly one populated `Maybe`
  * body field produces precisely the nested `type`-keyed sibling wire shape with no
  * spurious null keys. Every DTO is private to the module; the public ADT never carries
  * a wire concern.
  */
private[kyo] object Wire:

    final case class TextBody(body: String, preview_url: Maybe[Boolean] = Absent) derives Schema

    final case class MediaBody(
        id: Maybe[String] = Absent,
        link: Maybe[String] = Absent,
        caption: Maybe[String] = Absent,
        filename: Maybe[String] = Absent
    ) derives Schema

    final case class LocationBody(
        latitude: Double,
        longitude: Double,
        name: Maybe[String] = Absent,
        address: Maybe[String] = Absent
    ) derives Schema

    final case class ReactionBody(message_id: String, emoji: String) derives Schema

    final case class ContextBody(message_id: String) derives Schema

    // contacts[]: the Contact public type maps to these snake_case DTOs in the codec.
    final case class ContactNameDto(
        formatted_name: String,
        first_name: Maybe[String] = Absent,
        last_name: Maybe[String] = Absent,
        middle_name: Maybe[String] = Absent,
        prefix: Maybe[String] = Absent,
        suffix: Maybe[String] = Absent
    ) derives Schema

    final case class ContactPhoneDto(phone: Maybe[String] = Absent, `type`: Maybe[String] = Absent, wa_id: Maybe[String] = Absent)
        derives Schema

    final case class ContactEmailDto(email: Maybe[String] = Absent, `type`: Maybe[String] = Absent) derives Schema

    final case class ContactAddressDto(
        street: Maybe[String] = Absent,
        city: Maybe[String] = Absent,
        state: Maybe[String] = Absent,
        zip: Maybe[String] = Absent,
        country: Maybe[String] = Absent,
        country_code: Maybe[String] = Absent,
        `type`: Maybe[String] = Absent
    ) derives Schema

    final case class ContactOrgDto(company: Maybe[String] = Absent, department: Maybe[String] = Absent, title: Maybe[String] = Absent)
        derives Schema
    final case class ContactUrlDto(url: Maybe[String] = Absent, `type`: Maybe[String] = Absent) derives Schema

    final case class ContactDto(
        name: ContactNameDto,
        phones: Maybe[Chunk[ContactPhoneDto]] = Absent,
        emails: Maybe[Chunk[ContactEmailDto]] = Absent,
        addresses: Maybe[Chunk[ContactAddressDto]] = Absent,
        org: Maybe[ContactOrgDto] = Absent,
        urls: Maybe[Chunk[ContactUrlDto]] = Absent,
        birthday: Maybe[String] = Absent
    ) derives Schema

    // interactive: the codec builds these from the Interactive ADT. ActionDto holds the
    // union of action shapes; only the fields a given interactive type uses are populated.
    final case class HeaderDto(
        `type`: String,
        text: Maybe[String] = Absent,
        image: Maybe[MediaBody] = Absent,
        video: Maybe[MediaBody] = Absent,
        document: Maybe[MediaBody] = Absent
    ) derives Schema
    final case class TextOnly(text: String) derives Schema
    final case class RowDto(id: String, title: String, description: Maybe[String] = Absent) derives Schema
    final case class SectionDto(title: String, rows: Maybe[Chunk[RowDto]] = Absent, product_items: Maybe[Chunk[ProductItemDto]] = Absent)
        derives Schema
    final case class ProductItemDto(product_retailer_id: String) derives Schema
    final case class ReplyDto(id: String, title: String) derives Schema
    final case class ButtonDto(`type`: String, reply: ReplyDto) derives Schema
    final case class FlowPayloadDto(screen: String, data: Maybe[String] = Absent) derives Schema
    // The cta_url and flow `action.parameters` object is one flat-product DTO carrying the
    // superset of both shapes; the mapper populates only the fields the interactive type uses
    // (display_text/url for cta_url; the flow_* fields for flow), and Absent fields are
    // omitted, so the produced object is exactly the documented cta_url or flow parameters.
    final case class ActionParamsDto(
        display_text: Maybe[String] = Absent,
        url: Maybe[String] = Absent,
        flow_message_version: Maybe[String] = Absent,
        flow_token: Maybe[String] = Absent,
        flow_id: Maybe[String] = Absent,
        flow_name: Maybe[String] = Absent,
        flow_cta: Maybe[String] = Absent,
        flow_action: Maybe[String] = Absent,
        flow_action_payload: Maybe[FlowPayloadDto] = Absent,
        mode: Maybe[String] = Absent
    ) derives Schema
    final case class ActionDto(
        button: Maybe[String] = Absent,
        sections: Maybe[Chunk[SectionDto]] = Absent,
        buttons: Maybe[Chunk[ButtonDto]] = Absent,
        catalog_id: Maybe[String] = Absent,
        product_retailer_id: Maybe[String] = Absent,
        name: Maybe[String] = Absent,
        parameters: Maybe[ActionParamsDto] = Absent
    ) derives Schema
    final case class InteractiveBody(
        `type`: String,
        header: Maybe[HeaderDto] = Absent,
        body: Maybe[TextOnly] = Absent,
        footer: Maybe[TextOnly] = Absent,
        action: ActionDto
    ) derives Schema

    // template
    final case class LanguageDto(code: String, policy: Maybe[String] = Absent) derives Schema
    final case class ParameterDto(
        `type`: String,
        text: Maybe[String] = Absent,
        currency: Maybe[CurrencyDto] = Absent,
        date_time: Maybe[DateTimeDto] = Absent,
        image: Maybe[MediaBody] = Absent,
        document: Maybe[MediaBody] = Absent,
        video: Maybe[MediaBody] = Absent,
        payload: Maybe[String] = Absent
    ) derives Schema
    final case class CurrencyDto(fallback_value: String, code: String, amount_1000: Long) derives Schema
    final case class DateTimeDto(fallback_value: String) derives Schema
    final case class ComponentDto(
        `type`: String,
        sub_type: Maybe[String] = Absent,
        index: Maybe[Int] = Absent,
        parameters: Chunk[ParameterDto] = Chunk.empty
    ) derives Schema
    final case class TemplateBody(name: String, language: LanguageDto, components: Maybe[Chunk[ComponentDto]] = Absent) derives Schema

    final case class SendEnvelope(
        messaging_product: String,
        recipient_type: Maybe[String] = Absent,
        to: String,
        `type`: String,
        text: Maybe[TextBody] = Absent,
        image: Maybe[MediaBody] = Absent,
        video: Maybe[MediaBody] = Absent,
        document: Maybe[MediaBody] = Absent,
        audio: Maybe[MediaBody] = Absent,
        sticker: Maybe[MediaBody] = Absent,
        location: Maybe[LocationBody] = Absent,
        contacts: Maybe[Chunk[ContactDto]] = Absent,
        reaction: Maybe[ReactionBody] = Absent,
        interactive: Maybe[InteractiveBody] = Absent,
        template: Maybe[TemplateBody] = Absent,
        context: Maybe[ContextBody] = Absent
    ) derives Schema

    // mark-as-read / typing
    final case class TypingDto(`type`: String) derives Schema
    final case class StatusReadEnvelope(
        messaging_product: String,
        status: String,
        message_id: String,
        typing_indicator: Maybe[TypingDto] = Absent
    ) derives Schema

    final case class SuccessResponse(success: Boolean) derives Schema

    final case class SendResponseContact(input: Maybe[String] = Absent, wa_id: Maybe[String] = Absent) derives Schema
    final case class SendResponseMessage(id: String, message_status: Maybe[String] = Absent) derives Schema
    final case class SendResponse(
        messaging_product: Maybe[String] = Absent,
        contacts: Maybe[Chunk[SendResponseContact]] = Absent,
        messages: Chunk[SendResponseMessage] = Chunk.empty
    ) derives Schema

    final case class MediaIdResponse(id: String) derives Schema

    // The Cloud API returns file_size as either "12345" or 12345. A class rather than an opaque alias, so the Schema macro uses this
    // given instead of the Long primitive's.
    final case class FileSize(value: Long) derives CanEqual
    object FileSize:
        // r.string() throws ParseException without advancing when the current byte is a digit rather than a quote, so the catch falls
        // back to r.long(). A size is a non-negative whole number in ASCII digits: the string is checked character by character because
        // toLongOption also takes a sign and any Unicode digit. A rejection is kyo-schema's own ParseException (a string that is not
        // ASCII digits, or overflows a Long) or RangeException (a negative number), so it surfaces as a decode failure with a typed cause.
        // Throwing it is how a reader rejects: kyo-schema has no rejecting transform (`transform` takes total functions, and a `check`
        // runs only on `validate` and yields a ValidationException, not a DecodeException), and its own readers throw from `readFn` too.
        given Schema[FileSize] = Schema.init[FileSize](
            writeFn = (v, w) => w.long(v.value),
            readFn = r =>
                val quoted: Maybe[String] =
                    try Present(r.string())
                    catch case _: ParseException => Absent
                quoted match
                    case Present(text) =>
                        val digits: Maybe[Long] =
                            if text.nonEmpty && text.forall(c => c >= '0' && c <= '9') then Maybe.fromOption(text.toLongOption) else Absent
                        digits match
                            case Present(n) => FileSize(n)
                            case Absent     => throw ParseException(new Json(), text, "file size")(using r.frame)
                    case Absent =>
                        val n = r.long()
                        if n < 0 then throw RangeException(n, "file size", 0L, Long.MaxValue)(using r.frame)
                        FileSize(n)
                end match
        )
    end FileSize

    final case class MediaInfoResponse(
        messaging_product: Maybe[String] = Absent,
        url: String,
        mime_type: String,
        sha256: String,
        file_size: FileSize,
        id: String
    ) derives Schema

    final case class ErrorDataDto(messaging_product: Maybe[String] = Absent, details: Maybe[String] = Absent) derives Schema
    final case class ErrorDto(
        message: String,
        `type`: Maybe[String] = Absent,
        code: Int,
        error_subcode: Maybe[Int] = Absent,
        error_data: Maybe[ErrorDataDto] = Absent,
        fbtrace_id: Maybe[String] = Absent,
        error_user_title: Maybe[String] = Absent,
        error_user_msg: Maybe[String] = Absent
    ) derives Schema
    final case class ErrorEnvelope(error: ErrorDto) derives Schema

    // A statuses[].errors[] item: the webhook reference documents code, title, message (equal to title), error_data.details and href,
    // with no type, subcode or fbtrace_id, so it is not an ErrorDto.
    final case class StatusErrorDto(
        code: Int,
        title: Maybe[String] = Absent,
        message: Maybe[String] = Absent,
        error_data: Maybe[ErrorDataDto] = Absent,
        href: Maybe[String] = Absent
    ) derives Schema

    final case class InboundEnvelope(`object`: Maybe[String] = Absent, entry: Chunk[InboundEntry]) derives Schema
    final case class InboundEntry(id: String, changes: Chunk[InboundChange]) derives Schema
    // `value` stays a Structure.Value, and so does each message and status in it, so an item that does not decode is
    // delivered as Unknown with every key Meta sent while the items beside it decode on their own: Meta treats a 200
    // as delivered and never resends a batch the module could only partly read.
    final case class InboundChange(value: Structure.Value, field: String) derives Schema
    final case class InboundRawItems(
        messages: Maybe[Chunk[Structure.Value]] = Absent,
        statuses: Maybe[Chunk[Structure.Value]] = Absent
    ) derives Schema
    final case class InboundHeader(
        metadata: Maybe[MetadataDto] = Absent,
        contacts: Maybe[Chunk[InboundContactDto]] = Absent
    ) derives Schema
    final case class MetadataDto(display_phone_number: String, phone_number_id: String) derives Schema
    final case class InboundContactDto(profile: Maybe[ProfileDto] = Absent, wa_id: Maybe[String] = Absent) derives Schema
    final case class ProfileDto(name: Maybe[String] = Absent) derives Schema

    // Each inbound messages[] item is a flat-product DTO: a `type` discriminator plus one
    // optional sibling body per type (the same nested-sibling shape as outbound). The codec
    // reads `type` and the matching populated sibling; an unrecognized `type` leaves every
    // sibling Absent, which the codec maps to Content.Unknown.
    final case class InboundMessageDto(
        from: String,
        id: String,
        timestamp: String,
        `type`: String,
        text: Maybe[InboundTextDto] = Absent,
        image: Maybe[InboundMediaDto] = Absent,
        audio: Maybe[InboundMediaDto] = Absent,
        video: Maybe[InboundMediaDto] = Absent,
        document: Maybe[InboundMediaDto] = Absent,
        sticker: Maybe[InboundMediaDto] = Absent,
        location: Maybe[InboundLocationDto] = Absent,
        contacts: Maybe[Chunk[ContactDto]] = Absent,
        reaction: Maybe[ReactionBody] = Absent,
        button: Maybe[InboundButtonDto] = Absent,
        interactive: Maybe[InboundInteractiveDto] = Absent,
        order: Maybe[InboundOrderDto] = Absent,
        system: Maybe[InboundSystemDto] = Absent,
        context: Maybe[InboundContextDto] = Absent
    ) derives Schema
    final case class InboundTextDto(body: String) derives Schema
    final case class InboundMediaDto(
        id: String,
        mime_type: String,
        sha256: String,
        caption: Maybe[String] = Absent,
        filename: Maybe[String] = Absent,
        voice: Maybe[Boolean] = Absent
    ) derives Schema
    final case class InboundLocationDto(latitude: Double, longitude: Double, name: Maybe[String] = Absent, address: Maybe[String] = Absent)
        derives Schema
    final case class InboundButtonDto(payload: String, text: String) derives Schema
    final case class InboundReplyDto(id: String, title: String, description: Maybe[String] = Absent) derives Schema
    final case class InboundInteractiveDto(
        `type`: String,
        button_reply: Maybe[InboundReplyDto] = Absent,
        list_reply: Maybe[InboundReplyDto] = Absent
    ) derives Schema
    final case class InboundOrderDto(catalog_id: String, product_items: Chunk[InboundOrderItemDto] = Chunk.empty) derives Schema
    final case class InboundOrderItemDto(product_retailer_id: String) derives Schema
    final case class InboundSystemDto(body: String) derives Schema
    final case class InboundContextDto(from: String, id: String) derives Schema

    // Each statuses[] item carries the status string plus the optional status metadata; an
    // unrecognized status leaves the codec to map it to Status.Other(value).
    final case class InboundStatusDto(
        id: String,
        status: String,
        timestamp: String,
        recipient_id: String,
        conversation: Maybe[InboundConversationDto] = Absent,
        pricing: Maybe[InboundPricingDto] = Absent,
        errors: Chunk[StatusErrorDto] = Chunk.empty
    ) derives Schema
    final case class InboundConversationDto(id: String, expiration_timestamp: Maybe[String] = Absent, origin: InboundOriginDto)
        derives Schema
    final case class InboundOriginDto(`type`: String) derives Schema
    final case class InboundPricingDto(billable: Boolean, pricing_model: String, category: String, `type`: Maybe[String] = Absent)
        derives Schema

end Wire
