package kyo.fixtures

/** Context-function fixtures for cross-platform testing of Type.ContextFunction decoding.
  *
  * Provides type aliases and methods that use Scala 3 context functions (the `?=>` arrow). These types appear in the TASTy encoding as
  * CONTEXTFUNCTIONtype.
  *
  * Design:
  *   - `Logger` is a simple capability type; methods that need it require `Logger ?=>`.
  *   - `withLogger` is a higher-order method whose parameter has a context-function type.
  *   - `example` is a method with a context-function return type.
  *   - `nested` demonstrates a nested context-function type for multi-level coverage.
  */

/** Minimal capability used as context for context-function types in this fixture. */
class Logger:
    def log(msg: String): Unit = ()
end Logger

/** A second capability, for intersection / multi-capability examples. */
class Config:
    def get(key: String): String = key
end Config

/** Top-level methods with context function signatures. */
def withLogger[A](body: Logger ?=> A): A = body(using new Logger)

def withConfig[A](body: Config ?=> A): A = body(using new Config)

def withBoth[A](body: (Logger, Config) ?=> A): A = body(using new Logger, new Config)

/** A class with methods that use context functions in their parameter and return types. */
class ContextFunctionFixture:

    def run[A](body: Logger ?=> A): A = body(using new Logger)

    def getConfig: Config ?=> String = "fixture"

    def nested[A](outer: Logger ?=> (Config ?=> A)): A =
        outer(using new Logger)(using new Config)

end ContextFunctionFixture
