package kyo

/** Sealed union of MCP content types exchanged in tool results, sampling messages, and prompts.
  *
  * The five cases are `Text`, `Image`, `Audio`, `EmbeddedResource`, and `ResourceLink`.
  * Use the companion factory methods `text`, `image`, `audio`, `resource` to construct values.
  *
  * The on-wire discriminator key is `"type"` with values `"text"` / `"image"` / `"audio"` /
  * `"resource"` / `"resource_link"` (INV-006). The `given Schema[McpContent]` hand-rolls the
  * discriminator schema in `kyo/internal/McpContentSchema.scala`.
  *
  * Optional record-typed fields (notably `annotations`) follow the noop pattern: the parameter
  * type is the concrete record and `Annotations.noop` is the default; the wire encoder omits
  * the field when the runtime value equals `.noop`.
  */
sealed trait McpContent derives CanEqual

object McpContent:

    /** Plain-text content. */
    final case class Text(
        text: String,
        annotations: Annotations = Annotations.noop
    ) extends McpContent

    /** Base-64-encoded image content. */
    final case class Image(
        data: String,
        mimeType: McpMimeType,
        annotations: Annotations = Annotations.noop
    ) extends McpContent

    /** Base-64-encoded audio content. */
    final case class Audio(
        data: String,
        mimeType: McpMimeType,
        annotations: Annotations = Annotations.noop
    ) extends McpContent

    /** An embedded resource included inline in content. */
    final case class EmbeddedResource(
        resource: McpResourceContents,
        annotations: Annotations = Annotations.noop
    ) extends McpContent

    /** A link to a resource that the client can retrieve.
      * INV-022: `uri` is typed `McpResourceUri`, not raw `String`.
      */
    final case class ResourceLink(
        uri: McpResourceUri,
        name: String,
        description: Maybe[String] = Absent,
        mimeType: Maybe[McpMimeType] = Absent,
        annotations: Annotations = Annotations.noop
    ) extends McpContent derives CanEqual

    /** Optional display annotations for any content leaf.
      *
      * `Annotations.noop` is the empty record used as the default parameter for every content
      * factory; the wire schema omits the `annotations` field when the value equals `.noop`.
      */
    final case class Annotations(
        audience: Maybe[Chunk[McpRole]] = Absent,
        priority: Maybe[Double] = Absent
    ) derives Schema, CanEqual

    object Annotations:
        /** The empty annotations record used as the default for every content factory. */
        val noop: Annotations = Annotations()
    end Annotations

    /** Constructs a text content value. */
    def text(s: String, annotations: Annotations = Annotations.noop): McpContent = Text(s, annotations)

    /** Constructs an image content value. */
    def image(data: String, mimeType: McpMimeType, annotations: Annotations = Annotations.noop): McpContent =
        Image(data, mimeType, annotations)

    /** Constructs an audio content value. */
    def audio(data: String, mimeType: McpMimeType, annotations: Annotations = Annotations.noop): McpContent =
        Audio(data, mimeType, annotations)

    /** Constructs an embedded resource content value. */
    def resource(resource: McpResourceContents, annotations: Annotations = Annotations.noop): McpContent =
        EmbeddedResource(resource, annotations)

    // Hand-rolled discriminator ("type") schema. Implementation in kyo/internal/McpContentSchema.scala.
    // Wire discriminator key: "type"; tags: "text" | "image" | "audio" | "resource" (INV-006).
    given Schema[McpContent] = internal.McpContentSchema.contentSchema

end McpContent
