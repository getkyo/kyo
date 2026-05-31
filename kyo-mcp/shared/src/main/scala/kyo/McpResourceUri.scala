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

    // Phase 1 stub; Phase 3 replaces with Schema.stringSchema.transform through parse
    given Schema[McpResourceUri] = new Schema[McpResourceUri](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(v: McpResourceUri, w: kyo.Codec.Writer): Unit =
            throw new NotImplementedError("McpResourceUri.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): McpResourceUri =
            throw new NotImplementedError("McpResourceUri.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def getter(v: McpResourceUri): Maybe[Any]                = Maybe(v)
        @publicInBinary private[kyo] def setter(v: McpResourceUri, next: Any): McpResourceUri = v

    given CanEqual[McpResourceUri, McpResourceUri] = CanEqual.derived

end McpResourceUri
