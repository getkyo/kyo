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

    // ----- outbound send envelope (B common + per-type sibling bodies) -----

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

    // ----- outbound responses -----

    final case class SuccessResponse(success: Boolean) derives Schema

    final case class SendResponseContact(input: Maybe[String] = Absent, wa_id: Maybe[String] = Absent) derives Schema
    final case class SendResponseMessage(id: String, message_status: Maybe[String] = Absent) derives Schema
    final case class SendResponse(
        messaging_product: Maybe[String] = Absent,
        contacts: Maybe[Chunk[SendResponseContact]] = Absent,
        messages: Chunk[SendResponseMessage] = Chunk.empty
    ) derives Schema

    final case class MediaIdResponse(id: String) derives Schema

    // NumericString accepts either a quoted JSON string or an unquoted JSON number.
    // The Cloud API can return file_size as either "12345" or 12345.
    // Defined as a final class (not an opaque type alias) so that the Schema macro
    // does not treat it as a String primitive and bypasses the custom readFn.
    final class NumericString private (val value: String)
    object NumericString:
        def apply(s: String): NumericString = new NumericString(s)
        // Reads a JSON string or a JSON number and normalises to String.
        // r.string() throws ParseException (without advancing pos) when the current
        // byte is a digit rather than a quote; the catch falls back to r.long().
        given Schema[NumericString] = Schema.init[NumericString](
            writeFn = (v, w) => w.string(v.value),
            readFn = r =>
                try NumericString(r.string())
                catch case _: Exception => NumericString(r.long().toString)
        )
    end NumericString

    final case class MediaInfoResponse(
        messaging_product: Maybe[String] = Absent,
        url: String,
        mime_type: String,
        sha256: String,
        file_size: NumericString, // accepts "12345" or 12345
        id: String
    ) derives Schema

    // ----- error envelope -----

    final case class ErrorDataDto(messaging_product: Maybe[String] = Absent, details: Maybe[String] = Absent) derives Schema
    final case class ErrorDto(
        message: String,
        `type`: String,
        code: Int,
        error_subcode: Maybe[Int] = Absent,
        error_data: Maybe[ErrorDataDto] = Absent,
        fbtrace_id: Maybe[String] = Absent
    ) derives Schema
    final case class ErrorEnvelope(error: ErrorDto) derives Schema

    // ----- inbound webhook DTOs -----

    final case class InboundEnvelope(`object`: Maybe[String] = Absent, entry: Chunk[InboundEntry] = Chunk.empty) derives Schema
    final case class InboundEntry(id: String, changes: Chunk[InboundChange] = Chunk.empty) derives Schema
    final case class InboundChange(value: InboundValue, field: String) derives Schema
    final case class InboundValue(
        messaging_product: Maybe[String] = Absent,
        metadata: Maybe[MetadataDto] = Absent,
        contacts: Maybe[Chunk[InboundContactDto]] = Absent,
        messages: Maybe[Chunk[InboundMessageDto]] = Absent,
        statuses: Maybe[Chunk[InboundStatusDto]] = Absent
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
        errors: Chunk[ErrorDto] = Chunk.empty
    ) derives Schema
    final case class InboundConversationDto(id: String, expiration_timestamp: Maybe[Long] = Absent, origin: InboundOriginDto) derives Schema
    final case class InboundOriginDto(`type`: String) derives Schema
    final case class InboundPricingDto(billable: Boolean, pricing_model: String, category: String, `type`: Maybe[String] = Absent)
        derives Schema

end Wire
