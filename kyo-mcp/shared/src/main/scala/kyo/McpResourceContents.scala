package kyo

/** Sealed union of MCP resource content types returned from `resources/read`.
  *
  * Both leaves carry a typed `uri: McpResourceUri` field per INV-022 / Audit-A2.
  * `mimeType` is the opaque `McpMimeType` so all media-type-carrying surface stays typed.
  * Use the companion factory methods `text` and `blob` to construct values.
  *
  * The `given Schema[McpResourceContents]` hand-rolls a tagged-union schema discriminating on
  * `"type"` in `kyo/internal/McpContentSchema.scala`.
  */
sealed trait McpResourceContents derives CanEqual:
    def uri: McpResourceUri
    def mimeType: Maybe[McpMimeType]

object McpResourceContents:

    /** Text resource content. */
    final case class Text(
        uri: McpResourceUri,
        mimeType: Maybe[McpMimeType],
        text: String
    ) extends McpResourceContents

    /** Binary blob resource content encoded as base-64. */
    final case class Blob(
        uri: McpResourceUri,
        mimeType: Maybe[McpMimeType],
        blob: String
    ) extends McpResourceContents

    /** Constructs a text resource content value. */
    def text(uri: McpResourceUri, text: String, mimeType: Maybe[McpMimeType] = Absent): McpResourceContents =
        Text(uri, mimeType, text)

    /** Constructs a blob resource content value. */
    def blob(uri: McpResourceUri, blob: String, mimeType: Maybe[McpMimeType] = Absent): McpResourceContents =
        Blob(uri, mimeType, blob)

    // Hand-rolled tagged-union schema. Implementation in kyo/internal/McpContentSchema.scala.
    // Wire discriminator key: "type"; tags: "text" | "blob" (INV-006).
    given Schema[McpResourceContents] = internal.McpContentSchema.resourceContentsSchema

end McpResourceContents
