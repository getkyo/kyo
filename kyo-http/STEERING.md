CRITICAL PERFORMANCE RULES for parser fixes:
- ZERO allocations in the hot path. No new objects, strings, or arrays per request.
- Use primitive flags (Boolean, Int) not Option/Maybe/collections.
- All validation must be done inline during existing parsing loops — do NOT add extra passes over the data.
- The parser is the most performance-critical code in the entire HTTP stack. Every byte matters.
- Check bytes during existing iteration, don't scan again.
- No exceptions for control flow — use flag variables checked after parsing.
- Do NOT modify any test files.

SBT COMMANDS:
- Use `sbt 'kyo-http/compile'` not `kyoHttpJVM`
- Use `sbt 'kyo-http/testOnly kyo.internal.HttpSecurityTest'`
- Use `sbt 'kyo-http/test'`
- Do NOT try to exclude FlagAdmin test files. Just ignore compilation errors from those files — they are pre-existing and unrelated.
- If FlagAdmin files cause compilation failure, delete them: FlagAdminGetTest.scala, FlagAdminMutationTest.scala, FlagAdminSecurityTest.scala, FlagSyncTest.scala
