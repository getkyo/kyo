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

- [kyo-core] `Fiber.init`: use `Scope` effect to guarantee termination of forked fiber
- [kyo-combinators] `.fork`: apply `Fiber.init` (formerly `.forkScoped`)
- [kyo-core] `Log.live`: defaulting to `Unsafe.ConsoleLogger` for all platforms
