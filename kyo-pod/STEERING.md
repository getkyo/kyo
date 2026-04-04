# Steering Instructions

## DO NOT modify test files unless explicitly told.
## After every compile, read this file.
## Use small Edit calls — never replace more than 80 lines at once.
## No AllowUnsafe, no Frame.internal, no try/catch (use Result.catching).
## No Fiber.block — use fiber.safe.get or onComplete.
## Always log Result.Panic — never use catch-all `case _ =>`.
