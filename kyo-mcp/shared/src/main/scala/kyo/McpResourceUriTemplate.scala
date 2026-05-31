package kyo

/** Opaque type wrapping an RFC 6570 URI template string.
  *
  * `parse` enforces two minimal constraints: the string must be non-empty and must contain
  * at least one `{` character (the RFC 6570 template expression open-brace). Use `apply`
  * at trusted call sites.
  * INV-022: all public surface carrying URI templates uses this type, never raw `String`.
  *
  * @see [[McpResourceUriTemplate.parse]]
  */
opaque type McpResourceUriTemplate = String

object McpResourceUriTemplate:

    /** Returns `Present(t)` if `s` is non-empty and contains `{`; `Absent` otherwise. */
    def parse(s: String): Maybe[McpResourceUriTemplate] =
        if s.nonEmpty && s.contains('{') then Present(s) else Absent

    /** Trusted call-site constructor; not gated by validation. */
    def apply(s: String): McpResourceUriTemplate = s

    extension (t: McpResourceUriTemplate)
        /** Returns the underlying string value. */
        def asString: String = t

    // Uses `apply` (total constructor) so the codec accepts any wire-received string (INV-022).
    given Schema[McpResourceUriTemplate] = Schema.stringSchema.transform[McpResourceUriTemplate](apply)(_.asString)

    given CanEqual[McpResourceUriTemplate, McpResourceUriTemplate] = CanEqual.derived

end McpResourceUriTemplate
