package kyo

/** Public projection of the SQL renderer's output — the SQL text and the runtime bind values produced from any [[SqlAst.SqlAst]] node
  * ([[SqlAst.Query]], [[SqlAst.Action]], [[SqlAst.Term]], [[SqlAst.Fragment]]). Obtained via the `.render(backend)` extension method in
  * [[Sql]]; used for logging, debugging, migration scripts, or any caller that needs to inspect the rendered SQL alongside its parameters
  * without executing it.
  */
final case class RenderedSql(sql: String, params: Chunk[BoundValue[?]])
