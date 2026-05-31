package kyo

/** Opaque type wrapping an IANA media type string.
  *
  * Use `parse` for user-supplied media types that require validation against the RFC 6838 token /
  * token grammar with optional parameters. Use `apply` at trusted call sites where the value is
  * known valid. The wire decoder admits any string via `fromWire` (private[kyo]) so the codec
  * tolerates non-conforming peer payloads; client-side validation is at the user `parse` callsite.
  *
  * Replaces every public-surface `String` / `Maybe[String]` `mimeType` field across the module
  * (`McpContent.Image`, `McpContent.Audio`, `McpResourceContents.*`, `McpRoute.ResourceMeta`,
  * `McpRoute.ResourceTemplateMeta`, factory parameters).
  *
  * @see [[McpMimeType.parse]]
  */
opaque type McpMimeType = String

object McpMimeType:

    // RFC 6838: type "/" subtype, each token using restricted characters, with optional ";" params.
    private val mimePattern = "^[a-zA-Z0-9!#$&^_.+-]+/[a-zA-Z0-9!#$&^_.+-]+(;.*)?$".r

    /** Returns `Present(m)` when `s` is a valid RFC 6838 media type with optional parameters;
      * `Absent` otherwise. Rejects empty input, missing slash, and malformed tokens.
      */
    def parse(s: String): Maybe[McpMimeType] =
        if s.nonEmpty && mimePattern.matches(s) then Present(s) else Absent

    /** Trusted call-site constructor; not gated by validation. */
    def apply(s: String): McpMimeType = s

    /** Wire decoder: used by the codec to decode a media type without client-side validation. */
    private[kyo] def fromWire(s: String): McpMimeType = s

    extension (m: McpMimeType)
        /** Returns the underlying string value. */
        def asString: String = m

    // Uses `fromWire` (private[kyo] total constructor) so the codec accepts any wire-received string.
    // User-supplied validation happens at the `parse` callsite, not at the codec level.
    given Schema[McpMimeType] = Schema.stringSchema.transform[McpMimeType](fromWire)(_.asString)

    given CanEqual[McpMimeType, McpMimeType] = CanEqual.derived

end McpMimeType
