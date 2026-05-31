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

    // Phase 1 stub; Phase 3 replaces with Schema.stringSchema.transform through parse
    given Schema[McpResourceUriTemplate] = new Schema[McpResourceUriTemplate](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(v: McpResourceUriTemplate, w: kyo.Codec.Writer): Unit =
            throw new NotImplementedError("McpResourceUriTemplate.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): McpResourceUriTemplate =
            throw new NotImplementedError("McpResourceUriTemplate.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def getter(v: McpResourceUriTemplate): Maybe[Any]                        = Maybe(v)
        @publicInBinary private[kyo] def setter(v: McpResourceUriTemplate, next: Any): McpResourceUriTemplate = v

    given CanEqual[McpResourceUriTemplate, McpResourceUriTemplate] = CanEqual.derived

end McpResourceUriTemplate
