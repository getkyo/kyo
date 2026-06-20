package kyo

import kyo.internal.SlackRawJson

/** A typed Block Kit model for building Slack message and view layouts ; no raw JSON. Build a
  * `Chunk[SlackBlock]` and pass it to `SlackMessage`/`SlackView`; the framework renders it to
  * the Block Kit wire shape. The covered surface is the common subset: `Section`, `Header`,
  * `Divider`, `Context`, `Actions`, `Input`, and `Image` blocks, with `Button`, `TextInput`,
  * and `Select` elements and `Text` objects. `Raw` is the single escape for a block type not
  * yet modeled: it carries that one block's JSON, validated when the message is sent.
  */
sealed trait SlackBlock derives CanEqual

object SlackBlock:

    /** A Block Kit text object: `Plain` for `plain_text`, `Markdown` for `mrkdwn`. */
    enum Text derives CanEqual:
        case Plain(text: String, emoji: Boolean = true)
        case Markdown(text: String)
    end Text

    /** An interactive or display element placed in a section accessory, an `Actions` block,
      * or an `Input` block.
      */
    sealed trait Element derives CanEqual
    object Element:
        /** A button. `style` is `"primary"`/`"danger"` when set; `url` makes it a link button. */
        case class Button(
            text: String,
            actionId: SlackId.ActionId,
            value: Maybe[String] = Absent,
            style: Maybe[String] = Absent,
            url: Maybe[String] = Absent
        ) extends Element

        /** A plain-text input (the element of an `Input` block). */
        case class TextInput(
            actionId: SlackId.ActionId,
            multiline: Boolean = false,
            placeholder: Maybe[String] = Absent
        ) extends Element

        /** A static single-select menu over `options`. */
        case class Select(
            actionId: SlackId.ActionId,
            placeholder: String,
            options: Chunk[Element.Option]
        ) extends Element

        /** One `Select` option: a label shown to the user and the `value` delivered on submit. */
        case class Option(text: String, value: String) derives CanEqual
    end Element

    /** A section with a text body and an optional accessory element. */
    case class Section(text: Text, blockId: Maybe[SlackId.BlockId] = Absent, accessory: Maybe[Element] = Absent) extends SlackBlock

    /** A header (large bold `plain_text`). */
    case class Header(text: String) extends SlackBlock

    /** A horizontal divider. */
    case class Divider() extends SlackBlock

    /** A context block: small text elements rendered together. */
    case class Context(elements: Chunk[Text]) extends SlackBlock

    /** A row of interactive elements (buttons, selects). */
    case class Actions(elements: Chunk[Element], blockId: Maybe[SlackId.BlockId] = Absent) extends SlackBlock

    /** An input block collecting a value; requires the view to carry a submit button. */
    case class Input(label: String, element: Element, blockId: Maybe[SlackId.BlockId] = Absent, optional: Boolean = false)
        extends SlackBlock

    /** An image block. */
    case class Image(imageUrl: String, altText: String, title: Maybe[String] = Absent) extends SlackBlock

    /** The escape for a block type the typed model does not cover: `json` is that one block's
      * raw JSON object, validated (and surfaced as `SlackDecodeException` if malformed) when the
      * containing message/view is sent.
      */
    case class Raw(json: String) extends SlackBlock

    /** Concise builders for assembling blocks without naming the case classes. Import
      * `SlackBlock.dsl.*` and write `blocks(section("*hi*"), divider, actions(button("Go", "go")))`.
      * The case-class model remains canonical; these are sugar over it, accepting plain `String`
      * ids for brevity (wrapped into the opaque `SlackId.*` types).
      */
    object dsl:
        /** Collect blocks into the `Chunk[SlackBlock]` that `SlackMessage`/`SlackView` take. */
        def blocks(bs: SlackBlock*): Chunk[SlackBlock] = Chunk.from(bs)

        def markdown(text: String): Text.Markdown = Text.Markdown(text)
        def plain(text: String): Text.Plain       = Text.Plain(text)

        /** A section with a markdown body. */
        def section(text: String): Section = Section(Text.Markdown(text))

        /** A section with an explicit text object. */
        def section(text: Text): Section = Section(text)

        /** A section with a markdown body and an accessory element. */
        def section(text: String, accessory: Element): Section = Section(Text.Markdown(text), accessory = Present(accessory))

        def header(text: String): Header                    = Header(text)
        def divider: Divider                                = Divider()
        def context(texts: Text*): Context                  = Context(Chunk.from(texts))
        def actions(elements: Element*): Actions            = Actions(Chunk.from(elements))
        def input(label: String, element: Element): Input   = Input(label, element)
        def image(imageUrl: String, altText: String): Image = Image(imageUrl, altText)

        def button(text: String, actionId: String): Element.Button =
            Element.Button(text, SlackId.ActionId(actionId))
        def textInput(actionId: String, multiline: Boolean = false): Element.TextInput =
            Element.TextInput(SlackId.ActionId(actionId), multiline)
        def select(actionId: String, placeholder: String, options: Element.Option*): Element.Select =
            Element.Select(SlackId.ActionId(actionId), placeholder, Chunk.from(options))
        def option(text: String, value: String): Element.Option = Element.Option(text, value)
    end dsl

    import Structure.Value

    // TODO: blocks are rendered by building a Structure.Value AST by hand rather than deriving a
    // Schema, because kyo-schema's derived sum/enum serialization emits a tagged shape (the variant
    // name as the tag), not Slack's untagged `{"type":"section",...}` wire format. Replace with
    // plain `derives Schema` once kyo-schema supports untagged discriminated unions (a custom
    // discriminator field with custom per-variant tag values).

    /** Render blocks to the native JSON array carrier. Builds the `Structure.Value` AST directly
      * (no JSON string assembly); a `Raw` block's JSON is parsed and spliced, failing with a
      * typed `SlackDecodeException` when malformed.
      */
    private[kyo] def encode(blocks: Chunk[SlackBlock])(using Frame): SlackRawJson < Abort[SlackException] =
        Kyo.foreach(blocks)(blockValue).map(vs => SlackRawJson(Value.Sequence(vs)))

    /** A `plain_text` object as the native value carrier, for view title/submit/close labels. */
    private[kyo] def plainText(label: String): SlackRawJson =
        SlackRawJson(Value.Record(Chunk("type" -> Value.Str("plain_text"), "text" -> Value.Str(label))))

    private def rec(fields: scala.collection.immutable.Seq[scala.Option[(String, Value)]]): Value =
        Value.Record(Chunk.from(fields.flatten))

    private def str(key: String, v: String): scala.Option[(String, Value)]           = Some(key -> Value.Str(v))
    private def bool(key: String, v: Boolean): scala.Option[(String, Value)]         = Some(key -> Value.Bool(v))
    private def node(key: String, v: Value): scala.Option[(String, Value)]           = Some(key -> v)
    private def optStr(key: String, v: Maybe[String]): scala.Option[(String, Value)] = v.fold(None)(s => Some(key -> Value.Str(s)))
    private def optBlock(key: String, v: Maybe[SlackId.BlockId]): scala.Option[(String, Value)] =
        v.fold(None)(b => Some(key -> Value.Str(b.value)))

    private def textValue(t: Text): Value =
        t match
            case Text.Plain(s, emoji) => rec(List(str("type", "plain_text"), str("text", s), bool("emoji", emoji)))
            case Text.Markdown(s)     => rec(List(str("type", "mrkdwn"), str("text", s)))

    private def elementValue(e: Element): Value =
        e match
            case Element.Button(t, actionId, value, style, url) =>
                rec(List(
                    str("type", "button"),
                    node("text", textValue(Text.Plain(t))),
                    str("action_id", actionId.value),
                    optStr("value", value),
                    optStr("style", style),
                    optStr("url", url)
                ))
            case Element.TextInput(actionId, multiline, placeholder) =>
                rec(List(
                    str("type", "plain_text_input"),
                    str("action_id", actionId.value),
                    bool("multiline", multiline),
                    placeholder.fold(None)(p => node("placeholder", textValue(Text.Plain(p))))
                ))
            case Element.Select(actionId, placeholder, options) =>
                rec(List(
                    str("type", "static_select"),
                    str("action_id", actionId.value),
                    node("placeholder", textValue(Text.Plain(placeholder))),
                    node(
                        "options",
                        Value.Sequence(options.map(o => rec(List(node("text", textValue(Text.Plain(o.text))), str("value", o.value)))))
                    )
                ))

    private def blockValue(b: SlackBlock)(using Frame): Value < Abort[SlackException] =
        b match
            case Raw(json) =>
                SlackRawJson.parse(json, "raw block").map(_.ast)
            case Section(t, blockId, accessory) =>
                rec(List(
                    str("type", "section"),
                    node("text", textValue(t)),
                    optBlock("block_id", blockId),
                    accessory.fold(None)(e => node("accessory", elementValue(e)))
                ))
            case Header(t) =>
                rec(List(str("type", "header"), node("text", textValue(Text.Plain(t)))))
            case Divider() =>
                rec(List(str("type", "divider")))
            case Context(elements) =>
                rec(List(str("type", "context"), node("elements", Value.Sequence(elements.map(textValue)))))
            case Actions(elements, blockId) =>
                rec(List(
                    str("type", "actions"),
                    node("elements", Value.Sequence(elements.map(elementValue))),
                    optBlock("block_id", blockId)
                ))
            case Input(label, el, blockId, optional) =>
                rec(List(
                    str("type", "input"),
                    node("label", textValue(Text.Plain(label))),
                    node("element", elementValue(el)),
                    optBlock("block_id", blockId),
                    bool("optional", optional)
                ))
            case Image(imageUrl, altText, title) =>
                rec(List(
                    str("type", "image"),
                    str("image_url", imageUrl),
                    str("alt_text", altText),
                    title.fold(None)(t => node("title", textValue(Text.Plain(t))))
                ))

end SlackBlock
