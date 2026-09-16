# kyo-sql-doltlite

The embedded Dolt driver for [kyo-sql](../kyo-sql/README.md).

[kyo-sql](../kyo-sql/README.md) is the SQL module, and it names no engine: the `sql"…"` interpolator, the typed
DSL, `SqlSchema` and row decoding, transactions, streaming, pooling, configuration, and error handling all live
there and are documented there. This artifact is what makes `doltlite://` one of the URLs that module can open.

DoltLite is a SQLite fork that keeps the `sqlite3_*` C API and replaces the storage engine with a
content-addressed prolly tree. One file therefore carries a whole history: branches, commits, merges and diffs
over the data, reachable through the same `DoltClient` the server backend implements.

```scala
val recent: Chunk[String] < (Async & Abort[SqlException]) =
    DB.run("doltlite://inventory.db") {
        sql"SELECT sku FROM item ORDER BY id".as[String].run
    }
```

Nothing in that body names DoltLite. Point it at a `dolt://` URL and it runs against a Dolt server.

## Adding the driver

Depend on kyo-sql and this artifact. There is nothing to wire:

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-sql"          % "<latest version>",
    "io.getkyo" %% "kyo-sql-doltlite" % "<latest version>"
)
```

The artifact carries one `META-INF/services/kyo.db.Backend` entry naming `DoltLiteBackendFactory`, which claims
the scheme `doltlite` and nothing else. It deliberately does not claim `sqlite`, even though this engine forks it:
claiming it would make which backend a `sqlite://` URL reaches depend on classpath order.

### Platforms

The engine is a compiled library rather than C source, so it ships per platform:

| | x86_64 | aarch64 |
|---|---|---|
| macOS | ✅ | ✅ |
| Linux (glibc) | ✅ | ✅ |
| Linux (musl) | ✅ | ✅ |
| Windows | ✅ | ❌ |

Windows on ARM is the one gap, and it is upstream's rather than a packaging choice here: DoltLite publishes no
`win-arm64` build, and its autoconf build runs through MSYS2/MinGW, which that platform has no native toolchain
for. Opening a `doltlite://` URL there fails with `DoltLiteEngineUnavailableException`, naming the platform and
where the loader looked, rather than failing deep inside a native call.

[kyo-sql-sqlite](../kyo-sql-sqlite/README.md) is the embedded engine that does run everywhere. It compiles from C
source, so it has no per-platform artifact and no such gap. What it does not have is version control.

## Opening a database

The URL is a file path, or `:memory:` for a database that lives only as long as the connection:

```scala doctest:expect=skipped
SqlClient.init("doltlite://inventory.db", SqlConfig(maxConnections = 1)).map { client =>
    DB.run(client)(DoltClient.use(dolt => dolt.commit("import")))
}
```

`:memory:` gives every CONNECTION its own private database, so a pool of more than one connection opening that
name is several databases rather than one. Pin `maxConnections = 1` when using it.

## The version-control surface

`DoltClient` is shared with [kyo-sql-dolt](../kyo-sql-dolt/README.md), and that README documents the whole
surface: branches, the difference between a SQL commit and a Dolt commit, merging where a conflict is a value,
reading history, and remotes. Everything there applies here, because a program written against `DoltClient`
runs on either backend unchanged.

What differs is only what an embedded engine cannot have. There is no server to authenticate to, so a URL
carries no user, password, host or port. There are no advisory locks and no configurable isolation level, both
of which are refused by name rather than silently downgraded, exactly as
[kyo-sql-sqlite](../kyo-sql-sqlite/README.md) describes for the engine this one forks.

## In a browser

The WebAssembly build runs in a browser, where there is no shared library to load. Initialize the module and
register it before opening a database:

```scala doctest:expect=skipped
DoltLiteWasm.init(sqlite3).map(_ => SqlClient.init("doltlite://app.db"))
```

The default VFS keeps a database in memory and loses it at reload. `SqliteVfs("opfs")` is what makes one
survive, and it carries two requirements: the page must be cross-origin isolated (COOP `same-origin` plus COEP
`require-corp`), and the database must be opened from a worker. The pooled `opfs-sahpool` VFS looks like an
alternative and does not work here: this engine replaces SQLite's storage layer and needs file-control
operations that VFS does not implement, so a database opens on it and then every statement fails
`SQLITE_NOTFOUND`.

`kyo-sql-doltlite/scripts/browser-check.sh` drives the published build in a real browser and checks that a
database survives a reload.
