# Changelog

All breaking API changes to this project will be documented in this file.

## [Unreleased]

### Added

- [kyo-data] `Glob`: a compiled, platform-independent pattern for matching slash-separated paths, with a `glob"..."` literal interpolator
- [kyo-data] `OrderedDict[K, V]`: an immutable map that iterates in insertion order
- [kyo-data] `OrderedDictBuilder[K, V]`: build an `OrderedDict` from repeated adds
- [kyo-schema] `Schema.stringOrderedDictSchema` and `Schema.orderedDictSchema`: derive a `Schema` for a case class with an `OrderedDict` field
- [kyo-core] `Fiber.use`: use a forked fiber within a function and clean it up
- [kyo-core] `Fiber.initUnscoped`: fork a fiber without guaranteeing cleanup (formerly `Fiber.init`)
- [kyo-system] `Path.tailBytes`: follow a file at the byte level, starting from the beginning, the end, or a recorded offset
- [kyo-system] `Path.Origin`: where a byte-level read begins (`Start`, `End`, `Offset`)
- [kyo-core] `Stream.fromInputStream`: stream a `java.io.InputStream`'s bytes, closed with the enclosing `Scope`
- [kyo-schema] `RecordDecodeException`: decode failure for one record in a multi-record input, carrying its index and byte offset
- [kyo-schema-json] `Json.Lines`: pure JSONL/NDJSON framing (`Framer`, `Pending`, `Line`, `Framed`) plus `decodeAll`, `decodeAllBytes`, `decodeAllBytesResults`, `encodeAll`, `encodeAllBytes`, `encodeLine`
- [kyo-schema-json] `Jsonl`: streaming JSONL/NDJSON over files and byte streams with `read`, `watch`, `pipe`, `encode`, `write`, `append`, and per-record error recovery through the `Results` variants
- [kyo-combinators] `.forkUsing`: apply `Fiber.use`
- [kyo-logging-jpl] `kyo.JavaLog`: bridge `Log` to Java platform logging a.k.a. `System.Logger`
- [kyo-logging-slf4j] `kyo.SLF4JLog`: bridge `Log` to SLF4J 2.0 API
- [kyo-system] `Stream.writeTo` and `Stream.writeLinesTo`: `append` and `createFolders` parameters, matching the `Path` write methods. `append = true` adds to the end of an existing file and leaves that file in place when the stream fails.
- [kyo-system] `FileWriteStalledException(path, remaining)`: a `FileWriteException` reporting a write that consumed none of the bytes it was offered, with the unwritten count as a `ByteSize`
- [kyo-sql] `Sql.from`, `Sql.insert`, `Sql.update` and `Sql.delete`: a `schemaName` parameter beside `tableName`, rendering the two as separately quoted identifiers (`"app"."invoice"`). Both are literals, so a qualified statement still folds at compile time.
- [kyo-sql-postgres] `PostgresConfig.searchPath`: the schemas an unqualified name resolves against, sent in the startup packet so every pooled connection agrees and `SqlClient.reset` restores it.
- [kyo-sql-sqlite] `SqliteAttach`: databases attached to every connection the client opens, so a schema-qualified name resolves on all of them.

### Removed

- [kyo-combinators] `.forkScoped`: changed to `.fork`
- [kyo-core] `LogPlatformSpecific.Unsafe.SLF4J`: removed from JVM module, see above

### Changed

- [kyo-sql] `SqlClient.reset` now drops the connection's prepared-statement cache, once the scrub has returned. A reset releases every server-side prepared statement, and the connection returns to the pool, so a cached entry naming one of them failed for the next borrower rather than for whoever called `reset`. A reset the server refused released nothing and leaves the cache alone, since forgetting the names there would strand the statements for the life of the connection. Affects the PostgreSQL, MySQL and Dolt backends; SQLite never had the failure.
- [kyo-sql] A PostgreSQL session that reports deallocating every prepared statement now drops the cache before the next statement binds. The command tag is what is read, not the SQL the caller was handed, so a `DEALLOCATE ALL` or `DISCARD ALL` sent through `executeRaw` is seen wherever it came from. Without this the next statement recovered through the retry below instead, at one dead Bind and one re-parse for every entry the cache still named, and inside a transaction block it could not recover at all.
- [kyo-sql] A PostgreSQL statement the server no longer holds is now parsed again and bound a second time, for the causes the driver cannot see: an external pooler's reset query, or DDL invalidating a cached plan. Only for `26000` from `FetchPreparedStatement` and `0A000` from `RevalidateCachedQuery`, only once, and only while the session is idle, since PostgreSQL aborts the whole block on a statement error. Inside a transaction block the original error is surfaced and the entry is dropped so the next statement heals. The pipeline path drops the entry and reports rather than retrying, because its batch has already run the later statements by the time the failure is read.
- [kyo-sql] A new `prepared_statements_reprepared` counter reports how often that recovery fired, alongside the existing pool counters.
- [kyo-sql] `SqlCodec.Writer` now states that encoding a value must be a pure, repeatable function of that value. Every shipped encoder already satisfies this; a user-supplied encoder over mutable state did not have to, and the retry above binds the same parameters twice.
- [kyo-sql] `Sql.Table`, `Sql.Insert`, `Sql.Update` and `Sql.Delete`: each carries a `schemaName: Maybe[String]` before its `tableName`. BREAKING for code that constructs or pattern-matches these AST nodes directly, which is what a custom `kyo.db.Idiom` does; the field has no default, because a default reaches the compile-time render as a synthetic accessor it cannot read and would make every statement unfoldable. Statements built through `Sql.from`, `Sql.insert`, `Sql.update` and `Sql.delete` are unaffected.
- [kyo-sql] Connection pool identity now includes the config's `SqlConfig.Extension` values, so two configs differing only in a backend setting do not share pooled connections. Idle retention per address can exceed `maxConnections` where it could not before; concurrency still cannot.
- [kyo-schema] `Schema.dictSchema`: non-String-key `Dict` now serializes each entry as a two-field `key`/`value` record (the same form `mapSchema` uses) instead of a bare two-element array. BREAKING: previously-serialized MsgPack bytes for a non-String-key `Dict` cannot be read by the new code. MsgPack was the only codec that decoded the old form; the other six failed to decode and Protobuf silently emitted corrupt bytes.
- [kyo-schema] `Schema.dictSchema` and `Schema.stringDictSchema`: a case class field holding an empty `Dict` now decodes on Protobuf instead of failing with `MissingFieldException`, matching the `Map` givens
- [kyo-core] `Fiber.init`: use `Scope` effect to guarantee termination of forked fiber
- [kyo-combinators] `.fork`: apply `Fiber.init` (formerly `.forkScoped`)
- [kyo-prelude] The `Parse` effect has been moved to a new `kyo-parse` module
- [kyo-core] `Log.live`: defaulting to `Unsafe.ConsoleLogger` for all platforms
- [kyo-core] `Path`, `System`, `Process`, `Command`, `CommandException`, `FileException`, and the `Stream` `writeTo` and `writeLinesTo` sinks have moved to a new `kyo-system` module. Add `"io.getkyo" %% "kyo-system"` to keep using them. No import or signature changes are needed, since the package is still `kyo`.
- [kyo-core] `Async.defaultConcurrency` now resolves through `StaticFlag`, which adds an environment-variable channel (`KYO_ASYNC_CONCURRENCY_DEFAULT`, checked after the `kyo.async.concurrency.default` system property) and changes the exception type thrown on a malformed value.
- [kyo-system] `Path.readStream(charset, bufferSize)`, `Path.readBytesStream(bufferSize)`, `Path.tail(pollDelay, bufferSize)`, and `Path.tailBytes(bufferSize)`: `bufferSize` is now a `ByteSize` rather than an `Int`. Pass `8.kib` where `8192` was passed. The value is clamped to the range an array can address, so `ByteSize.Zero` reads one byte at a time and anything above `Int.MaxValue` bytes reads through the largest buffer there is.
- [kyo-core] `Stream.fromInputStream(is, bufferSize)`: `bufferSize` is now a `ByteSize`, clamped the same way.
- [kyo-schema-json] `Json.Lines.DefaultMaxLineBytes` is now `Json.Lines.DefaultMaxLineSize`, a `ByteSize` of 16 MiB. The `maxLineBytes` parameter of `Json.Lines.Framer.init`, `Json.Lines.decodeAll`, `Json.Lines.decodeAllBytes`, `Json.Lines.decodeAllBytesResults`, and every `Jsonl` entry point is now `maxLineSize: ByteSize`. Pass `30.bytes` where `30` was passed. A ceiling above `Json.Lines.MaxLineSize` (`Int.MaxValue` bytes) is clamped to it, since a record is handed out as an array-backed `Span`.
- [kyo-prelude] `Stream.handle`: the handler's input is now typed as the stream's own pending computation instead of an inferred supertype, and its result is decomposed after inference. Handlers that only remove part of an effect keep the rest: `stream.handle(Abort.recover[Int](...))` on a `Stream[Int, Abort[Int] & Abort[Float]]` now infers `Stream[Int, Abort[Float]]` where it used to infer `Stream[Int, Abort[Any]]`. The first type parameter is the handler's result type rather than its input type, so an explicit type application such as `handle[A, V1, S1](...)` needs updating.
- [kyo-system] `Path.Unsafe.openWrite(append = true)`: now appends on Scala.js and Wasm. The Node handle wrote at an explicit position, which makes the call a positioned write, and POSIX leaves `O_APPEND` without effect there, so on macOS every write overwrote the file from its first byte. Writes now go at the file description's own cursor.
- [kyo-data] `ConcreteTag.accepts`: a primitive tag now asks the platform about its own type instead of classifying the value first. On Scala.js and Wasm every number is one runtime type, so the widest check answered for all of them and `ConcreteTag[Float]`, `ConcreteTag[Byte]` and `ConcreteTag[Short]` accepted nothing at all, which left `Abort[Float]`, `Abort[Byte]` and `Abort[Short]` unable to catch there. JVM and Native answers are unchanged. A JS number carries no width, so a widened numeric tag over-accepts on those platforms: `ConcreteTag[Double]` accepts `42`, and `ConcreteTag[Float]` accepts float-representable doubles.
- [kyo-system] A file write that reports no progress now fails with the new `FileWriteStalledException(path, remaining)` instead of being retried. Both write handles and both positioned raw channels retried a write that took no bytes, which re-offers the same bytes at the same offset forever and hung the calling thread with no diagnostic.
- [kyo-schema] `Schema.omitEmptyCollections` and `omit(_.f).whenEmpty`: an omitted empty `Map`, `Dict`, or `OrderedDict` field now decodes back to the empty value under either of that type's two givens, the object form or the array of `key`/`value` records. The injected value was chosen from the field's declared key type, which is not where a mapping's wire form comes from: a `Map[Int, V]` field failed to decode under its own default given, and a `String`-keyed field bound to `Schema.mapSchema`, `Schema.dictSchema`, or `Schema.orderedDictSchema` failed with `TypeMismatchException`.
- [kyo-schema] A schema transform (a rename, a drop, an omit policy, a discriminator, a representation) no longer rewrites a mapping field's wire form. A field whose given writes the array of `key`/`value` records keeps that form; it came out as an object whenever every key was a string, which the schema that wrote it could not read back.
- [kyo-sql] `SqlSchema` derived for a named tuple now carries the tuple's labels as the row's column names, so the row decodes by name as the equivalent case class does. `SqlMacros.columnNames` and the auto-key rule read them too. `Sql.from` on a named tuple now reports that the query DSL stages a row's columns from a case class's fields, rather than reporting a row with no fields.
