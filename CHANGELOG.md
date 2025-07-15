# Changelog

All breaking API changes to this project will be documented in this file.

## [Unreleased]

### Added

- [kyo-core] `Fiber.use`: use a forked fiber within a function and clean it up
- [kyo-core] `Fiber.initUnscoped`: fork a fiber without guaranteeing cleanup (formerly `Fiber.init`)
- [kyo-combinators] `.forkUsing`: apply `Fiber.use`

### Removed

- [kyo-combinators] `.forkScoped`: changed to `.fork`

### Changed

- [kyo-core] `Fiber.init`: use `Scope` effect to guarantee termination of forked fiber
- [kyo-combinators] `.fork`: apply `Fiber.init` (formerly `.forkScoped`)
