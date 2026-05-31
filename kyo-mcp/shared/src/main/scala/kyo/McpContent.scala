package kyo

/** Sealed union of MCP content types exchanged in tool results, sampling messages, and prompts.
  *
  * The four cases are `Text`, `Image`, `Audio`, and `EmbeddedResource`. Use the companion
  * factory methods `text`, `image`, `audio`, `resource` to construct values.
  *
  * The on-wire discriminator key is `"type"` with values `"text"` / `"image"` / `"audio"` /
  * `"resource"` (INV-006). The `given Schema[McpContent]` is a Phase 1 stub; Phase 3 replaces
  * it with a hand-rolled discriminator schema.
  */
sealed trait McpContent derives CanEqual

object McpContent:

    /** Plain-text content. */
    final case class Text(
        text: String,
        annotations: Maybe[Annotations] = Absent
    ) extends McpContent

    /** Base-64-encoded image content. */
    final case class Image(
        data: String,
        mimeType: String,
        annotations: Maybe[Annotations] = Absent
    ) extends McpContent

    /** Base-64-encoded audio content. */
    final case class Audio(
        data: String,
        mimeType: String,
        annotations: Maybe[Annotations] = Absent
    ) extends McpContent

    /** An embedded resource included inline in content. */
    final case class EmbeddedResource(
        resource: McpResourceContents,
        annotations: Maybe[Annotations] = Absent
    ) extends McpContent

    /** Optional display annotations for any content leaf. */
    final case class Annotations(
        audience: Maybe[Chunk[McpRole]] = Absent,
        priority: Maybe[Double] = Absent
    ) derives Schema, CanEqual

    /** Constructs a text content value. */
    def text(s: String, annotations: Maybe[Annotations] = Absent): McpContent = Text(s, annotations)

    /** Constructs an image content value. */
    def image(data: String, mimeType: String, annotations: Maybe[Annotations] = Absent): McpContent =
        Image(data, mimeType, annotations)

    /** Constructs an audio content value. */
    def audio(data: String, mimeType: String, annotations: Maybe[Annotations] = Absent): McpContent =
        Audio(data, mimeType, annotations)

    /** Constructs an embedded resource content value. */
    def resource(resource: McpResourceContents, annotations: Maybe[Annotations] = Absent): McpContent =
        EmbeddedResource(resource, annotations)

    // Hand-rolled discriminator ("type") schema. Implementation in kyo/internal/McpContentSchema.scala.
    // Wire discriminator key: "type"; tags: "text" | "image" | "audio" | "resource" (INV-006).
    given Schema[McpContent] = internal.McpContentSchema.contentSchema

end McpContent
