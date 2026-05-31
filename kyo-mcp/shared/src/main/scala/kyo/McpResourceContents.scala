package kyo

/** Sealed union of MCP resource content types returned from `resources/read`.
  *
  * Both leaves carry a typed `uri: McpResourceUri` field per INV-022 / Audit-A2.
  * Use the companion factory methods `text` and `blob` to construct values.
  *
  * The `given Schema[McpResourceContents]` is a Phase 1 stub; Phase 3 replaces it with a
  * hand-rolled tagged-union schema discriminating on `"type"`.
  */
sealed trait McpResourceContents derives CanEqual:
    def uri: McpResourceUri
    def mimeType: Maybe[String]

object McpResourceContents:

    /** Text resource content. */
    final case class Text(
        uri: McpResourceUri,
        mimeType: Maybe[String],
        text: String
    ) extends McpResourceContents

    /** Binary blob resource content encoded as base-64. */
    final case class Blob(
        uri: McpResourceUri,
        mimeType: Maybe[String],
        blob: String
    ) extends McpResourceContents

    /** Constructs a text resource content value. */
    def text(uri: McpResourceUri, text: String, mimeType: Maybe[String] = Absent): McpResourceContents =
        Text(uri, mimeType, text)

    /** Constructs a blob resource content value. */
    def blob(uri: McpResourceUri, blob: String, mimeType: Maybe[String] = Absent): McpResourceContents =
        Blob(uri, mimeType, blob)

    // Hand-rolled tagged-union schema. Implementation in kyo/internal/McpContentSchema.scala.
    // Wire discriminator key: "type"; tags: "text" | "blob" (INV-006).
    given Schema[McpResourceContents] = internal.McpContentSchema.resourceContentsSchema

end McpResourceContents
