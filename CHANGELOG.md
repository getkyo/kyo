# Changelog

All breaking API changes to this project will be documented in this file.

## [Unreleased]

### Added

- [kyo-core] `Fiber.use`: use a forked fiber within a function and clean it up
- [kyo-core] `Fiber.initUnscoped`: fork a fiber without guaranteeing cleanup (formerly `Fiber.init`)
- [kyo-combinators] `.forkUsing`: apply `Fiber.use`
- [kyo-logging-jpl] `kyo.JavaLog`: bridge `Log` to Java platform logging a.k.a. `System.Logger`
- [kyo-logging-slf4j] `kyo.SLF4JLog`: bridge `Log` to SLF4J 2.0 API

### Removed

- [kyo-combinators] `.forkScoped`: changed to `.fork`
- [kyo-core] `LogPlatformSpecific.Unsafe.SLF4J`: removed from JVM module, see above

### Changed

- [kyo-schema] `Schema.dictSchema`: non-String-key `Dict` now serializes each entry as a two-field `key`/`value` record (the same form `mapSchema` uses) instead of a bare two-element array. BREAKING: previously-serialized MsgPack bytes for a non-String-key `Dict` cannot be read by the new code. MsgPack was the only codec that decoded the old form; the other six failed to decode and Protobuf silently emitted corrupt bytes.
- [kyo-core] `Fiber.init`: use `Scope` effect to guarantee termination of forked fiber
- [kyo-combinators] `.fork`: apply `Fiber.init` (formerly `.forkScoped`)
- [kyo-prelude] The `Parse` effect has been moved to a new `kyo-parse` module
- [kyo-core] `Log.live`: defaulting to `Unsafe.ConsoleLogger` for all platforms
