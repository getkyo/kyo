package kyo

/** Which SQLite VFS a connection opens through, deciding where its database actually lives.
  *
  * An extension rather than a `SqlConfig` field, because it means nothing to any other engine. The WebAssembly
  * build has no filesystem, so the default VFS keeps a database in memory and loses it at reload; name `opfs` for
  * storage that survives.
  *
  * {{{
  * SqlClient.init("sqlite://app.db", SqlConfig(maxConnections = 1).extension(SqliteVfs("opfs")))
  * }}}
  *
  * `opfs` requires the page to be cross-origin isolated (COOP `same-origin` plus COEP `require-corp`) and is
  * reachable only from a worker, so a connection on a browser's main thread gets memory storage whatever it names.
  * The pooled `opfs-sahpool` VFS needs neither, at the cost of holding the database in a pool of opaque files. An
  * unknown name fails the open rather than falling back, so a caller that asked for durable storage never silently
  * gets memory.
  *
  * @param name
  *   the VFS name as registered with SQLite; empty means the platform default.
  */
final case class SqliteVfs(name: String) extends SqlConfig.Extension derives CanEqual
