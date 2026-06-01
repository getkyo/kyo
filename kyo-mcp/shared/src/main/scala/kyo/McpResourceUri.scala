package kyo

/** Opaque type wrapping a resource URI string.
  *
  * Use `parse` for user-supplied URIs that require validation (rejects blank / whitespace-only
  * strings). Use `apply` at trusted call sites within the library where the value is known valid.
  * INV-022: all public surface carrying resource identifiers uses this type, never raw `String`.
  *
  * @see [[McpResourceUri.parse]]
  */
opaque type McpResourceUri = String

object McpResourceUri:

    /** Returns `Present(uri)` if `s` is non-empty and not purely whitespace; `Absent` otherwise. */
    def parse(s: String): Maybe[McpResourceUri] =
        if s.nonEmpty && !s.forall(_.isWhitespace) then Present(s) else Absent

    /** Trusted call-site constructor; not gated by validation. */
    def apply(s: String): McpResourceUri = s

    extension (u: McpResourceUri)
        /** Returns the underlying string value. */
        def asString: String = u

    // Uses `apply` (total constructor) so the codec accepts any wire-received string.
    // Blank-rejection is at the user `parse` callsite, not at the codec level (INV-022).
    given Schema[McpResourceUri] = Schema.stringSchema.transform[McpResourceUri](apply)(_.asString)

    given CanEqual[McpResourceUri, McpResourceUri] = CanEqual.derived

    /** Opaque type wrapping an RFC 6570 URI template string.
      *
      * `parse` enforces two minimal constraints: the string must be non-empty and must contain
      * at least one `{` character (the RFC 6570 template expression open-brace). Use `apply`
      * at trusted call sites.
      * INV-022: all public surface carrying URI templates uses this type, never raw `String`.
      *
      * @see [[McpResourceUri.Template.parse]]
      */
    opaque type Template = String

    object Template:

        /** Returns `Present(t)` if `s` is non-empty and contains `{`; `Absent` otherwise. */
        def parse(s: String): Maybe[Template] =
            if s.nonEmpty && s.contains('{') then Present(s) else Absent

        /** Trusted call-site constructor; not gated by validation. */
        def apply(s: String): Template = s

        extension (t: Template)
            /** Returns the underlying string value. */
            def asString: String = t

        // Uses `apply` (total constructor) so the codec accepts any wire-received string (INV-022).
        given Schema[Template] = Schema.stringSchema.transform[Template](apply)(_.asString)

        given CanEqual[Template, Template] = CanEqual.derived

    end Template

end McpResourceUri
