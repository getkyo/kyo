package kyo

/** Which SQLite VFS a connection opens through, deciding where its database actually lives.
  *
  * An extension rather than a `SqlConfig` field, because it means nothing to any other engine. The WebAssembly
  * build has no filesystem, so the default VFS keeps a database in memory and loses it at reload; name `opfs` for
  * storage that survives.
  *
  * {{{
  * SqlClient.init("doltlite://app.db", SqlConfig(maxConnections = 1).extension(SqliteVfs("opfs")))
  * }}}
  *
  * `opfs` requires the page to be cross-origin isolated (COOP `same-origin` plus COEP `require-corp`) and is
  * reachable only from a worker, so a connection on a browser's main thread gets memory storage whatever it names.
  * The pooled `opfs-sahpool` VFS needs no isolation, but this engine replaces SQLite's storage layer and needs
  * file-control operations that VFS lacks: a database opens on it and then every statement fails
  * `SQLITE_NOTFOUND`. An unknown name fails the open rather than falling back.
  *
  * @param name
  *   the VFS name as registered with SQLite; empty means the platform default.
  */
final case class SqliteVfs(name: String) extends SqlConfig.Extension derives CanEqual
