package kyo

/** Identifies a client or server in the LSP initialize handshake.
  *
  * The `name` and `version` fields correspond to the `clientInfo` / `serverInfo` shape in the
  * LSP 3.17 initialize exchange. Both are free-form strings; no version-format constraint is
  * imposed at this level.
  *
  * `version` is the IMPLEMENTATION version of this client or server (e.g. "1.0.0"), not the
  * LSP specification version. The LSP spec version ("3.17") is a separate wire field on the
  * `InitializeResult` envelope and is always fixed to `LspConfig.SpecVersion`. Ship a real
  * implementation version string in production code; the default `"0.0.0"` is a safe placeholder.
  *
  * The `title` field is an optional human-readable display name; it is omitted from the wire
  * when `Absent`.
  *
  * @param name    human-readable name of the implementation (e.g. "my-language-server")
  * @param version implementation version string; defaults to `"0.0.0"`
  * @param title   optional display title distinct from the wire-stable `name`
  */
final case class LspInfo(name: String, version: String = "0.0.0", title: Maybe[String] = Absent)
    derives Schema, CanEqual
