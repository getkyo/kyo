# kyo-system-doltfs

A filesystem whose storage is a version-controlled database.

[kyo-system](../kyo-system/README.md) is where `Path` lives, along with the `FileSystem` capability that decides
what a path actually reads and writes. This artifact supplies one implementation of that capability. Ordinary
`Path` code runs on it unchanged; what changes is what the tree is. A file written here is a row, so the whole
tree branches, commits, diffs and merges with the same operations that work on any other table, and it can be
queried in SQL.

```scala
val onDraft: String < (Async & Abort[SqlException | FileSystemException]) =
    DB.run("doltlite://config.db") {
        Dolt.use { dolt =>
            DoltFileSystem.let(dolt) {
                dolt.onBranch("draft") {
                    Path("etc", "app.conf").write("mode = draft")
                        .andThen(dolt.commit("switch to draft"))
                        .andThen(Path("etc", "app.conf").read)
                }
            }
        }
    }
```

The branch a read or write lands on is whichever one is checked out, so `Dolt.onBranch` scopes filesystem
work to a branch exactly as it scopes queries. On `main`, that same path still reads whatever it held before.

That URL is a local DoltLite file, which needs no server. Point the same code at `dolt://user:pass@host:3306/db`
and it runs against a Dolt server instead; nothing above it changes, which is the point of building on the shared
`Dolt` rather than on one engine.

## Adding the module

Depend on this artifact and on one engine. It is built against
[kyo-sql-dolt-api](../kyo-sql-dolt-api), the vocabulary the two versioned backends share, rather than against
either of them, so the choice of engine is yours and the filesystem does not change with it:

```scala doctest:expect=skipped
libraryDependencies ++= Seq(
    "io.getkyo" %% "kyo-system-doltfs" % "<latest version>",
    // A server, reached over the MySQL wire:
    "io.getkyo" %% "kyo-sql-dolt" % "<latest version>"
    // ...or a local file, with no server at all:
    // "io.getkyo" %% "kyo-sql-doltlite" % "<latest version>"
)
```

`DoltFileSystem.init(client)` creates its tables if they are absent, so a fresh database works straight away and
opening one over a database that already holds a tree is a no-op.

## What the storage buys

A host filesystem has no vocabulary for most of this.

**A branch gives one path two contents.** Writing a config on a branch, reading back what a deploy changed, and
reverting it are ordinary operations rather than conventions layered on top of files.

**A diff names what changed.** `dolt.diff(Dolt.Ref.Branch("main"), Dolt.Ref.Branch("work"), "vfs_node")` answers one
entry per path that changed between two branches, and nothing for the paths that did not.

**The tree answers SQL.** The node table carries path, kind, size and modification time, so "every file over a
megabyte, by directory" is a query rather than a walk:

```scala
val large: Chunk[(String, String, Long)] < (Async & Abort[SqlException]) =
    DB.run("doltlite://config.db") {
        sql"SELECT parent, path, size_bytes FROM vfs_node WHERE kind = 'file' AND size_bytes > 1048576"
            .as[(String, String, Long)].run
    }
```

## How content is stored

One table. `vfs_node` is one row per path, carrying kind, size, modification time, a symlink's target, and the
file's whole content in a `LONGBLOB` column. That is the shape SQLite's own archive format uses, and it keeps the
tree queryable: a file's content is a column you can select rather than a set of rows to reassemble, and a commit's
diff names one row per changed file.

Content is not split into fixed-size rows, because the engine already does that. Measured on a 1 MiB value,
flipping one byte and committing cost 12 KB: Dolt content-addresses and shares structure inside a large blob
across versions, so a whole-value rewrite is not a whole-value copy.

Positioned reads and writes slice the value's **hex text** rather than its bytes, which looks indirect and is not.
Measured on Dolt 2.3.4, `SUBSTRING`, `SUBSTR`, `MID`, `INSERT` and a `BINARY` cast all fail outright on a blob
holding bytes that are not valid UTF-8, and `LEFT` and `RIGHT` succeed while silently replacing every such byte
with U+FFFD. `HEX` is correct, and its output is ASCII, which those same functions handle. So a slice is taken
over the hex and decoded back with `UNHEX`, and a spliced write is built the same way. The cost is that a slice
moves two characters per byte; the alternative corrupts binary files.

The schema is plain SQL with no engine-specific types, because it has to create identically on a server and on an
embedded file. `LONGBLOB` rather than `BLOB`: MySQL's `BLOB` caps at 65535 bytes.

## Two deliberate differences from a host filesystem

Both are observable, so they are stated here rather than left to be discovered.

**Advisory locks are held in memory, per filesystem instance, not in the database.** This is the right place for
them: a lock is not tree content, and a lock table would be versioned, committed and merged along with the files,
which is not what a lock means. The consequence is that two separate clients against one database do not see each
other's locks.

**A write handle abandoned without finishing has its partial entry removed on the next filesystem operation**,
rather than the instant it is closed. `Path.WriteHandle.close` is synchronous and takes no effect, so a backend
whose storage is a database has nowhere to run the removal. The window is bounded by the next thing you do.

The same constraint shapes reads: `Path.ReadHandle.readChunk` and `WalkHandle.next` return values rather than
computations, so a read handle materializes its content when it is opened. Opening one here costs the whole file
where the host's costs a descriptor. The positioned channels have no such limit, because their signatures carry
the backend's effect, and they are what a large file wants.

## Conformance

This backend answers kyo-system's own reusable contracts: `FileSystemReadTest`, `FileSystemWriteTest`,
`FileSystemChannelTest` and `FileSystemLockTest`, the same four the host filesystem answers. Passing them is the
claim that a program written against `Path` behaves the same whether its bytes are on a disk or in a table.
Symbolic links are stored, so the link assertions run rather than being skipped.

`FileSystem.Watch` is not advertised. It is a separate, optional capability, and this backend does not implement
it.
