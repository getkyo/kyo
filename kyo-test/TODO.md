# Converting zio-test to kyo

The kyo library is a [new functional effect system for Scala](../README.md).
As testing component it currently has a thin wrapper around zio-test.
We would like to provide a native testing library that starts out as a port of zio-test itself.
kyo differs from ZIO in a few ways, but there are a few correspondences as well:
- A `ZIO[R, E, A]` corresponds to a Kyo `A < Env[R] & Abort[E]`
- ZIO provides most of its functionality as methods on the `ZIO` object or on an instance of a `ZIO[R, E, A]`.
  Kyo has the [kyo-combinators](../kyo-combinators/) module which tries to achieve a similar API to ZIO by providing
  extension methods on pending types `A < S` in [Combinators](../kyo-combinators/shared/src/main/scala/kyo/Combinators.scala)
  and [Constructors](../kyo-combinators/shared/src/main/scala/kyo/Combinators.scala).

## Notes
- The Kyo effects used to have plural names like `IOs`, `Envs`, `Aborts`... Those have been changed to singular `IO`, `Env`, `Abort`, ...



// ----- Begin Conversion Guide from ZIO to Kyo -----

## Converting ZIO Test to Kyo Test

This section provides actionable guidelines to convert the Scala test library from the ZIO effect system to the Kyo effect system. The conversion should be handled on a per-file basis. Below are key points to address:

### 1. Kyo Effect Fundamentals
- In Kyo, computations are expressed via the infix type `<`, which represents a value with pending effects. For example:
  - A pure value: `val a: Int < Any = 1` (every value is automatically promoted to a computation with no pending effects).
  - Effectful computations: `String < (Abort[Exception] & IO)` corresponds to a computation that may abort with an Exception and perform IO.
- Replace ZIO types (e.g., `ZIO[R, E, A]`) with Kyo types (e.g., `A < Env[R] & Aborts[E]`).

### 2. Effect Handling and Transformation
- **Chaining Operations:** Use Kyo's `map` and `flatMap` (or simply `map`, given Kyo's effect widening) in place of ZIO's monadic operations.
  - Example:
    ```scala
    def combine(a: Int < IO, b: Int < Abort[Exception]): Int < (IO & Abort[Exception]) =
      a.flatMap(v => b.map(_ + v))
    ```
- **Pipelining Handlers:** Utilize the `pipe` method to handle multiple effects sequentially, similar to composing effect handlers in ZIO.
  - Example:
    ```scala
    val result = computation.pipe(Abort.run(_), Env.run(10)).eval
    ```
- **Error Handling:** Replace `ZIO.fail` with `Abort.fail` and wrap effectful operations with `Kyo.attempt` (which internally uses `Abort.catching`) to manage errors.

### 3. Resource Management and Asynchronous Effects
- **Resource Handling:** Migrate resource lifecycle management using Kyo's constructors:
  - `Kyo.acquireRelease` handles resource acquisition and guaranteed release.
  - Use `Kyo.fromAutoCloseable` to work with AutoCloseable resources.
- **Asynchronous Operations:** Convert asynchronous operations using the `Kyo.async` constructor. Also, use helpers such as `Kyo.fromFuture` and `fromEither` to bridge standard Scala classes to Kyo effects.

### 4. Control Combinators and Repetition
- Replace ZIO combinators with Kyo's extension methods found in Combinators.scala:
  - Sequencing: `*>`, `<*`, and `<*>` for managing the order of execution and discarding/extracting results.
  - Repetition: Methods like `repeat`, `repeatWhile`, `repeatUntil`, and `retry` allow for retry logic and looping with delayable effects.
- Ensure that the combined effects in these operations correctly propagate pending effect sets (e.g., merging IO and Abort effects).

### 5. Converting Test Environment and Layers
- Update ZIO environment layers (e.g., ZLayer) to Kyo's dependency and environment mechanisms (using `Env` and appropriate constructors).
- Adjust tests to replace ZIO-specific test assertions and runtime constructs with Kyo equivalents.

### 6. File-by-File Conversion Checklist
For each file (e.g., Assertion.scala, CheckConstructor.scala, etc.) perform the following:

- Change method signatures from types like `ZIO[R, E, A]` to `A < Env[R] & Aborts[E]`.
- `ZLayer[R, E, A]` => `Layer[A, Env[R] & Abort[E]]`
- Replace ZIO combinators (`flatMap`, `map`, `either`, etc.) with their Kyo counterparts as detailed above.
- Refactor asynchronous, resource, and error handling code using constructors provided in Constructors.scala (e.g., `async`, `attempt`, `fromOption`, etc.).
- `ZLayer[R, E, A]` => `Layer[A, Env[R] & Abort[E]]`
- Revise test assertions and environment setup to align with Kyo's execution model.

### 7. Additional Considerations
- Utilize Kyo's effect widening: computations with fewer effects can be automatically promoted to those requiring a larger set of pending effects.
- Leverage Kyo's debugging helpers (e.g., `debugln`, `debugValue`, and `debugTrace`) to trace computations during conversion.

_Reminder: Use the Kyo documentation and sample modules (such as kyo-combinators) as references to ensure that the conversion preserves functionality and idiomatic usage._

```scala
// ----- Kyo Combinators External Interface -----

/*
This section summarizes the external interface of the Kyo combinators (from Combinators.scala) with abbreviated comments.
*/

// Extension methods on computations of type A < S
extension [A, S](effect: A < S):
  def *>[A1, S1](next: => A1 < S1)(using Frame): A1 < (S & S1)      // Discards current result and returns the next computation.
  def <*[A1, S1](next: => A1 < S1)(using Frame): A < (S & S1)             // Discards next result and returns the current computation.
  def <*>[A1, S1](next: => A1 < S1)(using Frame): (A, A1) < (S & S1)        // Returns a tuple of both computation results.
  def debugValue(using Frame): A < S                                      // Prints the computation's result.
  def debugTrace(using Frame): A < S                                      // Prints a detailed execution trace.
  def delayed(duration: Duration)(using Frame): A < (S & Async)             // Delays execution by the given duration.
  def repeat(schedule: Schedule)(using Flat[A], Frame): A < (S & Async)       // Repeats with a backoff policy.
  def repeat(limit: Int)(using Flat[A], Frame): A < S                       // Repeats a limited number of times.
  def repeat(backoff: Int => Duration, limit: Int)(using Flat[A], Frame): A < (S & Async) // Repeats with backoff delay and a limit.
  def repeatWhile[S1](fn: A => Boolean < S1)(using Flat[A], Frame): A < (S & S1 & Async) // Repeats while the condition holds.
  def repeatWhile[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using Flat[A], Frame): A < (S & S1 & Async) // Repeats with condition and iteration info.
  def repeatUntil[S1](fn: A => Boolean < S1)(using Flat[A], Frame): A < (S & S1 & Async) // Repeats until the condition holds.
  def repeatUntil[S1](fn: (A, Int) => (Boolean, Duration) < S1)(using Flat[A], Frame): A < (S & S1 & Async) // Repeats until condition holds with iteration info.
  def retry(schedule: Schedule)(using Flat[A], Frame): A < (S & Async & Abort[Throwable]) // Retries with a given policy on failure.
  def retry(n: Int)(using Flat[A], Frame): A < (S & Async & Abort[Throwable])          // Retries a specific number of times.
  def forever(using Frame): Nothing < S                                   // Repeats indefinitely.
  def when[S1](condition: => Boolean < S1)(using Frame): Maybe[A] < (S & S1) // Executes only if the condition holds.
  def unpanic(using Flat[A], Frame): A < (S & Abort[Throwable])             // Converts abort errors into a handled effect.
  def tap[S1](f: A => Any < S1)(using Frame): A < (S & S1)                  // Applies a side-effect function to the result.
  def unless[S1](condition: Boolean < S1)(using Frame): A < (S & S1 & Abort[Absent]) // Executes unless condition holds, failing with Absent.
  def ensuring(finalizer: => Unit < Async)(using Frame): A < (S & Resource & IO) // Ensures a finalizer runs after the computation.

// Extension methods on A < (Abort[E] & S)
extension [A, S, E](effect: A < (Abort[E] & S)):
  def result(using ct: SafeClassTag[E], fl: Flat[A], fr: Frame): Result[E, A] < S  // Handles Abort effect, returning a Result.
  def mapAbort[E1, S1](fn: E => E1 < S1)(using ct: SafeClassTag[E], ct1: SafeClassTag[E1], fl: Flat[A], fr: Frame): A < (Abort[E1] & S & S1) // Maps abort errors to a new type.
  def forAbort[E1 <: E]: ForAbortOps[A, S, E, E1]                        // Provides additional Abort operations.
  def abortToEmpty(using ct: SafeClassTag[E], fl: Flat[A], fr: Frame): A < (S & Choice) // Converts Abort errors to an empty Choice.
  def abortToAbsent(using ct: SafeClassTag[E], fl: Flat[A], fr: Frame): A < (S & Abort[Absent]) // Converts Abort errors to Abort[Absent].
  def abortToThrowable(using ng: NotGiven[E <:< Throwable], ct: SafeClassTag[E], fl: Flat[A], fr: Frame): A < (S & Abort[Throwable]) // Converts abort errors to Throwable.
  def catching[A1 >: A, S1](fn: E => A1 < S1)(using ct: SafeClassTag[E], fl: Flat[A], fr: Frame): A1 < (S & S1) // Catches abort errors and recovers.
  def catchingSome[A1 >: A, S1](fn: PartialFunction[E, A1 < S1])(using ct: SafeClassTag[E], fl: Flat[A], frame: Frame): A1 < (S & S1 & Abort[E]) // Partially catches abort errors.
  def swapAbort(using cta: SafeClassTag[A], cte: SafeClassTag[E], fl: Flat[A], fr: Frame): E < (S & Abort[A]) // Swaps error and success types.
  def orPanic(using ct: SafeClassTag[E], fl: Flat[A], fr: Frame): A < S            // Panics instead of catching Abort errors.

// Extension methods on A < (Abort[Absent] & S)
extension [A, S](effect: A < (Abort[Absent] & S)):
  def maybe(using Flat[A], Frame): Maybe[A] < S                         // Handles Abort[Absent] and returns Maybe.
  def absentToChoice(using Flat[A], Frame): A < (S & Choice)              // Converts Abort[Absent] to Choice.
  def absentToThrowable(using Flat[A], Frame): A < (S & Abort[NoSuchElementException]) // Converts to Abort with NoSuchElementException.
  def absentToFailure[E](failure: => E)(using Flat[A], Frame): A < (S & Abort[E]) // Converts Abort[Absent] to Abort[E] with provided failure.

// Extension methods on A < (S & Env[E])
extension [A, S, E](effect: A < (S & Env[E])):
  def provideValue[E1 >: E, ER](dependency: E1)(using ev: E => E1 & ER, fl: Flat[A], reduce: Reducible[Env[ER]], tag: Tag[E1], frame: Frame): A < (S & reduce.SReduced) // Provides a dependency value for Env.
  inline def provideLayer[S1, E1 >: E, ER](layer: Layer[E1, S1])(using ev: E => E1 & ER, fl: Flat[A], reduce: Reducible[Env[ER]], tag: Tag[E1], frame: Frame): A < (S & S1 & Memo & reduce.SReduced) // Provides a layer for Env effect.
  transparent inline def provide(inline layers: Layer[?, ?]*): A < Nothing           // Provides multiple layers for Env effect.

// Extension methods on A < (S & Choice)
extension [A, S](effect: A < (S & Choice)):
  def filterChoice[S1](fn: A => Boolean < S1)(using Frame): A < (S & S1 & Choice) // Filters the result with Choice effect.
  def handleChoice(using Flat[A], Frame): Seq[A] < S                         // Handles Choice effect to return a sequence.
  def emptyToAbsent(using Flat[A], Frame): A < (Choice & Abort[Absent] & S)       // Converts empty Choice to Abort[Absent].
  def emptyToThrowable(using Flat[A], Frame): A < (Choice & Abort[NoSuchElementException] & S) // Converts empty Choice to Abort[NoSuchElementException].
  def emptyToFailure[E](error: => E)(using Flat[A], Frame): A < (Choice & Abort[E] & S) // Converts empty Choice to a failure with the provided error.

// Extension methods on A < (Abort[E] & Async & Ctx)
extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx)):
  inline def fork(using Flat[A], Frame): Fiber[E, A] < (IO & Ctx)         // Forks the computation, returning a Fiber.
  inline def forkScoped(using Flat[A], Frame): Fiber[E, A] < (IO & Ctx & Resource) // Forks the computation in a scoped manner.

// Extension methods on Fiber[E, A] < S
extension [A, E, S](fiber: Fiber[E, A] < S):
  def join(using reduce: Reducible[Abort[E]], frame: Frame): A < (S & reduce.SReduced & Async) // Joins the fiber to get its result.
  def awaitCompletion(using Flat[A], Frame): Unit < (S & Async)             // Awaits the fiber's completion.

// Extension methods on A < (Abort[E] & Async & Ctx) for parallelism
extension [A, E, Ctx](effect: A < (Abort[E] & Async & Ctx)):
  def &>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(using /* omitted implicits */): A1 < _ // Runs in parallel, discarding this result.
  def <&[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(using /* omitted implicits */): A < _ // Runs in parallel, discarding next result.
  def <&>[A1, E1, Ctx1](next: A1 < (Abort[E1] & Async & Ctx1))(using /* omitted implicits */): (A, A1) < _ // Runs in parallel and returns both results.

// Extension methods for Emit effects
extension [A, S](effect: Unit < (Emit[Chunk[A]] & S)):
  def emitToStream: Stream[A, S]                            // Converts an Emit effect to a Stream.

extension [A, B, S](effect: B < (Emit[A] & S)):
  def handleEmit(using Tag[A], Flat[B], Frame): (Chunk[A], B) < S // Handles Emit, returning emitted values with result.
  def handleEmitDiscarding(using Tag[A], Flat[B], Frame): Chunk[A] < S // Handles Emit and discards the result.
  def foreachEmit[S1](fn: A => Unit < S1)(using /* omitted implicits */): B < (S & S1) // Applies a function to each emitted value.
  def emitToChannel(channel: Channel[A])(using Tag[A], Flat[B], Frame): B < (S & Async & Abort[Closed]) // Emits to a channel.
  def emitChunked(chunkSize: Int)(using Tag[A], Frame, Tag[A], Flat[B]): B < (Emit[Chunk[A]] & S) // Chunks emitted values.
  def emitChunkedToStreamDiscarding(chunkSize: Int)(using NotGiven[B =:= Unit], Tag[A], Flat[B], Frame): Stream[A, S] // Converts chunked Emit to a Stream, discarding result.
  def emitChunkedToStreamAndResult(using Tag[A], Flat[B], Frame)(chunkSize: Int): (Stream[A, S & Async], B < Async) < Async // Emits to Stream and provides result.

extension [A, B, S](effect: B < (Emit[Chunk[A]] & S)):
  def emitToStreamDiscarding(using NotGiven[B =:= Unit], Frame): Stream[A, S] // Converts chunked Emit to Stream, discarding result.
  def emitToStreamAndResult(using Flat[B], Frame): (Stream[A, S & Async], B < Async) < Async // Converts to Stream and returns result as effect.

extension [A, B, S](effect: Unit < (Emit[A] & S)):
  def emitChunkedToStream(chunkSize: Int)(using Tag[A], Frame): Stream[A, S] // Converts Emit to a chunked Stream.

// ----- Begin Kyo Constructors External Interface -----

// Acquires a resource and ensures its release.
def acquireRelease[A, S](acquire: => A < S)(release: A => Unit < Async)(using Frame): A < (S & Resource & IO)

// Adds a finalizer to the current effect using Resource.
def addFinalizer(finalizer: => Unit < Async)(using Frame): Unit < (Resource & IO)

// Creates an asynchronous effect that can be completed by a registration function.
def async[A](register: (A < Async => Unit) => Unit < Async)(using Flat[A], Frame): A < Async

// Attempts to run an effect and handles exceptions with Abort[Throwable].
def attempt[A, S](effect: => A < S)(using Flat[A], Frame): A < (S & Abort[Throwable])

// Collects elements from a sequence using a partial function.
def collect[A, S, A1](sequence: Seq[A])(useElement: PartialFunction[A, A1 < S])(using Frame): Seq[A1] < S

// Prints a message to the console.
def debugln(message: String)(using Frame): Unit < (IO & Abort[IOException])

// Emits a value as an effect.
def emit[A](value: A)(using Tag[A], Frame): Unit < Emit[A]

// Creates an effect that fails with Abort[E].
def fail[E](error: => E)(using Frame): Nothing < Abort[E]

// Applies a function to each element in parallel and collects the results.
inline def foreachPar[E, A, S, A1, Ctx](sequence: Seq[A])(useElement: A => A1 < (Abort[E] & Async & Ctx))(using Flat[A1], frame: Frame): Seq[A1] < (Abort[E] & Async & Ctx)

// Applies a function to each element in parallel and discards the results.
inline def foreachParDiscard[E, A, S, A1, Ctx](sequence: Seq[A])(useElement: A => A1 < (Abort[E] & Async & Ctx))(using Flat[A1], frame: Frame): Unit < (Abort[E] & Async & Ctx)

// Creates an effect from an AutoCloseable resource.
def fromAutoCloseable[A <: AutoCloseable, S](closeable: => A < S)(using Frame): A < (S & Resource & IO)

// Creates an effect from an Either, failing on a Left value.
def fromEither[E, A](either: Either[E, A])(using Frame): A < Abort[E]

// Creates an effect from an Option, failing on a None value.
def fromOption[A](option: Option[A])(using Frame): A < Abort[Absent]

// Creates an effect from a Maybe, failing on an Absent value.
def fromMaybe[A](maybe: Maybe[A])(using Frame): A < Abort[Absent]

// Creates an effect from a Result, failing on a failure.
def fromResult[E, A](result: Result[E, A])(using Frame): A < Abort[E]

// Creates an effect from a Future, handling it as Async and Abort[Throwable].
def fromFuture[A: Flat](future: => Future[A])(using Frame): A < (Async & Abort[Throwable])

// Creates an effect from a Scala Promise, handling it as Async and Abort[Throwable].
def fromPromiseScala[A: Flat](promise: => scala.concurrent.Promise[A])(using Frame): A < (Async & Abort[Throwable])

// Creates an effect from a sequence, handling it as a Choice.
def fromSeq[A](sequence: Seq[A])(using Frame): A < Choice

// Creates an effect from a Try, failing on a Failure.
def fromTry[A](_try: scala.util.Try[A])(using Frame): A < Abort[Throwable]

// Logs an informational message.
inline def logInfo(inline message: => String): Unit < IO

// Logs an informational message with an error.
inline def logInfo(inline message: => String, inline err: => Throwable): Unit < IO

// Logs a warning message.
inline def logWarn(inline message: => String): Unit < IO

// Logs a warning message with an error.
inline def logWarn(inline message: => String, inline err: => Throwable): Unit < IO

// Logs a debug message.
inline def logDebug(inline message: => String): Unit < IO

// Logs a debug message with an error.
inline def logDebug(inline message: => String, inline err: => Throwable): Unit < IO

// Logs an error message.
inline def logError(inline message: => String): Unit < IO

// Logs an error message with an error.
inline def logError(inline message: => String, inline err: => Throwable): Unit < IO

// Logs a trace message.
inline def logTrace(inline message: => String): Unit < IO

// Logs a trace message with an error.
inline def logTrace(inline message: => String, inline err: Throwable): Unit < IO

// Creates an effect that never completes.
def never(using Frame): Nothing < Async

// Provides a dependency to an effect using Env.
def provideFor[E, A, SA, ER](dependency: E)(effect: A < (SA & Env[E | ER]))(using Reducible[Env[ER]], Tag[E], Flat[A], Frame): A < (SA & Reducible[Env[ER]].SReduced)

// Creates a scoped effect from a resource using Resource.
def scoped[A, S](resource: => A < (S & Resource))(using Frame): A < (Async & S)

// Retrieves a dependency from the environment.
def service[D](using Tag[D], Frame): D < Env[D]

// Retrieves a dependency and applies a function to it.
def serviceWith[D](using Tag[D], Frame): [A, S] => (D => A < S) => A < (S & Env[D])

// Sleeps for the specified duration using Async.
def sleep(duration: Duration)(using Frame): Unit < Async

// Suspends an effect using IO.
def suspend[A, S](effect: => A < S)(using Frame): A < (S & IO)

// Suspends an effect and handles exceptions with Abort[Throwable].
def suspendAttempt[A, S](effect: => A < S)(using Flat[A], Frame): A < (S & IO & Abort[Throwable])

// Traverses a sequence of effects and collects the results.
def traverse[A, S](sequence: Seq[A < S])(using Frame): Seq[A] < S

// Traverses a sequence of effects and discards the results.
def traverseDiscard[A, S](sequence: Seq[A < S])(using Frame): Unit < S

// Traverses a sequence of effects in parallel and collects the results.
def traversePar[A](sequence: => Seq[A < Async])(using Flat[A], Frame): Seq[A] < Async

// Traverses a sequence of effects in parallel and discards the results.
def traverseParDiscard[A](sequence: => Seq[A < Async])(using Flat[A], Frame): Unit < Async

// ----- End Kyo Constructors External Interface -----

// ----- Begin Kyo.Layer External Interface -----

// Abstract class representing a composable layer for dependency injection and modular composition.
abstract class Layer[+Out, -S]

// Methods (external interface, bodies omitted):
// to: Composes this layer with another dependent layer.
def to[Out2, S2, In2](that: Layer[Out2, Env[In2] & S2]): Layer[Out2, S & S2]

// and: Combines this layer with an independent layer.
def and[Out2, S2](that: Layer[Out2, S2]): Layer[Out & Out2, S & S2]

// using: Combines this layer with another layer that depends on this layer's output.
def using[Out2, S2, In2](that: Layer[Out2, Env[In2] & S2]): Layer[Out & Out2, S & S2]

// Companion object methods:
object Layer {
  // run: Executes the layer to produce its output as a TypeMap.
  def run[R](using reduce: Reducible[Env[In]]): TypeMap[Out] < (S & reduce.SReduced & Memo)

  // empty: An empty layer that produces no output.
  val empty: Layer[Any, Any]

  // apply: Creates a layer from a Kyo effect.
  def apply[A: Tag, S](kyo: => A < S)(using Frame): Layer[A, S]

  // from (single dependency): Creates a layer from a function with one dependency.
  def from[A: Tag, B: Tag, S](f: A => B < S)(using Frame): Layer[B, Env[A] & S]

  // from (two dependencies): Creates a layer from a function with two dependencies.
  def from[A: Tag, B: Tag, C: Tag, S](f: (A, B) => C < S)(using Frame): Layer[C, Env[A & B] & S]

  // from (three dependencies): Creates a layer from a function with three dependencies.
  def from[A: Tag, B: Tag, C: Tag, D: Tag, S](f: (A, B, C) => D < S)(using Frame): Layer[D, Env[A & B & C] & S]

  // from (four dependencies): Creates a layer from a function with four dependencies.
  def from[A: Tag, B: Tag, C: Tag, D: Tag, E: Tag, S](f: (A, B, C, D) => E < S)(using Frame): Layer[E, Env[A & B & C & D] & S]

  // init: Initializes a composite layer from a sequence of layers.
  transparent inline def init[Target](inline layers: Layer[?, ?]*): Layer[Target, ?]
}

// ----- End Kyo.Layer External Interface -----

// ----- Replacement Patterns: ZLayer to kyo.Layer -----

/*
Replacement guidelines for converting ZLayer code to kyo.Layer code:

1. ZLayer.succeed(value)           ==> Layer(value)
   - Replace ZIO's succeed layers with Layer.apply for pure or effectful values.

2. ZLayer.fromService(f)           ==> Layer.from(f)
   - Use Layer.from to create a layer from a function that depends on a service.

3. ZLayer.fromFunction(f)          ==> Layer.from(f)
   - Use Layer.from for functions with one or multiple dependencies (use overloaded versions for 2, 3, or 4 inputs).

4. ZLayer combinations using ++  ==> Use the 'and' method: layer1 and layer2.
   - To combine independent layers, use Layer.and.

5. Providing dependencies: ZLayer.provideCustomLayer(layer)  ==> Use layer.run or provideValue with kyo.Env.
   - Replace ZIO's provide methods with kyo's Env.run and provideValue to inject dependencies.

6. Structured concurrency: ZLayer composition in ZIO (applying layers to an effect) can be mapped to using the init method or run method in kyo.Layer.
   - Compose multiple layers with Layer.init to form a composite environment.

These replacement patterns help map from the ZIO Layer API to the Kyo Layer API, enabling a systematic conversion.
*/

// ----- End Replacement Patterns: ZLayer to kyo.Layer -----
```
