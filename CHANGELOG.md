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
- [kyo-core] `Path.tailBytes`: follow a file at the byte level, starting from the beginning, the end, or a recorded offset
- [kyo-core] `Path.Origin`: where a byte-level read begins (`Start`, `End`, `Offset`)
- [kyo-combinators] `.forkUsing`: apply `Fiber.use`
- [kyo-logging-jpl] `kyo.JavaLog`: bridge `Log` to Java platform logging a.k.a. `System.Logger`
- [kyo-logging-slf4j] `kyo.SLF4JLog`: bridge `Log` to SLF4J 2.0 API
- [kyo-system] `Stream.writeTo` and `Stream.writeLinesTo`: `append` and `createFolders` parameters, matching the `Path` write methods. `append = true` adds to the end of an existing file and leaves that file in place when the stream fails.

### Removed

- [kyo-combinators] `.forkScoped`: changed to `.fork`
- [kyo-core] `LogPlatformSpecific.Unsafe.SLF4J`: removed from JVM module, see above

### Changed

- [kyo-schema] `Schema.dictSchema`: non-String-key `Dict` now serializes each entry as a two-field `key`/`value` record (the same form `mapSchema` uses) instead of a bare two-element array. BREAKING: previously-serialized MsgPack bytes for a non-String-key `Dict` cannot be read by the new code. MsgPack was the only codec that decoded the old form; the other six failed to decode and Protobuf silently emitted corrupt bytes.
- [kyo-schema] `Schema.dictSchema` and `Schema.stringDictSchema`: a case class field holding an empty `Dict` now decodes on Protobuf instead of failing with `MissingFieldException`, matching the `Map` givens
- [kyo-core] `Fiber.init`: use `Scope` effect to guarantee termination of forked fiber
- [kyo-combinators] `.fork`: apply `Fiber.init` (formerly `.forkScoped`)
- [kyo-prelude] The `Parse` effect has been moved to a new `kyo-parse` module
- [kyo-core] `Log.live`: defaulting to `Unsafe.ConsoleLogger` for all platforms
- [kyo-core] `Path`, `System`, `Process`, `Command`, `CommandException`, `FileException`, and the `Stream` `writeTo` and `writeLinesTo` sinks have moved to a new `kyo-system` module. Add `"io.getkyo" %% "kyo-system"` to keep using them. No import or signature changes are needed, since the package is still `kyo`.
- [kyo-core] `Async.defaultConcurrency` now resolves through `StaticFlag`, which adds an environment-variable channel (`KYO_ASYNC_CONCURRENCY_DEFAULT`, checked after the `kyo.async.concurrency.default` system property) and changes the exception type thrown on a malformed value.
