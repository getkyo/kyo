package kyo

/** Identifies a client or server in the LSP initialize handshake.
  *
  * The `name` and `version` fields correspond to the `clientInfo` / `serverInfo` shape in the
  * LSP 3.17 initialize exchange. Both are free-form strings; no version-format constraint is
  * imposed at this level. The engine sets `serverInfo.version = "3.17"` in the
  * `InitializeResult` response (INV-091), overriding the value from `LspConfig.serverInfo`.
  *
  * The `title` field is an optional human-readable display name; it is omitted from the wire
  * when `Absent`.
  *
  * @param name    human-readable name of the implementation
  * @param version version string; defaults to `"0.0.0"`
  * @param title   optional display title for the implementation
  */
final case class LspInfo(name: String, version: String = "0.0.0", title: Maybe[String] = Absent)
    derives Schema, CanEqual
