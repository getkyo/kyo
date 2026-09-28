package kyo

import kyo.internal.slack.RawJson

/** A typed Block Kit model for building Slack message and view layouts, with no raw JSON. Build a
  * `Chunk[SlackBlock]` and pass it to `SlackMessage`/`SlackView`; the framework renders it to
  * the Block Kit wire shape. The covered surface is the common subset: `Section`, `Header`,
  * `Divider`, `Context`, `Actions`, `Input`, and `Image` blocks, with `Button`, `TextInput`,
  * and `Select` elements and `Text` objects. `Raw` is the single escape for a block type not
  * yet modeled: it carries that one block's JSON, validated when the `Raw` is built.
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
        /** A button. `style` colors it, `Absent` for Slack's default; `url` makes it a link button. */
        final case class Button(
            text: String,
            actionId: SlackId.ActionId,
            value: Maybe[String] = Absent,
            style: Maybe[ButtonStyle] = Absent,
            url: Maybe[HttpUrl] = Absent
        ) extends Element

        /** The two button styles Slack accepts: `Primary` (green) and `Danger` (red). */
        enum ButtonStyle derives CanEqual:
            case Primary, Danger

        /** A plain-text input (the element of an `Input` block). */
        final case class TextInput(
            actionId: SlackId.ActionId,
            multiline: Boolean = false,
            placeholder: Maybe[String] = Absent
        ) extends Element

        /** A static single-select menu over `options`. */
        final case class Select(
            actionId: SlackId.ActionId,
            placeholder: String,
            options: Chunk[Element.SelectOption]
        ) extends Element

        /** One `Select` option: a label shown to the user and the `value` delivered on submit. */
        final case class SelectOption(text: String, value: String) derives CanEqual
    end Element

    /** A section with a text body and an optional accessory element. */
    final case class Section(text: Text, blockId: Maybe[SlackId.BlockId] = Absent, accessory: Maybe[Element] = Absent) extends SlackBlock

    /** A header (large bold `plain_text`). */
    final case class Header(text: String) extends SlackBlock

    /** A horizontal divider. */
    final case class Divider() extends SlackBlock

    /** A context block: small text elements rendered together. */
    final case class Context(elements: Chunk[Text]) extends SlackBlock

    /** A row of interactive elements (buttons, selects). */
    final case class Actions(elements: Chunk[Element], blockId: Maybe[SlackId.BlockId] = Absent) extends SlackBlock

    /** An input block collecting a value; requires the view to carry a submit button. */
    final case class Input(label: String, element: Element, blockId: Maybe[SlackId.BlockId] = Absent, optional: Boolean = false)
        extends SlackBlock

    /** An image block. */
    final case class Image(imageUrl: HttpUrl, altText: String, title: Maybe[String] = Absent) extends SlackBlock

    /** The escape for a block type the typed model does not cover: `json` is that one block's
      * raw JSON object. It is parsed when the `Raw` is built, and text that is not an RFC 8259 JSON
      * object panics with [[kyo.SlackInvalidRawBlockException]], so sending a message never fails on it.
      */
    final case class Raw(json: String)(using Frame) extends SlackBlock:
        private[kyo] val parsed: Structure.Value =
            RawJson.parse(json) match
                case Result.Success(record: Structure.Value.Record) => record
                case Result.Success(_)                              =>
                    throw SlackInvalidRawBlockException(
                        json.indexWhere(c => c != ' ' && c != '\t' && c != '\n' && c != '\r'),
                        SlackInvalidRawBlockException.Problem.NotAnObject
                    )
                case Result.Failure(f) => throw SlackInvalidRawBlockException(f.position, f.problem)
    end Raw

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

        def header(text: String): Header                     = Header(text)
        def divider: Divider                                 = Divider()
        def context(texts: Text*): Context                   = Context(Chunk.from(texts))
        def actions(elements: Element*): Actions             = Actions(Chunk.from(elements))
        def input(label: String, element: Element): Input    = Input(label, element)
        def image(imageUrl: HttpUrl, altText: String): Image = Image(imageUrl, altText)

        def button(text: String, actionId: String): Element.Button =
            Element.Button(text, SlackId.ActionId(actionId))
        def textInput(actionId: String, multiline: Boolean = false): Element.TextInput =
            Element.TextInput(SlackId.ActionId(actionId), multiline)
        def select(actionId: String, placeholder: String, options: Element.SelectOption*): Element.Select =
            Element.Select(SlackId.ActionId(actionId), placeholder, Chunk.from(options))
        def option(text: String, value: String): Element.SelectOption = Element.SelectOption(text, value)
    end dsl

    import Structure.Value

    /** Render blocks to their Block Kit JSON value. Each case is mapped to Slack's shape by hand because
      * the public model is not that shape: a `Header`'s text and an `Input`'s label are `String`s that
      * Slack reads as `plain_text` objects, and a `Raw` block splices the value parsed when it was built.
      */
    private[kyo] def encode(blocks: Chunk[SlackBlock]): Value =
        Value.Sequence(blocks.map(blockValue))

    /** A `plain_text` object, for view title/submit/close labels. */
    private[kyo] def plainText(label: String): Value =
        Value.Record(Chunk("type" -> Value.Str("plain_text"), "text" -> Value.Str(label)))

    private def rec(fields: Maybe[(String, Value)]*): Value =
        Value.Record(Chunk.from(fields).flatMap(_.toChunk))

    private def str(key: String, v: String): Maybe[(String, Value)]                      = Present(key -> Value.Str(v))
    private def bool(key: String, v: Boolean): Maybe[(String, Value)]                    = Present(key -> Value.Bool(v))
    private def node(key: String, v: Value): Maybe[(String, Value)]                      = Present(key -> v)
    private def optStr(key: String, v: Maybe[String]): Maybe[(String, Value)]            = v.map(s => key -> Value.Str(s))
    private def optNode(key: String, v: Maybe[Value]): Maybe[(String, Value)]            = v.map(key -> _)
    private def optBlock(key: String, v: Maybe[SlackId.BlockId]): Maybe[(String, Value)] =
        v.map(b => key -> Value.Str(b.value))

    private def textValue(t: Text): Value =
        t match
            case Text.Plain(s, emoji) => rec(str("type", "plain_text"), str("text", s), bool("emoji", emoji))
            case Text.Markdown(s)     => rec(str("type", "mrkdwn"), str("text", s))

    private def elementValue(e: Element): Value =
        e match
            case Element.Button(t, actionId, value, style, url) =>
                rec(
                    str("type", "button"),
                    node("text", textValue(Text.Plain(t))),
                    str("action_id", actionId.value),
                    optStr("value", value),
                    optStr(
                        "style",
                        style.map {
                            case Element.ButtonStyle.Primary => "primary"
                            case Element.ButtonStyle.Danger  => "danger"
                        }
                    ),
                    optStr("url", url.map(_.full))
                )
            case Element.TextInput(actionId, multiline, placeholder) =>
                rec(
                    str("type", "plain_text_input"),
                    str("action_id", actionId.value),
                    bool("multiline", multiline),
                    optNode("placeholder", placeholder.map(p => textValue(Text.Plain(p))))
                )
            case Element.Select(actionId, placeholder, options) =>
                rec(
                    str("type", "static_select"),
                    str("action_id", actionId.value),
                    node("placeholder", textValue(Text.Plain(placeholder))),
                    node(
                        "options",
                        Value.Sequence(options.map(o => rec(node("text", textValue(Text.Plain(o.text))), str("value", o.value))))
                    )
                )

    private def blockValue(b: SlackBlock): Value =
        b match
            case raw: Raw =>
                raw.parsed
            case Section(t, blockId, accessory) =>
                rec(
                    str("type", "section"),
                    node("text", textValue(t)),
                    optBlock("block_id", blockId),
                    optNode("accessory", accessory.map(elementValue))
                )
            case Header(t) =>
                rec(str("type", "header"), node("text", textValue(Text.Plain(t))))
            case Divider() =>
                rec(str("type", "divider"))
            case Context(elements) =>
                rec(str("type", "context"), node("elements", Value.Sequence(elements.map(textValue))))
            case Actions(elements, blockId) =>
                rec(
                    str("type", "actions"),
                    node("elements", Value.Sequence(elements.map(elementValue))),
                    optBlock("block_id", blockId)
                )
            case Input(label, el, blockId, optional) =>
                rec(
                    str("type", "input"),
                    node("label", textValue(Text.Plain(label))),
                    node("element", elementValue(el)),
                    optBlock("block_id", blockId),
                    bool("optional", optional)
                )
            case Image(imageUrl, altText, title) =>
                rec(
                    str("type", "image"),
                    str("image_url", imageUrl.full),
                    str("alt_text", altText),
                    optNode("title", title.map(t => textValue(Text.Plain(t))))
                )

end SlackBlock
