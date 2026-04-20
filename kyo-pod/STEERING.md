# Steering Instructions

## After every compile, read this file.
## Use small Edit calls — never replace more than 80 lines at once.
## No AllowUnsafe, no Frame.internal, no try/catch (use Result.catching).
## No Fiber.block — use fiber.safe.get or onComplete.
## Always log Result.Panic — never use catch-all `case _ =>`.
## No null — use Option in DTOs (derives Json), Maybe everywhere else.

## Resource cleanup: prefer Sync.ensure over Scope.ensure

Use `Sync.ensure` for cleanup that doesn't need Async (closing pipes, killing processes, releasing local resources).
Use `Scope.ensure` only when cleanup itself needs Async (HTTP calls for container stop/remove).
