package kyo

/** Opaque type wrapping the MCP protocol version string.
  *
  * Users construct values via `parse`, which validates the version against the supported set.
  * The engine uses `fromWire` (private[kyo]) to decode versions without validation during
  * the handshake response path. No public `apply` exists per INV-025 / Audit-A5.
  *
  * @see [[McpProtocolVersion.parse]]
  * @see [[McpProtocolVersion.supported]]
  */
opaque type McpProtocolVersion = String

object McpProtocolVersion:

    /** The current MCP protocol version shipped by this library. */
    val current: McpProtocolVersion = "2025-06-18"

    /** The kyo-mcp library version string. */
    val kyoMcpVersion: String = "0.1.0"

    /** The set of MCP protocol versions this library accepts during handshake. */
    val supported: Set[McpProtocolVersion] = Set("2025-06-18")

    /** Returns `Present(v)` when `s` is a supported version string; `Absent` otherwise. */
    def parse(s: String): Maybe[McpProtocolVersion] =
        if supported.contains(s) then Present(s) else Absent

    /** Wire decoder: used by the engine to decode a protocol version without client-side validation. */
    private[kyo] def fromWire(s: String): McpProtocolVersion = s

    extension (v: McpProtocolVersion)
        /** Returns the underlying string value. */
        def asString: String = v

    // Phase 1 stub; Phase 3 replaces with Schema.stringSchema.transform(fromWire)(_.asString)
    given Schema[McpProtocolVersion] = new Schema[McpProtocolVersion](Seq.empty):
        import scala.annotation.publicInBinary
        @publicInBinary private[kyo] def serializeWrite(v: McpProtocolVersion, w: kyo.Codec.Writer): Unit =
            throw new NotImplementedError("McpProtocolVersion.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def serializeRead(r: kyo.Codec.Reader): McpProtocolVersion =
            throw new NotImplementedError("McpProtocolVersion.Schema stub: body filled in Phase 3")
        @publicInBinary private[kyo] def getter(v: McpProtocolVersion): Maybe[Any]                    = Maybe(v)
        @publicInBinary private[kyo] def setter(v: McpProtocolVersion, next: Any): McpProtocolVersion = v

    given CanEqual[McpProtocolVersion, McpProtocolVersion] = CanEqual.derived

end McpProtocolVersion
