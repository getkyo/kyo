### Please visit https://getkyo.io for an indexed version of this documentation.
##

<img src="https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png" width="200" alt="Kyo">

## Introduction

[![Build Status](https://github.com/getkyo/kyo/workflows/build/badge.svg)](https://github.com/getkyo/kyo/actions)
[![Discord](https://img.shields.io/discord/1087005439859904574)](https://discord.gg/KxxkBbW8bq)
[![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)](https://search.maven.org/search?q=g:io.getkyo)
[![javadoc](https://javadoc.io/badge2/io.getkyo/kyo-core_3/javadoc.svg)](https://javadoc.io/doc/io.getkyo/kyo-core_3)

Kyo is a powerful toolkit for Scala development, providing a rich standard library for development across Native, JVM, and JavaScript platforms.
Kyo introduces a novel approach based on algebraic effects to deliver straightforward APIs in the pure Functional Programming paradigm.

Kyo achieves this without requiring a pretext of concepts from category theory, and avoiding cryptic operators. This results in a development experience that is both intuitive and robust.

Drawing inspiration from [ZIO](https://zio.dev/)'s [effect rotation](https://degoes.net/articles/rotating-effects), Kyo generalizes this concept with a more flexible approach to effect management. While ZIO's effect system centers around two primary channels (environment and error handling), Kyo's algebraic effects allow for composing arbitrary effect types as needed. This design enables developers to work with a specialized set of effects for the problem they are trying to solve. This approach provides more granular control over computational contexts.


## [Kyo: A New Approach to Functional Effects in Scala](https://www.youtube.com/watch?v=uA2_TWP5WF4)

> This podcast gives a well rounded overview of Kyo, including live coding!

<a href="http://www.youtube.com/watch?v=uA2_TWP5WF4" title="Kyo: A New Approach to Functional Effects in Scala">
    <img src="https://img.youtube.com/vi/uA2_TWP5WF4/maxresdefault.jpg" alt="Kyo: A New Approach to Functional Effects in Scala" width="500" height="300">
</a>

### Getting Started

Kyo is structured as a monorepo, published to Maven Central:

#### Core Libraries

| Module          | JVM | JS | Native | Description                         |
|-----------------|-----|----| ------ |-------------------------------------|
| kyo-data        | ✅  | ✅ | ✅     | Efficient `Maybe`, `Result`, `Duration`, and other data types |
| kyo-kernel      | ✅  | ✅ | ✅     | Core algebraic effects engine and type-level effect tracking |
| kyo-prelude     | ✅  | ✅ | ✅     | Pure effects: `Abort`, `Env`, `Var`, `Emit`, `Choice`, etc. |
| kyo-core        | ✅  | ✅ | ✅     | Side-effectful computations: `Sync`, `Async`, `Resource`, etc. |
| kyo-direct      | ✅  | ✅ | ✅     | Direct-style syntax using `.await` and control flow |
| kyo-combinators | ✅  | ✅ | ✅     | ZIO-like effect combinators and utility methods |
| kyo-actor       | ✅  | ✅ | ✅     | Type-safe actor system with supervision and messaging |
| kyo-stm         | ✅  | ✅ | ✅     | Software transactional memory for managing state |
| kyo-offheap     | ✅  | ❌ | ✅     | Direct memory allocation and off-heap data structures |

#### Integrations

| Module               | JVM | JS | Native | Description                         |
|----------------------|-----|----| ------ |-------------------------------------|
| kyo-sttp             | ✅  | ✅ | ✅     | HTTP client using Sttp with automatic effect management |
| kyo-tapir            | ✅  | ❌ | ❌     | HTTP server endpoints using Tapir with Netty backend |
| kyo-caliban          | ✅  | ❌ | ❌     | GraphQL server using Caliban with schema derivation |
| kyo-zio              | ✅  | ✅ | ❌     | Bidirectional ZIO interop with fiber and effect conversion |
| kyo-zio-test         | ✅  | ✅ | ❌     | ZIO Test framework integration for testing Kyo effects |
| kyo-cats             | ✅  | ✅ | ❌     | Cats Effect interop with `Sync` and fiber conversion |
| kyo-cache            | ✅  | ❌ | ❌     | High-performance caching using Caffeine with memoization |
| kyo-stats-registry   | ✅  | ✅ | ✅     | Metrics collection with counters, histograms, and gauges |
| kyo-stats-otel       | ✅  | ❌ | ❌     | OpenTelemetry integration for metrics and tracing export |
| kyo-playwright       | ✅  | ❌ | ❌     | Browser automation testing using Microsoft Playwright |
| kyo-reactive-streams | ✅  | ❌ | ❌     | Reactive Streams interop implementation |
| kyo-aeron            | ✅  | ❌ | ❌     | High-performance messaging using Aeron transport |

#### Scheduler

> These modules are designed to be used independently, without requiring the Kyo effect system. These modules provide specific functionalities that can be integrated into any Scala project, regardless of whether it uses Kyo's effect system or not. They are also compiled with support for Scala 2, enabling adoption without needing to update to Scala 3.

| Module                | JVM | JS | Native | Description                         |
|-----------------------|-----|----| ------ |-------------------------------------|
| kyo-scheduler         | ✅  | ✅ | ✅     | Adaptive work-stealing scheduler with automatic parallelism |
| kyo-scheduler-cats    | ✅  | ❌ | ❌     | Drop-in Cats Effect `ExecutionContext` replacement |
| kyo-scheduler-finagle | ✅  | ❌ | ❌     | Twitter Finagle integration for improved performance |
| kyo-scheduler-pekko   | ✅  | ❌ | ❌     | Apache Pekko actor system integration |
| kyo-scheduler-zio     | ✅  | ❌ | ❌     | ZIO `Executor` implementation for better ZIO performance |

Add Kyo modules to your `build.sbt`:

```sbt
libraryDependencies += "io.getkyo" %% "kyo-prelude" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-core"    % "<version>"
```

Use `%%` for JVM/Scala Native, or `%%%` for ScalaJS cross-compilation. See the module tables above for platform support. Replace `<version>` with: ![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)


## Talks
> These talks are listed in reverse chronological order, as Kyo has grown and transformed significantly in preparation for 1.0.

| Title | Speaker(s) | Host | Date |
|-------|------------|------|------|
| [An Algebra of Thoughts: When Kyo effects meet LLMs](https://www.youtube.com/watch?v=KIjtXM5dlgY) | Flavio Brasil | Func Prog Sweden | May, 2025 |
| [Redefining Stream Composition with Algebraic Effects](https://www.youtube.com/watch?v=WcYKTyQwEA0) | Adam Hearn | LambdaConf | May, 2025 |
| [Kyo: A New Approach to Functional Effects in Scala](https://www.youtube.com/watch?v=uA2_TWP5WF4) | Flavio Brasil & Adam Hearn | Scala for Fun & Profit | February, 2025 |
| [The Actor Model Beyond Akka With Kyo](https://www.youtube.com/watch?v=VU31k3lQ8yU) | Damian Reeves | Functional Scala | December, 2024 |
| [Building Robust Applications with Kyo: A Hands on Introduction](https://www.youtube.com/watch?v=QW8mAJr0Wso) | Adam Hearn | ScalaIO | November, 2024 |
| [Comparing Approaches to Structured Concurrency](https://www.youtube.com/watch?v=g6dyLhAublQ) | James Ward & Adam Hearn | LambdaConf | May, 2024 |
| [ Algebraic Effects from Scratch](https://www.youtube.com/watch?v=qPvPdRbTF-E) | Kit Langton | Func Prog Sweden | April, 2024 |
| [Releasing Kyo: When Performance Meets Elegance In Scala](https://www.youtube.com/watch?v=FXkYKQRC9LI) | Flavio Brasil | Functional Scala | December, 2023 |


## IDE Support

Kyo utilizes features from the latest Scala 3 versions that are not yet fully supported by IntelliJ IDEA. For the best development experience, we recommend using a [Metals-based](https://scalameta.org/metals/) IDE with the SBT BSP server for improved stability. See the Metals [instructions](https://scalameta.org/metals/docs/build-tools/sbt/#sbt-build-server) to switch from Bloop to sbt BSP.

## Recommended Compiler Flags

We strongly recommend enabling these Scala compiler flags when working with Kyo to catch common mistakes and ensure proper effect handling:

1. `-Wvalue-discard`: Warns when non-Unit expression results are unused.
2. `-Wnonunit-statement`: Warns when non-Unit expressions are used in statement position.
3. `-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error`: Elevates the warnings from the previous flags to compilation errors.
4. `-language:strictEquality`: Enforces type-safe equality comparisons by requiring explicit evidence that types can be safely compared.

Add these to your `build.sbt`:

```scala 
scalacOptions ++= Seq(
    "-Wvalue-discard", 
    "-Wnonunit-statement", 
    "-Wconf:msg=(unused.*value|discarded.*value|pure.*statement):error",
    "-language:strictEquality"
)
```

These flags help catch three common issues in Kyo applications:

1. **A pure expression does nothing in statement position**: Often suggests that a Kyo computation is being discarded and will never execute, though it can also occur with other pure expressions. Common fixes include using `map` to chain transformations or explicitly handling the result.

2. **Unused/Discarded non-Unit value**: Most commonly occurs when you pass a computation to a method that can only handle some of the effects that your computation requires. For example, passing a computation that needs both `Sync` and `Abort[Exception]` effects as a method parameter that only accepts `Sync` can trigger this warning. While this warning can appear in other scenarios (like ignoring any non-Unit value), in Kyo applications it typically signals that you're trying to use a computation in a context that doesn't support all of its required effects.

3. **Values cannot be compared with == or !=**: The strict equality flag ensures type-safe equality comparisons by requiring that compared types are compatible. This is particularly important for Kyo's opaque types like `Maybe`, where comparing values of different types could lead to inconsistent behavior. The flag helps catch these issues at compile-time, ensuring you only compare values that can be meaningfully compared. For example, you cannot accidentally compare a `Maybe[Int]` with an `Option[Int]` or a raw `Int`, preventing subtle bugs. To disable the check for a specific scope, introduce an unsafe evidence: `given [A, B]: CanEqual[A, B] = CanEqual.derived`

> Note: You may want to selectively disable these warnings in test code, where it's common to assert side effects without using their returned values: `Test / scalacOptions --= Seq(options, to, disable)`

### The "Pending" type: `<`

In Kyo, computations are expressed via the infix type `<`, known as "Pending". It takes two type parameters:

1. The type of the expected output.
2. The pending effects that need to be handled, represented as an unordered type-level set via a type intersection.

```scala 
import kyo.*

// 'Int' pending 'Abort[Absent]'
// 'Absent' is Kyo's equivalent of 'None' via the 'Maybe' type
Int < Abort[Absent]

// 'String' pending 'Abort[Absent]' and 'Sync'
String < (Abort[Absent] & Sync)
```

Any type `T` is automatically considered to be of type `T < Any`, where `Any` denotes an empty set of pending effects. In simpler terms, this means that every value in Kyo is automatically a computation, but one without any effects that you need to explicitly handle.

This design choice streamlines your code by removing the necessity to differentiate between pure values and computations that may have effects. So, when you're dealing with a value of type `T < Any`, you can safely `eval` the pure value directly, without worrying about handling any effects.

```scala
import kyo.*

// An 'Int' is also an 'Int < Any'
val a: Int < Any = 1

// Since there are no pending effects,
// the computation can produce a pure value
val b: Int = a.eval
```

> Note: This README provides explicit type declarations for clarity. However, Scala's type inference is generally able to infer Kyo types properly.

This unique property removes the need to juggle between `map` and `flatMap`. All values are automatically promoted to a Kyo computation with zero pending effects, enabling you to focus on your application logic rather than the intricacies of effect handling.

```scala
import kyo.*

// Kyo still supports both `map`
// and `flatMap`.
def example1(
    a: Int < Sync,
    b: Int < Abort[Exception]
): Int < (Sync & Abort[Exception]) =
    a.flatMap(v => b.map(_ + v))

// But using only `map` is recommended
// since it functions like `flatMap` due
// to effect widening.
def example2(
    a: Int < Sync,
    b: Int < Abort[Exception]
): Int < (Sync & Abort[Exception]) =
    a.map(v => b.map(_ + v))
```

The `map` method automatically updates the set of pending effects. When you apply `map` to computations that have different pending effects, Kyo reconciles these into a new computation type that combines all the unique pending effects from both operands.

Kyo also offers an `andThen` method for more fluent code, combining two computations, discarding the result of the first one.

```scala
import kyo.*

val a: Unit < Sync =
    Sync(println("hello"))

val b: String < Sync =
    a.andThen("test")
```

The `handle` method allows for chaining effect handlers without nesting parentheses. It's particularly useful when dealing with multiple effects.

```scala
import kyo.*

val a: Int < (Abort[String] & Env[Int]) =
    for
        v <- Abort.get(Right(42))
        e <- Env.get[Int]
    yield v + e

// Handle effects using `handle`
val b: Result[String, Int] =
    a.handle(Abort.run(_))   // Handle Abort
     .handle(Env.run(10))    // Handle Env
     .eval                 // Evaluate the computation

// Equivalent without `handle`
val c: Result[String, Int] =
    Env.run(10)(Abort.run(a)).eval

// `handle` also supports multiple functions
val d: Result[String, Int] =
    a.handle(Abort.run(_), Env.run(10)).eval

// Mixing effect handling, 'map' transformation, and 'eval'
val e: Int =
    a.handle(
        Abort.run(_),
        Env.run(10),
        _.map(_.getOrElse(24)), // Convert Result to Int
        _.eval
    )
```

### Effect widening

Kyo's set of pending effects is a contravariant type parameter. This encoding permits computations to be widened to encompass a larger set of effects.

```scala
import kyo.*

// An 'Int' with an empty effect set (`Any`)
val a: Int < Any =
    1

// Widening the effect set from empty (`Any`)
// to include `Sync`
val b: Int < Sync =
    a

// Further widening the effect set to include
// both `Sync` and `Abort[Exception]`
val c: Int < (Sync & Abort[Exception]) =
    b

// Directly widening a pure value to have
// `Sync` and `Abort[Exception]`
val d: Int < (Sync & Abort[Exception]) =
    42
```

This characteristic enables a fluent API for effectful code. Methods can accept parameters with a specific set of pending effects while also permitting those with fewer or no effects.

```scala
import kyo.*

// The function expects a parameter with both
// 'Sync' and 'Abort' effects pending
def example1(v: Int < (Sync & Abort[Exception])) =
    v.map(_ + 1)

// A value with only the 'Abort' effect can be
// automatically widened to include 'Sync'
def example2(v: Int < Abort[Exception]) =
    example1(v)

// A pure value can also be automatically widened
def example3 = example1(42)
```

Here, `example1` is designed to accept an `Int < (Options & Abort[Exception])`. However, thanks to the contravariant encoding of the type-level set of effects, `example2` and `example3` demonstrate that you can also pass in computations with a smaller set of effects—or even a pure value—and they will be automatically widened to fit the expected type.

### Using effects

Effects follow a naming convention for common operations:

- `init*`: Initializes an instance of the container type handled by the effect. For instance, `Promise.init` returns a new `Promise`.
- `get*`: Allows the "extraction" of the value of the container type. `Abort.get` can turn an `Either[E, A]` into an `A < Abort[E]`.
- `run*`: Handles a given effect, transforming the result, pending any remaining effects.

Though named `run`, effect handling doesn't necessarily execute the computation immediately, as the effect handling itself can also be suspended if another effect is pending.

```scala
import kyo.*

val a: Int < Abort[Exception] = 42

// Handle the 'Options' effect
// 'Result' is similar to 'Either'
val b: Result[Exception, Int] < Any =
    Abort.run(a)

// Retrieve pure value as there are no more pending effects
val c: Result[Exception, Int] =
    b.eval
```

The order in which you handle effects in Kyo can significantly influence both the type and value of the result. Since effects are unordered at the type level, the runtime behavior depends on the sequence in which effects are processed.

```scala
import kyo.*

def abortStringFirst(a: Int < (Abort[String] & Abort[Exception])): Result[Exception, Result[String, Int]] =
    val b: Result[String, Int] < Abort[Exception] =
        Abort.run[String](a)
    val c: Result[Exception, Result[String, Int]] < Any =
        Abort.run[Exception](b)
    c.eval
end abortStringFirst

// Note how 'Abort' supports type unions. This method's parameter is equivalent to 'abortStringFirst'.
def abortExceptionFirst(a: Int < Abort[String | Exception]): Result[String, Result[Exception, Int]] =
    val b: Result[Exception, Int] < Abort[String] =
        Abort.run[Exception](a)
    val c: Result[String, Result[Exception, Int]] < Any =
        Abort.run[String](b)
    c.eval
end abortExceptionFirst

// The sequence in which effects are handled has a significant impact on the outcome.
// This is especially true for effects that can short-circuit the computation.

val ex = new Exception

// If the computation doesn't short-circuit, only the order of nested types in the result changes.
// This code uses a pure value as the computation as an example.
val a: Result[Exception, Result[String, Int]] = abortStringFirst(1)    // Result.Success(Result.Success(1))
val b: Result[String, Result[Exception, Int]] = abortExceptionFirst(1) // Result.Success(Result.Success(1))

// If there's short-circuiting, the resulting value can be different depending on the handling order.
abortStringFirst(Abort.fail("test"))    // Result.Success(Result.Fail("test"))
abortStringFirst(Abort.fail(ex))        // Result.Fail(ex)

abortExceptionFirst(Abort.fail("test")) // Result.Fail("test")
abortExceptionFirst(Abort.fail(ex))     // Result.Success(Result.Fail(ex))
```

### Direct Syntax

Kyo provides direct syntax for a more intuitive and concise way to express computations, especially when dealing with multiple effects. This syntax leverages three constructs: `direct`, `.now`, and `.later`.

The `.now` operator sequences an effect immediately, making its result available for use, while `.later` (advanced API) preserves an effect without immediate sequencing for more controlled composition.

```scala
import kyo.*

// Use the direct syntax
val a: String < (Abort[Exception] & Sync) =
    direct {
        val b: String =
            Sync("hello").now
        val c: String =
            Abort.get(Right("world")).now
        b + " " + c
    }

// Equivalent desugared
val b: String < (Abort[Exception] & Sync) =
    Sync("hello").map { b =>
        Abort.get(Right("world")).map { c =>
            b + " " + c
        }
    }
```

The `direct` macro translates the `direct` and `.now`/`.later` constructs by virtualizing control flow. It modifies value definitions, conditional branches, loops, and pattern matching to express computations in terms of `map`. 

For added safety, the direct syntax enforces effectful hygiene. Within a `direct` block, values of the `<` type must be explicitly handled using either `.now` or `.later`. This approach ensures all effectful computations are explicitly processed, reducing the potential for missed effects or operation misalignment.

```scala 
import kyo.*

// This code fails to compile
val a: Int < Sync =
    direct {
        // Incorrect usage of a '<' value
        // without '.now' or '.later'
        Sync(println(42))
        42
    }
```

> Note: In the absence of effectful hygiene, the side effect `Sync(println(42))` would be overlooked and never executed. With the hygiene in place, such code results in a compilation error.

The `.now` operator is used when you need the effect's result immediately, while `.later` is an advanced operation that preserves the effect without sequencing it:

```scala
import kyo.*

// Using .now for immediate sequencing
val immediate = direct {
    val x: Int = Sync(1).now      // Get result here
    val y: Int = Sync(2).now      // Then get this result
    x + y                       // Use both results
}

// Using .later for preserved effects
val preserved = direct {
    val effect1: Int < Sync = Sync(1).later   // Effect preserved
    val effect2: Int < Sync = Sync(2).later   // Effect preserved
    effect1.now + effect2.now             // Sequence effects
}

// Combining both approaches
val combined = direct {
    val effect1: Int < Sync = Sync(1).later   // Effect preserved
    val effect2: Int = Sync(2).now          // Effect sequenced
    effect1.now + effect2                 // Combine results
}
```

The direct syntax supports a variety of constructs to handle effectful computations. These include pure expressions, value definitions, control flow statements like `if`-`else`, logical operations (`&&` and `||`), `while`, and pattern matching.

```scala
import kyo.*

direct {
    // Pure expression
    val a: Int = 5

    // Effectful value
    val b: Int = Sync(10).now

    // Control flow
    val c: String =
        if Sync(true).now then "True branch" else "False branch"

    // Logical operations
    val d: Boolean =
        Sync(true).now && Sync(false).now

    val e: Boolean =
        Sync(true).now || Sync(true).now

    // Loop (for demonstration; this loop
    // won't execute its body)
    while Sync(false).now do "Looping"

    // Pattern matching
    val matchResult: String =
        Sync(1).now match
            case 1 => "One"
            case _ => "Other"
}
```

In addition to control flow, the direct syntax also supports working directly with data structures such as `Option[?]`, `Try[?]`, `Either[?, ?]`, `Seq[?]`, and `Map[?, ?]`. 
This enables effectful operations to be seamlessly integrated with common collections and containers:

```scala
import kyo.*

def greet(name: String): Unit < Sync =
  Console.printLine(s"hello $name!")

val maybeName: Option[String] = Some("Alice")

direct {
  maybeName.foreach(name => greet(name).now)
}

val computations: Seq[Int < Abort[String]] = 
  Seq(1, 2, Abort.fail("Oops"))

val sequenced: Seq[Int] < Abort[String] = direct {
  computations.map(_.now)
}
```

The `direct` method in Kyo mirrors Scala's `for`-comprehensions in providing a constrained yet expressive syntax. In `direct`, features like nested `direct` blocks, `var` declarations, `return` statements, `lazy val`, `lambda` and `def` with `.now`, `try`/`catch` blocks, methods and constructors accepting by-name parameters, `throw` expressions, as well as `class`, `for`-comprehension, `trait`, and `object`s are disallowed. This design allows clear virtualization of control flow, eliminating potential ambiguities or unexpected results.

The `kyo-direct` module is constructed as a wrapper around [dotty-cps-async](https://github.com/rssh/dotty-cps-async).

### Defining an App

`KyoApp` offers a structured approach similar to Scala's `App` for defining application entry points. However, it comes with added capabilities, handling a suite of default effects. As a result, the `run` method within `KyoApp` can accommodate various effects, such as Sync, Async, Resource, Clock, Console, Random, Timer, and Aspect.

```scala
import kyo.*

object MyApp extends KyoApp:
    // Use 'run' blocks to execute Kyo computations.
    // The execution of the run block is lazy to avoid
    // field initialization issues.
    run {
        for
            _            <- Console.printLine(s"Main args: $args")
            currentTime  <- Clock.now
            _            <- Console.printLine(s"Current time is: $currentTime")
            randomNumber <- Random.nextInt(100)
            _            <- Console.printLine(s"Generated random number: $randomNumber")
        yield
        // The produced value can be of any type and is
        // automatically printed to the console.
        "example"
    }
end MyApp
```

While the companion object of `KyoApp` provides a utility method to run isolated effectful computations, it's crucial to approach it with caution. Direct handling the `Sync` effect through this method compromises referential transparency, an essential property for functional programming.

```scala
import kyo.*

// An example computation
val a: Int < Sync =
    Sync(Math.cos(42).toInt)

// Avoid! Run the application with a timeout
val b: Result[Throwable, Int] =
    import AllowUnsafe.embrace.danger
    KyoApp.Unsafe.runAndBlock(2.minutes)(a)
```

### Displaying Kyo types

Due to the extensive use of opaque types in Kyo, logging Kyo values can lead to confusion, as the output of `toString` will often leave out type information we are used to seeing in boxed types. For instance, when a pure value is lifted to a pending computation, you will see only that value when you print it.

```scala
import kyo.*

val a: Int < Any = 23
Console.printLine(s"Kyo effect: $a")
// Ouput: Kyo effect: 23
```

This can be jarring to new Kyo users, since we would expect a Kyo computation to be something more than just a pure value. In fact, Kyo's ability to treat pure values as effects is part of what makes it so performant. Nevertheless, the string representations can mislead us about the types of values we log. To make things clearer, Kyo provides a `Render` utility to generate clearer string representation of types:

```scala
import kyo.*

val a: Int < Any = 23

val aStr: Text = Render.asText(a)

Console.printLine(s"Kyo effect: $aStr")
// Output: Kyo effect: Kyo(23)
```

We can still see the pure value (23) in the output, but now we can also see that it is a `Kyo`. This will work similarly for other unboxed types like `Maybe` and `Result` (see below). 

Note that `Render` does not convert to string but to `Text`--an enriched `String` alternative provided and used internally by Kyo. Kyo methods for displaying strings all accept `Text` values (see `Console` and `Log`, below). Converting values using `Render` directly can be cumbersome, however, so Kyo also provides a string interpolator to construct properly formatted `Text`s automatically. To use this interpolator, prefix your interpolated strings with `t` instead of `s`.

```scala
import kyo.*

val a: Int < Any = 23

Console.printLine(t"Kyo effect: $a, Kyo maybe: ${Maybe(23)}")
// Output: Kyo effect: Kyo(23), Kyo maybe: Present(23)
```

We recommend using `t` as the default string interpolator in Kyo applications for the best developer experience.

## Core Effects

Kyo's core effects act as the essential building blocks that power your application's various functionalities. Unlike other libraries that might require heavy boilerplate or specialized knowledge, Kyo's core effects are designed to be straightforward and flexible. These core effects not only simplify the management of side-effects, dependencies, and several other aspects but also allow for a modular approach to building maintainable systems.

### Abort: Short Circuiting

The `Abort` effect is a generic implementation for short-circuiting effects. It's equivalent to ZIO's failure channel.

```scala
import kyo.*

// The 'get' method "extracts" the value
// from an 'Either' (right projection)
val a: Int < Abort[String] =
    Abort.get(Right(1))

// short-circuiting via 'Left'
val b: Int < Abort[String] =
    Abort.get(Left("failed!"))

// short-circuiting via 'Fail'
val c: Int < Abort[String] =
    Abort.fail("failed!")

// 'catching' automatically catches exceptions
val d: Int < Abort[Exception] =
    Abort.catching(throw new Exception)
```

To handle a potentially aborting effect, you can use `Abort.run`. This will produce a `Result`, a high-performance Kyo type equivalent to `Either`:

```scala
import kyo.*

// The 'get' method "extracts" the value
// from an 'Either' (right projection)
val a: Int < Abort[String] =
    Abort.get(Right(1))

// short-circuiting via 'Left'
val b: Int < Abort[String] =
    Abort.get(Left("failed!"))

val aRes: Result[String, Int] < Any = Abort.run(a)
val bRes: Result[String, Int] < Any = Abort.run(b)

// Note we use a t-string since Result is an unboxed type
println(t"A: ${aRes.eval}, B: ${bRes.eval}")
// Output: A: Success(1), B: Fail(failed!)
```

Note that `Kyo` has two error channels: an explicitly typed channel represented by `Abort[E]` as well as a `Throwable` "panic" channel for unexpected errors. The `Result` generated by `Abort.run` includes both `Failure[E]` and `Panic` error cases. To handle `Failure[E]` without also handling `Panic`, you can use `Abort.runPartial`, which will produce a `Result.Partial[E, A]`, a subtype of `Result` consisting of only `Success[A] | Failure[E]` (note that if the effect panics it will throw the underlying exception). Alternatively, you can use `Abort.fold`, which is overloaded to handle either all three cases or just success and failure.

> Note that the `Abort` effect has a type parameter and its methods can only be accessed if the type parameter is provided.

### Sync: Side Effects

Kyo is unlike traditional effect systems since its base type `<` does not assume that the computation can perform side effects. The `Sync` effect is introduced whenever a side effect needs to be performed.

```scala
import kyo.*

def writeBytes = 1 // placeholder

// 'apply' is used to suspend side effects
val a: Int < Sync =
    Sync(writeBytes)
```

Users shouldn't typically handle the `Sync` effect directly since it triggers the execution of side effects, which breaks referential transparency. Prefer `KyoApp` instead.

In some specific cases where Kyo isn't used as the main effect system of an application, it might be necessary to handle the Sync effect directly. However, this requires explicit acknowledgment of the unsafe nature of the operation using `AllowUnsafe.embrace.danger`. The `run` method can only be used if `Sync` is the only pending effect.

```scala
import kyo.*

val a: Int < Sync =
    Sync(42)

// ** Avoid 'Sync.Unsafe.run', use 'KyoApp' instead. **
val b: Int < Abort[Nothing] =
    import AllowUnsafe.embrace.danger // Required for unsafe operations
    Sync.Unsafe.run(a)
// ** Avoid 'Sync.Unsafe.run', use 'KyoApp' instead. **
```

> IMPORTANT: Avoid handling the `Sync` effect directly since it breaks referential transparency. Use `KyoApp` instead.

### Env: Dependency Injection

`Env` is similar to ZIO's environment feature but offers more granular control. Unlike ZIO, which has built-in layering for dependencies, `Env` allows you to inject individual services directly. Env can be combined with Layers for automated dependency injection.

```scala
import kyo.*

// Given an interface
trait Database:
    def count: Int < Sync

// The 'Env' effect can be used to summon an instance.
// Note how the computation produces a 'Database' but at the
// same time requires a 'Database' from its environment
val a: Database < Env[Database] =
    Env.get[Database]

// Use the 'Database' to obtain the count
val b: Int < (Env[Database] & Sync) =
    a.map(_.count)

// A 'Database' mock implementation
val db = new Database:
    def count = 1

// Handle the 'Env' effect with the mock database
val c: Int < Sync =
    Env.run(db)(b)

// Additionally, a computation can require multiple values
// from its environment.

// A second interface to be injected
trait Cache:
    def clear: Unit < Sync

// A computation that requires two values
val d: Unit < (Env[Database] & Env[Cache] & Sync) =
    Env.get[Database].map { db =>
        db.count.map {
            case 0 =>
                Env.get[Cache].map(_.clear)
            case _ =>
                ()
        }
    }
```

### Layer: Dependency Management

The Layer effect builds upon `Env` to provide a more structured approach to dependency management. It allows you to define, compose, and provide dependencies in a modular and reusable way.

`Layer` is defined with two type parameters: `Layer[Out, S]`

1. `Out`: This represents the output type of the layer, which is the type of the dependency or service that the layer provides. It can be a single type or a combination of types using `&` as a type intersection.
2. `S`: This represents the set of effects that the layer requires to build its output. It includes any effects needed to construct the `Out` type.

For example, `Layer[Database, Sync]` represents a layer that provides a `Database` service and has the `Sync` effect to construct it.

Now, let's look at how to create and use layers:

```scala
import kyo.*

// Define some services
trait Database:
    def query: String < Sync

trait Cache:
    def get: Int < Sync

trait Logger:
    def log(msg: String): Unit < Sync

// Create layers for each service
val dbLayer: Layer[Database, Any] =
    Layer {
        new Database:
            def query = Sync("DB result")
    }

val cacheLayer: Layer[Cache, Any] =
    Layer {
        new Cache:
            def get = Sync(42)
    }

val loggerLayer: Layer[Logger, Any] =
    Layer {
        new Logger:
            def log(msg: String) = Sync(println(msg))
    }

// The `Layer.init` method provides a way to create a layer from multiple sub-layers, automatically 
// resolving dependencies between them. It can be used for more complex compositions as well
val appLayer: Layer[Database & Cache & Logger, Any] =
    Layer.init[Database & Cache & Logger](dbLayer, cacheLayer, loggerLayer)

// Use the composed layer in a computation
val computation: String < (Env[Database] & Env[Cache] & Env[Logger] & Sync) =
    for
        db     <- Env.get[Database]
        cache  <- Env.get[Cache]
        logger <- Env.get[Logger]
        _      <- logger.log("Starting query")
        result <- db.query
        _      <- logger.log(s"Query result: $result")
        cached <- cache.get
        _      <- logger.log(s"Cached value: $cached")
    yield result

// Run the computation with the composed layer
val result: String < (Sync & Memo) =
    Env.runLayer(appLayer)(computation)

// The 'Memo' effect is used by Layer to ensure components are initialized only once
val result2: String < Sync =
    Memo.run(result)
```

The `Layer` type provides instance methods for manually composing layers:

1. `to`: Combines two layers sequentially, where the output of the first layer is used as input for the second layer.
2. `and`: Combines two layers in parallel, producing a layer that provides both outputs.
3. `using`: Combines a layer with another layer that depends on its output, similar to `to` but keeps both outputs.

Here's an example that demonstrates the differences between these methods:

```scala
import kyo.*

trait Database:
    def query: String < Sync

trait UserService:
    def getUser(id: Int): String < Sync

trait EmailService:
    def sendEmail(to: String, content: String): Unit < Sync

// Define layers
val dbLayer: Layer[Database, Sync] = Layer {
    new Database:
        def query = Sync("DB result")
}

val userServiceLayer: Layer[UserService, Env[Database] & Sync] =
    Layer.from { (db: Database) =>
        new UserService:
            def getUser(id: Int) = db.query.map(result => s"User $id: $result")
    }

val emailServiceLayer: Layer[EmailService, Sync] = Layer {
    new EmailService:
        def sendEmail(to: String, content: String) =
            Sync(println(s"Email sent to $to: $content"))
}

// Example of `to`: Output of dbLayer is used as input for userServiceLayer
val dbToUserService: Layer[UserService, Sync] =
    dbLayer.to(userServiceLayer)

// Example of `and`: Combines dbLayer and emailServiceLayer in parallel
val dbAndEmail: Layer[Database & EmailService, Sync] =
    dbLayer.and(emailServiceLayer)

// Example of `using`: Similar to `to`, but keeps both Database and UserService
val dbUsingUserService: Layer[Database & UserService, Sync] =
    dbLayer.using(userServiceLayer)

// Complex composition
val fullAppLayer: Layer[Database & UserService & EmailService, Sync] =
    dbLayer.using(userServiceLayer).and(emailServiceLayer)

// Use the full app layer
val computation: Unit < (Env[Database] & Env[UserService] & Env[EmailService] & Sync) =
    for
        db           <- Env.get[Database]
        userService  <- Env.get[UserService]
        emailService <- Env.get[EmailService]
        _            <- db.query
        user         <- userService.getUser(1)
        _            <- emailService.sendEmail("user@example.com", s"User data: $user")
    yield ()

val result: Unit < (Sync & Memo) =
    Env.runLayer(fullAppLayer)(computation)
```

### Local: Scoped Values

The `Local` effect operates on top of `Sync` and enables the definition of scoped values. This mechanism is typically used to store contextual information of a computation. For example, in request processing, locals can be used to store information about the user who initiated the request. This provides a functionality similar to `ThreadLocal`s but with a more flexible scoping to effectful programs.

```scala
import kyo.*

// Local need to be initialized with a default value
val myLocal: Local[Int] =
    Local.init(0)

// The 'get' method returns the current value of the local (in this case, 0)
val a: Int < Sync =
    myLocal.get

// The 'let' method assigns a value to a local within the
// scope of a computation. This effect produces 43 (42 + 1)
val b: Int < Sync =
    myLocal.let(42)(a.map(_ + 1))
```

> Note: Kyo's effects are designed so locals are properly propagated. For example, they're automatically inherited by forked computations in `Async`.

### Resource: Resource Safety

The `Resource` effect handles the safe use of external resources like network connections, files, and any other resource that needs to be freed once the computation finalizes. It serves as a mechanism similar to ZIO's `Scope`.

```scala
import java.io.Closeable
import kyo.*

class Database extends Closeable:
    def count: Int < Sync = 42
    def close()          = {}

// The `acquire` method accepts any object that
// implements Java's `Closeable` interface
val db: Database < (Resource & Async) =
    Resource.acquire(new Database)

// Use `run` to handle the effect, while also
// closing the resources utilized by the
// computation
val b: Int < Async =
    Resource.run(db.map(_.count))

// The `ensure` method provides a low-level API to handle the finalization of
// resources directly. The `acquire` method is implemented in terms of `ensure`.

// Example method to execute a function on a database
def withDb[T](f: Database => T < Async): T < (Resource & Async) =
    // Initializes the database ('new Database' is a placeholder)
    Sync(new Database).map { db =>
        // Registers `db.close` to be finalized
        Resource.ensure(db.close).andThen {
            // Invokes the function
            f(db)
        }
    }

// Execute a function
val c: Int < (Resource & Async) =
    withDb(_.count)

// Close resources
val d: Int < Async =
    Resource.run(c)
```

### Batch: Efficient Data Processing

The `Batch` effect provides a mechanism for efficient processing of data in batches, allowing for optimized handling of datasets. It includes a type parameter `S` that represents the possible effects that can occur in the data sources.

```scala
import kyo.*

// Using 'Batch.sourceSeq' for processing the entire sequence at once, returning a 'Seq'
val source1 = Batch.sourceSeq[Int, String, Any] { seq =>
    seq.map(i => i.toString)
}

// Using 'Batch.sourceMap' for processing the entire sequence at once, returning a 'Map'
val source2 = Batch.sourceMap[Int, String, Sync] { seq =>
    // Source functions can perform arbitrary effects like 'Sync' before returning the results
    Sync {
        seq.map(i => i -> i.toString).toMap
    }
}

// Using 'Batch.source' for individual effect suspensions
// This is a more generic method that allows effects for each of the inputs
val source3 = Batch.source[Int, String, Sync] { seq =>
    val map = seq.map { i =>
        i -> Sync((i * 2).toString)
    }.toMap
    (i: Int) => map(i)
}

// Example usage
val result =
    for
        a <- Batch.eval(Seq(1, 2, 3))
        b1 <- source1(a)
        b2 <- source2(a)
        b3 <- source3(a)
    yield (a, b1, b2, b3)

// Handle the effect
val finalResult: Seq[(Int, String, String, String)] < Sync =
    Batch.run(result)
```

When creating a source, it's important to note that the returned sequence must have the same number of elements as the input sequence. This restriction ensures consistent behavior and allows for proper batching of operations.

```scala
import kyo.*

// This is valid
val validSource = Batch.sourceSeq[Int, String, Any] { seq =>
    seq.map(_.toString)
}

// This would cause a runtime error
val invalidSource = Batch.sourceSeq[Int, Int, Any] { seq =>
    seq.filter(_ % 2 == 0)
}
```

It's crucial to understand that the batching is done based on the identity of the provided source function. To ensure proper batching, it's necessary to reuse the function returned by `Batch.source`. Creating a new source for each operation will prevent effective batching. For example:

```scala
import kyo.*

// Correct usage: reusing the source
val source = Batch.sourceSeq[Int, Int, Sync] { seq => 
    Sync(seq.map(_ * 2))
}

val goodBatch = for
    a <- Batch.eval(1 to 1000)
    b <- source(a)  // This will be batched
    c <- source(b)  // This will also be batched
yield c

// Incorrect usage: creating new sources inline
val badBatch = for
    a <- Batch.eval(1 to 1000)
    b <- Batch.sourceSeq[Int, Int, Sync](seq => Sync(seq.map(_ * 2)))(a)  // This won't be batched
    c <- Batch.sourceSeq[Int, Int, Sync](seq => Sync(seq.map(_ * 2)))(b)  // This also won't be batched
yield c
```

### Choice: Exploratory Branching

The `Choice` effect is designed to aid in handling and exploring multiple options, pathways, or outcomes in a computation. This effect is particularly useful in scenario where you're dealing with decision trees, backtracking algorithms, or any situation that involves dynamically exploring multiple options.

```scala
import kyo.*

// Evaluate each of the provided `Seq`s.
// Note how 'get' takes a 'Seq[T]'
// and returns a 'T < Choice'
val a: Int < Choice =
    Choice.eval(1, 2, 3, 4)

// 'dropIf' discards the current element if
// a condition is not met. Produces a 'Seq(1, 2)'
// since values greater than 2 are dropped
val b: Int < Choice =
    a.map(v => Choice.dropIf(v > 2).map(_ => v))

// 'drop' unconditionally discards the
// current choice. Produces a 'Seq(42)'
// since only the value 1 is transformed
// to 42 and all other values are dropped
val c: Int < Choice =
    b.map {
        case 1 => 42
        case _ => Choice.drop
    }

// Handle the effect to evaluate all elements
// and return a 'Seq' with the results
val d: Seq[Int] < Any =
    Choice.run(c)
```

The `Choice` effect becomes exceptionally powerful when combined with other effects. This allows you not just to make decisions or explore options in isolation but also to do so in contexts that may involve factors such as asynchronicity, resource management, or even user interaction.


### Loop: Efficient Recursion

`Loop` provides a solution for efficient recursion in Kyo. It offers a set of methods to transform input values through repeated applications of a function until a termination condition is met, allowing for safe and efficient recursive computations without the need for explicit effect suspensions.

```scala
import kyo.*
import java.io.IOException

// Iteratively increment an 'Int' value
// until it reaches 5
val a: Int < Any =
    Loop(1)(i =>
        if i < 5 then Loop.continue(i + 1)
        else Loop.done(i)
    )

// Transform with multiple input values
val b: Int < Any =
    Loop(1, 1)((i, j) =>
        if i + j < 5 then Loop.continue(i + 1, j + 1)
        else Loop.done(i + j)
    )

// Mixing 'Sync' with 'Loop'
val d: Int < Sync =
    Loop(1)(i =>
        if i < 5 then
            Sync(println(s"Iteration: $i")).map(_ => Loop.continue(i + 1))
        else
            Loop.done(i)
    )

// Mixing 'Console' with 'Loop'
val e: Int < (Sync & Abort[IOException]) =
    Loop(1)(i =>
        if i < 5 then
            Console.printLine(s"Iteration: $i").map(_ => Loop.continue(i + 1))
        else
            Loop.done(i)
    )
```

The `transform` method takes an initial input value and a function that accepts this value. The function should return either `Loop.continue` with the next input value or `Loop.done` with the final result. The computation continues until `Loop.done` is returned. Similarly, `transform2` and `transform3` allow transformations with multiple input values. 

Here's an example showing three versions of the same computation:

```scala
import kyo.*

// Version 1: Regular while loop
def whileLoop: Int =
    var i   = 0
    var sum = 0
    while i < 10 do
        sum += i
        i += 1
    sum
end whileLoop

// Version 2: Recursive method loop
def recursiveLoop(i: Int = 0, sum: Int = 0): Int =
    if i < 10 then
        recursiveLoop(i + 1, sum + i)
    else
        sum

// Version 3: Using Loop
def loopsVersion: Int < Any =
    Loop(0, 0)((i, sum) =>
        if i < 10 then
            Loop.continue(i + 1, sum + i)
        else
            Loop.done(sum)
    )
```

In addition to the transform methods, Loop also provides indexed variants that pass the current iteration index to the transformation function. This can be useful when the logic of the loop depends on the iteration count, such as performing an action every nth iteration or terminating the loop after a certain number of iterations. The indexed methods are available with one, two, or three input values.

```scala
import kyo.*
import java.io.IOException

// Print a message every 3 iterations
val a: Int < (Sync & Abort[IOException]) =
    Loop.indexed(1)((idx, i) =>
        if idx < 10 then
            if idx % 3 == 0 then
                Console.printLine(s"Iteration $idx").map(_ => Loop.continue(i + 1))
            else
                Loop.continue(i + 1)
        else
            Loop.done(i)
    )

// Terminate the loop after 5 iterations
val b: Int < Any =
    Loop.indexed(1, 1)((idx, i, j) =>
        if idx < 5 then Loop.continue(i + 1, j + 1)
        else Loop.done(i + j)
    )

// Use the index to calculate the next value
val c: Int < Any =
    Loop.indexed(1, 1, 1)((idx, i, j, k) =>
        if idx < 5 then Loop.continue(i + idx, j + idx, k + idx)
        else Loop.done(i + j + k)
    )
```

### Memo: Function Memoization

The `Memo` effect in Kyo provides a mechanism for memoizing (caching) the results of function calls. It's implemented as a specialized `Var` effect that manages a cache of function results.

```scala
import kyo.*

val fibonacci: Int => Int < Memo = 
    Memo { n =>
        if (n <= 1) n
        else
            for
                a <- fibonacci(n - 1)
                b <- fibonacci(n - 2)
            yield a + b
    }

val result: (Int, Int) < Memo = 
    Memo.run {
        for
            fib10 <- fibonacci(10)
            fib11 <- fibonacci(11)
        yield (fib10, fib11)
    }

val result2: (Int, Int) < Any =
    Memo.run(result)
```

Key points about `Memo`:

- `Memo` memoizes function results based on both the function's input and a unique internal `MemoIdentity` for each memoized function.
- Memoization is scoped to the `Memo.run` block. A new cache is created at the start of the block and discarded at the end.
- `Memo` works seamlessly with other Kyo effects, allowing memoization of effectful computations.
- The memoization cache uses structural equality for keys, making it effective with immutable data structures.
- Each memoized function has its own cache space, even if created with identical code at different call sites.

For optimizing frequently called functions or computations in performance-critical sections of your code, the Cache effect would be more appropriate. `Memo` is designed for automatic memoization within a specific computation scope, while `Cache` provides more fine-grained control over caching behavior and better performance.

### Chunk: Efficient Sequences

`Chunk` is an efficient mechanism for processing sequences of data in a purely functional manner. It offers a wide range of operations optimized for different scenarios, ensuring high performance without compromising functional programming principles.

`Chunk` is designed as a lightweight wrapper around contiguous collections (`Array`, `IndexedSeq`), allowing for efficient random access and transformation operations. Its internal representation is carefully crafted to minimize memory allocation and ensure stack safety. Many of its operations have an algorithmic complexity of `O(1)`, making them highly performant for a variety of use cases. It extends Scala's `Seq` trait, enabling direct use within other libraries or existing codebases.

```scala
import kyo.*

// Construct chunks
val a: Chunk[Int] = Chunk(1, 2, 3)
val b: Chunk[Int] = Chunk.from(Seq(4, 5, 6))

// Perform O(1) operations
val c = a.append(4)
val d = b.take(2)
val e = c.dropLeft(1)

// Perform O(n) operations
val f = d.map(_.toString)
val g = e.filter(_ % 2 == 0)
```

`Chunk` provides two main subtypes: `Chunk` for regular chunks and `Chunk.Indexed` for indexed chunks. The table below summarizes the time complexity of various operations for each type:

| Description              | Operations                                           | Regular Chunk | Indexed Chunk |
| ------------------------ | ---------------------------------------------------- | ------------- | ------------- |
| Creation                 | `Chunk`, `Chunk.from`                                | O(n)          | O(n)          |
| Size and emptiness       | `size`, `isEmpty`                                    | O(1)          | O(1)          |
| Take and drop            | `take`, `dropLeft`, `dropRight`, `slice`             | O(1)          | O(1)          |
| Append and last          | `append`, `last`                                     | O(1)          | O(1)          |
| Element access           | `apply`, `head`, `tail`                              | N/A           | O(1)          |
| Concatenation            | `concat`                                             | O(n)          | O(n)          |
| Effectful map and filter | `map`, `filter`, `collect`, `takeWhile`, `dropWhile` | O(n)          | O(n)          |
| Effectful side effects   | `foreach`, `collectUnit`                             | O(n)          | O(n)          |
| Effectful fold           | `foldLeft`                                           | O(n)          | O(n)          |
| Copying to arrays        | `toArray`, `copyTo`                                  | O(n)          | O(n)          |
| Other operations         | `flatten`, `changes`, `toSeq`, `toIndexed`           | O(n)          | O(n)          |

When deciding between `Chunk` and `Chunk.Indexed`, consider the primary operations you'll be performing on the data. If you mainly need to `append` elements, `take` slices, or `drop` elements from the beginning or end of the sequence, `Chunk` is a good choice. Its `O(1)` complexity for these operations makes it efficient for such tasks.

```scala
import kyo.*

val a: Chunk[Int] = Chunk(1, 2, 3, 4, 5)

// Efficient O(1) operations with Chunk
val b: Chunk[Int] = a.append(6)
val c: Chunk[Int] = a.take(3)
val d: Chunk[Int] = a.dropLeft(2)
```

On the other hand, if you frequently need to access elements by index, `Chunk.Indexed` is the better option. It provides `O(1)` element access and supports `head` and `tail` operations, which are not available in `Chunk`.

```scala
import kyo.*

val a: Chunk.Indexed[Int] =
    Chunk(1, 2, 3, 4, 5).toIndexed

// Efficient O(1) operations with Chunk.Indexed
val b: Int                 = a(2)
val c: Int                 = a.head
val d: Chunk.Indexed[Int] = a.tail
```

Keep in mind that converting between `Chunk` and `Chunk.Indexed` is an `O(n)` operation, so it's best to choose the appropriate type upfront based on your usage patterns. However, calling `toIndexed` on a chunk that is already internally indexed is a no-op and does not incur any additional overhead.

Here's an overview of the main APIs available in Chunk:

```scala
import kyo.*

// Creation
val a: Chunk[Int] = Chunk(1, 2, 3)
val b: Chunk[Int] = Chunk.from(Seq(4, 5, 6))

// Size and emptiness
val c: Int     = a.size
val d: Boolean = a.isEmpty

// Take and drop
val e: Chunk[Int] = a.take(2)
val f: Chunk[Int] = a.dropLeft(1)

// Append and last
val g: Chunk[Int] = a.append(4)
val h: Int        = a.last

// Concatenation
val i: Chunk[Int] = a.concat(b)

// Copying to arrays
val n: Array[Int] = a.toArray

// Flatten a nested chunk
val o: Chunk[Int] =
    Chunk(a, b).flattenChunk

// Obtain sequentially distinct elements.
// Outputs: Chunk(1, 2, 3, 1)
val p: Chunk[Int] =
    Chunk(1, 1, 2, 3, 3, 1, 1).changes
```

### Stream: Composable Data Processing

The Stream effect provides a powerful mechanism for processing sequences of data in a memory-conscious and composable manner. It offers a rich set of operations for transforming, filtering, and combining streams of data, all while maintaining laziness and ensuring stack safety.

```scala
import kyo.*
import java.io.IOException

// Create a stream from a sequence
val a: Stream[Int, Any] =
    Stream.init(Seq(1, 2, 3, 4, 5))

// Map over stream elements
val b: Stream[String, Any] =
    a.map(_.toString)

// Filter stream elements
val c: Stream[Int, Any] =
    a.filter(_ % 2 == 0)

// Take a limited number of elements
val d: Stream[Int, Any] =
    a.take(3)

// Drop elements from the beginning
val e: Stream[Int, Any] =
    a.drop(2)

// Concatenate streams
val f: Stream[Int, Any] =
    a.concat(Stream.init(Seq(6, 7, 8)))

// FlatMap over stream elements
val g: Stream[Int, Any] =
    a.flatMap(x => Stream.init(Seq(x, x * 2)))

// Collect stream results into a Chunk
val h: Chunk[Int] < Any =
    a.run

// Process stream elements without collecting results
val i: Unit < Any =
    a.discard

// Fold over stream elements
val j: Int < Any =
    a.fold(0)(_ + _)

// Process each element with side effects
val k: Unit < (Sync & Abort[IOException]) =
    a.foreach(Console.printLine(_))
```

Streams can be combined with other effects, allowing for powerful and flexible data processing pipelines:

```scala
import kyo.*

case class Config(someConfig: String)

// Stream with Sync effect
val a: Stream[String, Sync] =
    Stream.init(Seq("file1.txt", "file2.txt"))
        .map(fileName => Sync(scala.io.Source.fromFile(fileName).mkString))

// Stream with Abort effect
val b: Stream[Int, Abort[NumberFormatException]] =
    Stream.init(Seq("1", "2", "abc", "3"))
        .map(s => Abort.catching[NumberFormatException](s.toInt))

def fetchUserData(config: Config, username: String): Seq[String] < Async =
    Seq(s"user data for $username") // mock implementation

// Combining multiple effects
val c: Stream[String, Env[Config] & Async] =
    Stream.init(Seq("user1", "user2", "user3"))
        .flatMap { username =>
            Stream.init {
                for
                    config <- Env.get[Config]
                    result <- fetchUserData(config, username)
                yield result
            }
        }

// Run the stream and handle effects
val result: Chunk[String] < (Env[Config] & Async) =
    c.run
```

To handle one or more effects used by a stream, you can pass effect transformations to the `handle` method. These will be applied to the underlying streaming effect.

```scala
import kyo.*

case class Config(someConfig: String)

val original: Stream[Int, Resource & Env[Config] & Abort[String] & Async] = Stream(1)

val handled: Stream[Int, Async] = original.handle(
    Resource.run(_),
    Env.run(Config("some config"))(_),
    Abort.run[String](_)
)
```

To fuse one or more effects used to create and run a stream, you can use `unwrap` method.

```scala
import kyo.*

case class Config(size:Int)

val original: Stream[Int, Any] < Env[Config] = Env.get[Config].map(config => Stream.range(-9, 999).take(config.size))

val unwrapped: Stream[Int, Env[Config]] = Stream.unwrap(original) // or original.unwrapStream if you are using kyo-combinators

val handled: Stream[Int, Any] = unwrapped.handle(Env.run(Config(3)))

assert(handled.run.eval == Chunk(-9, -8, -7))
```

The `Stream` effect is useful for processing large amounts of data in a memory-efficient manner, as it allows for lazy evaluation and only keeps a small portion of the data in memory at any given time. It's also composable, allowing you to build complex data processing pipelines by chaining stream operations.

Note that a number of `Stream` methods (e.g., `map`, `filter`, `mapChunk`) have variants that only accept pure functions. These variants have suffix `-Pure`. For instance, `mapPure` transforms streamed elements using a function in the form `V => V1`, whereas `map` transforms elements with a function `V => V1 < S1` allowing you to use arbitrary effects in the transformation. While any of the non-pure methods will also accept pure functions, since any value can be lifted to a pending type, the `-Pure` variants are optimized for pure functions.

#### Sink: stream evaluation

While typically it is sufficient to use the built-in methods on `Stream` to run streams and evaluate them to some value or outcome, it can also be useful to abstract the evaluation into a separate type that can be composed and manipulated separately from any actual stream. For this purpose, Kyo includes a `Sink[V, A, S]` type, inspired by ZIO's `ZSink`, which represents the processing of a stream of element type `V` into an output value of type `A` using effect types `S`.

To run a stream into a sink, you can use `Stream#into` or `Sink#consume`

All of the evaluation methods available on `Stream` are available as constructors for `Sink`s, e.g:

```scala
import kyo.*

val stream = Stream.init(1 to 10)

val collectValuesSink: Sink[Int, Chunk[Int], Any] = Sink.collect[Int]
val collectedValues: Chunk[Int] < Any = stream.into(collectValuesSink)

val printValuesSink: Sink[Int, Unit, Sync] = Sink.foreach((v: Int) => Console.printLine(s"Value: $v"))
val printedValues: Unit < Sync = stream.into(printValuesSink)

val sumValuesSink: Sink[Int, Int, Any] = Sink.fold(0)((a: Int, v: Int) => a + v)
val summedValues: Int < Any = stream.into(sumValuesSink)
```

Sinks can then be combined and transformed to produce new sinks:

```scala
import kyo.*

// built in sink to count elements in stream
val countSink: Sink[Int, Int, Any] = Sink.count[Int]
// add all integers in stream together
val sumSink: Sink[Int, Int, Any] = Sink.fold(0)((a: Int, v: Int) => a + v)
// `zip` combines sinks together, applying them to same stream elements in tandem, and
// producing a tuple of the output values of each sink.
// `map` transforms the output value
val averageSink: Sink[Int, Double, Any] = sumSink.zip(countSink).map { 
    case (sum, count) => sum / count
}
// contramap transforms the sink to consume a different stream type
val avgStringLengthSink = averageSink.contramap((str: String) => str.length)

val strings = Stream.init(Seq("one", "two", "three"))
val avgStringLength: Double < Any = strings.into(avgStringLengthSink)
```

#### Pipe: stream transformation

Just as `Sink` can be used to separate the evaluation of streams from the stream itself, `Pipe` provides the ability to construct and manipulate stream transformations independently of any particular stream. A `Pipe[A, B, S]` represents a transformation of `Stream[A, ?]` to `Stream[B, ?]` using effect type `S`.

To use a pipe to transform a stream, you can use `Stream#into` or `Pipe.transform`.

All of the transformation combinators on `Stream` are available as `Pipe` constructors, e.g.:

```scala
import kyo.*

val stream = Stream.init(1 to 10)

val mapValuesPipe: Pipe[Int, String, Any] = Pipe.map(_.toString)
val mappedStream: Stream[String, Any] = stream.into(mapValuesPipe)

val printValuesPipe: Pipe[Int, Int, Sync] = Pipe.tap((v: Int) => Console.printLine(s"Value: $v"))
val printedStream: Stream[Int, Sync] = stream.into(printValuesPipe)

val takeWhilePipe: Pipe[Int, Int, Any] = Pipe.takeWhile[Int](_ < 5)
val shorterStream: Stream[Int, Any] = stream.into(takeWhilePipe)
```

Pipes can be combined with other pipes to produce new pipes, or with sinks to produce new sinks:

```scala
import kyo.*
import scala.util.Try

val pipe1 = Pipe.map[String](v => Try(v.toInt))
val pipe2 = Pipe.collect[Try[Int]](v => Maybe.fromOption(v.toOption))
val sink = Sink.fold[Int, Int](0)(_ + _)

val pipe3 = pipe1.join(pipe2)
val sink2 = pipe3.join(sink)

val stream = Stream.init(Seq("one", "tow", "three", "fuor", "five", "xis"))
val result = stream.into(sink2)

// OR you can just run the stream `into` the pipes and sink

val result2 = stream.into(pipe1).into(pipe2).into(sink)
```

### Var: Stateful Computations

The `Var` effect allows for stateful computations, similar to the `State` monad. It enables the management of state within a computation in a purely functional manner.

```scala
import kyo.*

// Get the current value
val a: Int < Var[Int] =
    Var.get[Int]

// Set a new value and return the previous one
val b: Int < Var[Int] =
    Var.set(10)

// Update the state and return the new value
val c: Int < Var[Int] =
    Var.update[Int](v => v + 1)

// Use in a computation
val d: String < Var[Int] =
    Var.use[Int](v => v.toString)

// Handle the effect and discard state
val e: String < Any =
    Var.run(10)(d)
```

`Var` is particularly useful when you need to maintain and manipulate state across multiple steps of a computation.

```scala
import kyo.*

// A computation that uses `Var` to maintain a counter
def counter[S](n: Int): Int < (Var[Int] & S) =
    if n <= 0 then
        Var.get[Int]
    else
        for
            _      <- Var.update[Int](_ + 1)
            result <- counter(n - 1)
        yield result

// Initialize the counter with an initial state
val a: Int < Any =
    Var.run(0)(counter(10))
```

By combining Var with other effects like Async, you can create stateful computations that can be safely executed concurrently.

### Emit: Accumulating Values

The `Emit` effect is designed to accumulate values throughout a computation, similar to the `Writer` monad. It collects a `Chunk` of values alongside the main result of a computation.

```scala
import kyo.*

// Add a value
val a: Unit < Emit[Int] =
    Emit.value(42)

// Add multiple values
val b: String < Emit[Int] =
    for
        _ <- Emit.value(1)
        _ <- Emit.value(2)
        _ <- Emit.value(3)
    yield "r"

// Handle the effect to obtain the
// accumulated log and the result.
// Evaluates to `(Chunk(1, 2, 3), "r")`
val c: (Chunk[Int], String) < Any =
    Emit.run(b)

```

When running `Emit`, the accumulated values are returned in a `Chunk`. The collected values and the result are returned as a tuple by `Emit.run`, with the `Chunk` as the first element. A computation can also use multiple `Emit` of different types.

```scala
import kyo.*

val a: String < (Emit[Int] & Emit[String]) =
    for
        _ <- Emit.value(1)
        _ <- Emit.value("log")
        _ <- Emit.value(2)
    yield "result"

// Note how `run` requires an explicit type
// parameter when a computation has multiple
// pending `Sum`s.
val b: (Chunk[Int], (Chunk[String], String)) < Any =
    Emit.run[Int](Emit.run[String](a))
```

The `Emit` effect is useful for collecting diagnostic information, accumulating intermediate results, or building up data structures during a computation.

### Aspect: Aspect-Oriented Programming

The `Aspect` effect provides a way to modify or intercept behavior across multiple points in a program without directly changing the affected code. It works by allowing users to provide implementations for abstract operations at runtime, similar to dependency injection but with more powerful composition capabilities.

Aspects are created using `Aspect.init` and are typically stored as vals at module level. Once initialized, an aspect can be used to wrap computations that need to be modified, and its behavior can be customized using the `let` method to provide specific implementations within a given scope. This pattern allows for clean separation between the definition of interceptable operations and their actual implementations.

An aspect is parameterized by two type constructors, `Input[_]` and `Output[_]`, along with an effect type `S`. These type constructors define the shape of values that can be processed and produced by the aspect. The underscore in `Input[_]` and `Output[_]` indicates that these are higher-kinded types - they each take a type parameter. This allows aspects to work with generic data structures while preserving type information throughout the transformation chain.

The simplest way to work with aspects is to use `Const[A]`, which represents a plain value of type `A`. This is useful when you want to transform values directly without additional context or metadata. As you'll see in the more advanced example later, you can also create custom type constructors when you need to carry additional information through the transformation pipeline.

Here's a basic example using `Const`:

```scala
import kyo.*

case class Invalid(reason: String) extends Exception

// Simple aspect that transforms integers
val numberAspect = Aspect.init[Const[Int], Const[Int], Abort[Throwable] & Sync]

// Basic processing function
def process(n: Int): Int < (Abort[Throwable] & Sync) =
    numberAspect(n)(x => x * 2)

// Add validation via a Cut
val validationCut =
    Aspect.Cut[Const[Int], Const[Int], Abort[Throwable] & Sync](
        [C] =>
            (input, cont) =>
                if input > 0 then cont(input)
                else Abort.fail(Invalid("negative number"))
    )

// Add logging via another Cut
val loggingCut =
    Aspect.Cut[Const[Int], Const[Int], Abort[Throwable] & Sync](
        [C] =>
            (input, cont) =>
                for
                    _      <- Console.printLine(s"Processing: $input")
                    result <- cont(input)
                    _      <- Console.printLine(s"Result: $result")
                yield result
    )

// Compose both cuts into one
val composedCut =
    Aspect.Cut.andThen(validationCut, loggingCut)

// Success case
val successExample: Unit < (Abort[Throwable] & Sync) =
    for
        result <-
            numberAspect.let(composedCut) {
                process(5) // Will succeed: 5 * 2 -> 10
            }
        _ <- Console.printLine(s"Success result: $result")
    yield ()

// Failure case
val failureExample: Unit < (Abort[Throwable] & Sync) =
    for
        result <-
            numberAspect.let(composedCut) {
                process(-3) // Will fail with Invalid("negative number")
            }
        _ <- Console.printLine("This won't be reached due to Abort")
    yield ()
```

Aspects support multi-shot continuations, meaning that cut implementations can invoke the continuation function multiple times or not at all. This enables control flow modifications like retry logic, fallback behavior, or conditional execution. Internally, aspects function as a form of reified ArrowEffect that can be stored, passed around, and modified at runtime. They maintain state through a `Local` map of active implementations, allowing them to be dynamically activated and deactivated through operations like `let` and `sandbox`.

The following example demonstrates these capabilities with generic type constructors:

```scala
import kyo.*

// Define wrapper types that preserve the generic parameter
case class Request[+A](value: A, metadata: Map[String, String])
case class Response[+A](value: A, status: Int)

// Initialize aspect that can transform any Request to Response
val serviceAspect = Aspect.init[Request, Response, Sync & Abort[Throwable]]

// Example service using the aspect
def processRequest[A](request: Request[A]): Response[A] < (Sync & Abort[Throwable]) =
    serviceAspect(request) { req =>
        Response(req.value, status = 200)
    }

// Add authentication via a Cut
val authCut =
    Aspect.Cut[Request, Response, Sync & Abort[Throwable]](
        [C] =>
            (input, cont) =>
                input.metadata.get("auth-token") match
                    case Some("valid-token") => cont(input)
                    case _                   => Sync(Response(input.value, status = 401))
    )

// Add logging via another Cut
val loggingCut =
    Aspect.Cut[Request, Response, Sync & Abort[Throwable]](
        [C] =>
            (input, cont) =>
                for
                    _      <- Console.printLine(s"Processing request: ${input}")
                    result <- cont(input)
                    _      <- Console.printLine(s"Response: ${result}")
                yield result
    )

// Compose both cuts into one
val composedCut =
    Aspect.Cut.andThen(authCut, loggingCut)

// Example requests
val req1 = Request("hello", Map("auth-token" -> "valid-token"))
val req2 = Request(42, Map("auth-token" -> "invalid"))

// Use the service with both aspects
val example: Unit < (Sync & Abort[Throwable]) =
    for
        r1 <-
            serviceAspect.let(composedCut) {
                processRequest(req1)
            }
        r2 <-
            serviceAspect.let(composedCut) {
                processRequest(req2)
            }
        _ <- Console.printLine(s"Results: $r1, $r2")
    yield ()
```

### Check: Runtime Assertions

The `Check` effect provides a mechanism for runtime assertions and validations. It allows you to add checks throughout your code that can be handled in different ways, such collecting failures or discarding them.

```scala
import kyo.*

// Create a simple check
val a: Unit < Check =
    Check.require(1 + 1 == 2, "Basic math works")

// Checks can be composed with other effects
val b: Int < (Check & Sync) =
    for
        value <- Sync(42)
        _     <- Check.require(value > 0, "Value is positive")
    yield value

// Handle checks by converting the first failed check to Abort
val c: Int < (Abort[CheckFailed] & Sync) =
    Check.runAbort(b)

// Discard check failures and continue execution
val e: Int < Sync =
    Check.runDiscard(b)
```

The `CheckFailed` exception class, which is used to represent failed checks, includes both the failure message and the source code location (via `Frame`) where the check failed, making it easier to locate and debug issues.

### Console: Console Interaction

```scala
import kyo.*
import java.io.IOException

// Read a line from the console
val a: String < (Sync & Abort[IOException]) =
    Console.readLine

// Print to stdout
val b: Unit < (Sync & Abort[IOException]) =
    Console.print("ok")

// Print to stdout with a new line
val c: Unit < (Sync & Abort[IOException]) =
    Console.printLine("ok")

// Print to stderr
val d: Unit < (Sync & Abort[IOException]) =
    Console.printErr("fail")

// Print to stderr with a new line
val e: Unit < (Sync & Abort[IOException]) =
    Console.printLineErr("fail")

// Explicitly specifying the 'Console' implementation
val f: Unit < (Sync & Abort[IOException]) =
    Console.let(Console.live)(e)
```

Note that `Console.printX` methods accept `Text` values. `Text` is a super-type of `String`, however, so you can just pass regular strings. You can also pass `Text` instances generated from the `t` string interpolator ([see above](#displaying-kyo-types)).

### Clock: Time Management and Scheduled Tasks

The `Clock` effect provides utilities for time-related operations, including getting the current time, creating stopwatches, and managing deadlines.

```scala
import kyo.*

// Obtain the current time
val a: Instant < Sync =
    Clock.now

// Create a stopwatch
val b: Clock.Stopwatch < Sync =
    Clock.stopwatch

// Measure elapsed time with a stopwatch
val c: Duration < Sync =
    for
        sw      <- Clock.stopwatch
        elapsed <- sw.elapsed
    yield elapsed

// Create a deadline
val d: Clock.Deadline < Sync =
    Clock.deadline(5.seconds)

// Check time left until deadline
val e: Duration < Sync =
    for
        deadline <- Clock.deadline(5.seconds)
        timeLeft <- deadline.timeLeft
    yield timeLeft

// Check if a deadline is overdue
val f: Boolean < Sync =
    for
        deadline <- Clock.deadline(5.seconds)
        isOverdue <- deadline.isOverdue
    yield isOverdue

// Run with an explicit `Clock` implementation
val g: Instant < Sync =
    Clock.let(Clock.live)(Clock.now)
```

`Clock` both safe (effectful) and unsafe (non-effectful) versions of its operations. The safe versions are suspended in `Sync` and should be used in most cases. The unsafe versions are available through the `unsafe` property and should be used with caution, typically only in performance-critical sections or when integrating with non-effectful code.

`Clock` also offers methods to schedule background tasks:

```scala
import kyo.*

// An example computation to
// be scheduled
val a: Unit < Sync =
    Sync(())

// Recurring task with a delay between
// executions
val b: Fiber[Nothing, Unit] < Sync =
    Clock.repeatWithDelay(
        startAfter = 1.minute,
        delay = 1.minute
    )(a)

// Without an initial delay
val c: Fiber[Nothing, Unit] < Sync =
    Clock.repeatWithDelay(1.minute)(a)

// Schedule at a specific interval, regardless
// of the duration of each execution
val d: Fiber[Nothing, Unit] < Sync =
    Clock.repeatAtInterval(
        startAfter = 1.minute,
        interval = 1.minute
    )(a)

// Without an initial delay
val e: Fiber[Nothing, Unit] < Sync =
    Clock.repeatAtInterval(1.minute)(a)
```

Use the returned `Fiber` to control scheduled tasks.

```scala
import kyo.*

// Example task
val a: Fiber[Nothing, Unit] < Sync =
    Clock.repeatAtInterval(1.second)(())

// Try to cancel a task
def b(task: Fiber[Nothing, Unit]): Boolean < Sync =
    task.interrupt

// Check if a task is done
def c(task: Fiber[Nothing, Unit]): Boolean < Sync =
    task.done
```


### System: Environment Variables and System Properties

The `System` effect provides a safe and convenient way to access environment variables and system properties. It offers methods to retrieve values with proper type conversion and fallback options.

```scala
import kyo.*

// Get an environment variable as a String
val a: Maybe[String] < Sync =
    System.env[String]("PATH")

// Get an environment variable with a default value
val b: String < Sync =
    System.env[String]("CUSTOM_VAR", "default")

// Get a system property as an Int.
val c: Maybe[Int] < (Abort[NumberFormatException] & Sync) =
    System.property[Int]("java.version")

// Get a system property with a default value
val d: Int < (Abort[NumberFormatException] & Sync) =
    System.property[Int]("custom.property", 42)

// Get the line separator for the current platform
val e: String < Sync =
    System.lineSeparator

// Get the current user's name
val f: String < Sync =
    System.userName

// Use a custom System implementation
val g: String < Sync =
    System.let(System.live)(System.userName)
```

The `System` effect provides built-in parsers for common types like `String`, `Int`, `Boolean`, `Double`, `Long`, `Char`, `Duration`, and `UUID`. Custom parsers can be implemented by providing an implicit `System.Parser[E, A]` instance.

### Random: Random Values

```scala
import kyo.*

// Generate a random 'Int'
val a: Int < Sync = Random.nextInt

// Generate a random 'Int' within a bound
val b: Int < Sync = Random.nextInt(42)

// A few method variants
val c: Long < Sync    = Random.nextLong
val d: Double < Sync  = Random.nextDouble
val e: Boolean < Sync = Random.nextBoolean
val f: Float < Sync   = Random.nextFloat
val g: Double < Sync  = Random.nextGaussian

// Obtain a random value from a sequence
val h: Int < Sync =
    Random.nextValue(List(1, 2, 3))

// Explicitly specify the `Random` implementation
val k: Int < Sync =
    Random.let(Random.live)(h)
```

### Log: Logging

`Log` is designed to streamline the logging process without requiring the instantiation of a `Logger`. Log messages automatically include source code position information (File, Line, Column), enhancing the clarity and usefulness of the logs.

```scala 
import kyo.*

// Log provide trace, debug, info,
// warn, and error method variants.
val a: Unit < Sync =
    Log.error("example")

// Each variant also has a method overload
// that takes a 'Throwable' as a second param
val d: Unit < Sync =
    Log.error("example", new Exception)
```

Note that like `Console`, `Log` methods accept `Text` values. This means they can also accept regular strings as well as outputs of `t`-interpolation ([see above](#displaying-kyo-types)).

### Stat: Observability

`Stat` is a pluggable implementation that provides counters, histograms, gauges, and tracing. It uses Java's [service loading](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html) to locate exporters. 

The module [`kyo-stats-otel`](https://central.sonatype.com/artifact/io.getkyo/kyo-stats-otel_3) provides exporters for [OpenTelemetry](https://opentelemetry.io/).

```scala
import kyo.*
import kyo.stats.*

// Initialize a Stat instance
// for a scope path
val stats: Stat =
    Stat.initScope("my_application", "my_module")

// Initialize a counter
val a: Counter =
    stats.initCounter("my_counter")

// It's also possible to provide
// metadata when initializing
val b: Histogram =
    stats.initHistogram(
        name = "my_histogram",
        description = "some description"
    )

// Gauges take a by-name function to
// be observed periodically
val c: Gauge =
    stats.initGauge("free_memory") {
        Runtime.getRuntime().freeMemory().toDouble
    }
```

Metrics are automatically garbage collected once no strong references to them are present anymore.

> Note: Although stats initialization perform side effects, Kyo chooses to consider the operation pure since stats are meant to be initialized in a static scope for optimal performance.

Tracing can be performed via the `traceSpan` method. It automatically initializes the span and closes it at the end of the traced computation even in the presence of failures or asynchronous operations. Nested traces are bound to their parent span via `Local`.

```scala
import kyo.*

val stats2: Stat =
    Stat.initScope("my_application", "my_module")

// Some example computation
val a: Int < Sync =
    Sync(42)

// Trace the execution of the
// `a` example computation
val b: Int < Sync =
    stats2.traceSpan("my_span")(a)
```

### Path: File System Utilities

`Path` provides utilities for interacting with the file system. It offers methods for reading, writing, and manipulating files and directories in a purely functional manner.

```scala
import kyo.*

// Create a Path instance representing a path
val path: Path = Path("tmp", "file.txt")

// Read the entire contents of a file as a String
val content: String < Sync =
    path.read

// Write a String to a file
val writeResult: Unit < Sync =
    path.write("Hello, world!")

// Check if a path exists
val exists: Boolean < Sync =
    path.exists

// Create a directory
val createDir: Unit < Sync =
    Path("tmp", "test").mkDir
```

`Path` instances are created by providing a list of path segments, which can be either `String`s or other `Path` instances. This allows for easy composition of paths. `Path` also provides methods for other common file operations:

- Reading: `read`, `readBytes`, `readLines`, `readStream`, `readLinesStream`, `readBytesStream`
- Writing: `write`, `writeBytes`, `writeLines`, `append`, `appendBytes`, `appendLines`
- Directory operations: `list`, `walk`
- File metadata: `exists`, `isDir`, `isFile`, `isLink`
- File manipulation: `mkDir`, `mkFile`, `move`, `copy`, `remove`, `removeAll`

All methods that perform side effects are suspended using the `Sync` effect, ensuring referential transparency. Methods that work with streams of data, such as `readStream` and `walk`, return a `Stream` of the appropriate type, suspended using the `Resource` effect to ensure proper resource handling.

```scala
import kyo.*
import java.io.IOException

val path: Path = Path("tmp", "file.txt")

// Read a file as a stream of lines
val lines: Stream[String, Resource & Sync] =
    path.readLinesStream()

// Process the stream
val result: Unit < (Resource & Console & Async & Abort[IOException]) =
    lines.map(line => Console.printLine(line)).discard

// Walk a directory tree
val tree: Stream[Path, Sync] =
    Path("tmp").walk

// Process each file in the tree
val processedTree: Unit < (Console & Async & Abort[IOException]) =
    tree.map(file => file.read.map(content => Console.printLine(s"File: ${file}, Content: $content"))).discard
```

`Path` integrates with Kyo's `Stream` API, allowing for efficient processing of file contents using streams. The `sink` and `sinkLines` extension methods on `Stream` enable writing streams of data back to files.

```scala
import kyo.*

// Create a stream of bytes
val bytes: Stream[Byte, Sync] = Stream.init(Seq[Byte](1, 2, 3))

// Write the stream to a file
val sinkResult: Unit < (Resource & Sync) =
    bytes.sink(Path("path", "to", "file.bin"))
```

### Process: Process Execution

`Process` provides a way to spawn and interact with external processes from within Kyo. It offers a purely functional interface for process creation, execution, and management.

```scala
import kyo.*

// Create a simple command
val command: Process.Command = Process.Command("echo", "Hello, World!")

// Spawn the process and obtain the result
val result: String < Sync = command.text
```

The core of `Process` is the `Process.Command` type, which represents a command to be executed. It can be created using the `Process.Command.apply` method, which takes a variable number of arguments representing the command and its arguments.

The `Process` object also provides a `jvm` sub-object for spawning JVM processes directly.

```scala
import kyo.*

class MyClass extends KyoApp:
    run {
        Console.printLine(s"Executed with args: $args")
    }
end MyClass

// Spawn a new JVM process
val jvmProcess: Process < Sync =
    Process.jvm.spawn(classOf[MyClass], List("arg1", "arg2"))
```

Once a `Process.Command` is created, it can be executed using various methods:

- `spawn`: Spawns the process and returns a `Process` instance.
- `text`: Spawns the process, waits for it to complete, and returns the standard output as a string.
- `stream`: Spawns the process and returns an `InputStream` of the standard output.
- `exitValue`: Spawns the process, waits for it to complete, and returns the exit code.
- `waitFor`: Spawns the process, waits for it to complete, and returns the exit code.

`Process.Command` instances can be transformed and combined using methods like `pipe`, `andThen`, `+`, `map`, and `cwd`, `env`, `stdin`, `stdout`, `stderr` for modifying the process's properties.

```scala
import java.io.File
import java.nio.file.Path
import kyo.*

// Create a piped command
val pipedCommand = Process.Command("echo", "Hello, World!").pipe(Process.Command("wc", "-w"))

// Modify the command's environment and working directory
val modifiedCommand = pipedCommand.env(Map("VAR" -> "value")).cwd(Path.of("/path/to/dir"))

// Spawn the modified command
val modifiedResult: String < Sync = modifiedCommand.text
```

`Process` also provides `Input` and `Output` types for fine-grained control over the process's standard input, output, and error streams.

```scala
import java.io.File
import kyo.*

// Create a command with custom input and output
val command = Process.Command("my-command")
    .stdin(Process.Input.fromString("input data"))
    .stdout(Process.Output.FileRedirect(new File("output.txt")))
    .stderr(Process.Output.Inherit)
```

The `Process` type returned by `spawn` provides methods for interacting with the spawned process, such as `waitFor`, `exitValue`, `destroy`, and `isAlive`.

## Concurrent Effects

The `kyo.concurrent` package provides utilities for dealing with concurrency in Scala applications. It's a powerful set of effects designed for easier asynchronous programming, built on top of other core functionalities provided by the `kyo` package.

### Async: Green Threads

The `Async` effect allows for the asynchronous execution of computations via a managed thread pool. The core function, `run`, spawns a new "green thread," also known as a fiber, to handle the given computation. This provides a powerful mechanism for parallel execution and efficient use of system resources. Moreover, fibers maintain proper propagation of `Local`, ensuring that context information is carried along during the forking process.

```scala
import kyo.*

// Fork a computation. The parameter is
// taken by reference and automatically
// suspended with 'Sync'
val a: Fiber[Nothing, Int] < Sync =
    Async.run(Math.cos(42).toInt)

// It's possible to "extract" the value of a
// 'Fiber' via the 'get' method. This is also
// referred as "joining the fiber"
val b: Int < Async =
    a.map(_.get)
```

The `zip` and `collectAll` methods fork multiple computations concurrently, join the fibers, and return their results.

```scala
import kyo.*

// An example computation
val a: Int < Sync =
    Sync(Math.cos(42).toInt)

// There are method overloadings for up to four
// parallel computations. Parameters taken by
// reference
val b: (Int, String) < Async =
    Async.zip(a, "example")

// Run with unlimited concurrency - starts all
// computations immediately
val c: Seq[Int] < Async =
    Async.collectAll(Seq(a, a.map(_ + 1)))

// Run with controlled concurrency (max 2 tasks)
val d: Seq[Int] < Async =
    Async.collectAll(Seq(a, a.map(_ + 1)), concurrency = 2)

```

If any computation fails or is interrupted, all other computations are automatically interrupted.

The `race` methods are similar to `parallel` but they return the first computation to complete with either a successful result or a failure. Once the first result is produced, the other computations are automatically interrupted.

```scala
import kyo.*

// An example computation
val a: Int < Sync =
    Sync(Math.cos(42).toInt)

// There are method overloadings for up to four
// computations. Parameters taken by reference
val b: Int < Async =
    Async.race(a, a.map(_ + 1))

// It's also possible to to provide a 'Seq'
// of computations
val c: Int < Async =
    Async.race(Seq(a, a.map(_ + 1)))
```

The `sleep` and `timeout` methods pause a computation or time it out after a duration.

```scala
import kyo.*

// A computation that sleeps for 1s
val a: Unit < Async =
    Async.sleep(1.second)

// Times out and interrupts the provided
// computation in case it doesn't produce
// a result within 1s
val b: Int < (Abort[Timeout] & Async) =
    Async.timeout(1.second)(Math.cos(42).toInt)
```

The `fromFuture` method provide interoperability with Scala's `Future`.

```scala
import kyo.*
import scala.concurrent.Future

// An example 'Future' instance
val a: Future[Int] = Future.successful(42)

// Transform a 'Future' into a 'Fiber'
val b: Fiber[Throwable, Int] < Sync =
    Fiber.fromFuture(a)
```

> Important: Keep in mind that Scala's Future lacks built-in support for interruption. As a result, any computations executed through Future will run to completion, even if they're involved in a race operation where another computation finishes first.

A `Fiber` instance also provides a few relevant methods.

```scala
import kyo.*
import scala.concurrent.*

// An example fiber
val a: Fiber[Nothing, Int] = Fiber.success(42)

// Check if the fiber is done
val b: Boolean < Sync =
    a.done

// Instance-level version of 'Async.get'
val c: Int < Async =
    a.get

// Avoid this low-level API to attach a
// a callback to a fiber
val d: Unit < Sync =
    a.onComplete(println(_))

// A variant of `get` that returns a `Result`
// with the failed or successful result
val e: Result[Nothing, Int] < Async =
    a.getResult

// Try to interrupt/cancel a fiber
val f: Boolean < Sync =
    a.interrupt

// Transforms a fiber into a Scala 'Future'
val h: Future[Int] < Sync =
    a.toFuture

// 'Fiber' provides a monadic API with both
// 'map' and 'flatMap'
val i: Fiber[Nothing, Int] < Sync =
    a.flatMap(v => Fiber.success(v + 1))
```

Similarly to `Sync`, users should avoid handling the `Async` effect directly and rely on `KyoApp` instead. If strictly necessary, there are two methods to handle the `Async` effect:

1. `run` takes a computation that has only the `Async` effect pending and returns a `Fiber` instance without blocking threads.
2. `runAndBlock` accepts computations with arbitrary pending effects but it handles asynchronous operations by blocking the current thread.

```scala
import kyo.*

// An example computation with fibers
val a: Int < Async =
    Async.run(Math.cos(42).toInt).map(_.get)

// Avoid handling 'Async' directly
val b: Fiber[Nothing, Int] < Sync =
    Async.run(a)

// The 'runAndBlock' method accepts
// arbitrary pending effects but relies
// on thread blocking and requires a timeout
val c: Int < (Abort[Timeout] & Sync) =
    Async.runAndBlock(5.seconds)(a)
```

> Note: Handling the `Async` effect doesn't break referential transparency as with `Sync` but its usage is not trivial due to the limitations of the pending effects. Prefer `KyoApp` instead.

The `Async` effect also offers a low-level API to create `Promise`s as way to integrate external async operations with fibers. These APIs should be used only in low-level integration code.

```scala
import kyo.*

// Initialize a promise
val a: Promise[Nothing, Int] < Sync =
    Promise.init[Nothing, Int]

// Try to fulfill a promise
val b: Boolean < Sync =
    a.map(_.complete(Result.succeed(42)))

// Fullfil the promise with
// another fiber
val c: Boolean < Sync =
    a.map(fiber => Async.run(1).map(fiber.become(_)))
```

> A `Promise` is basically a `Fiber` with all the regular functionality plus the `complete` and `become` methods to manually fulfill the promise.

## Retry: Automatic Retries

`Retry` provides a mechanism for retrying computations that may fail, with configurable policies for backoff and retry limits. This is particularly useful for operations that might fail due to transient issues, such as network requests or database operations.

```scala
import kyo.*
import scala.concurrent.duration.*

// Define a computation that might fail
val unreliableComputation: Int < Abort[Exception] =
    Abort.catching[Exception](throw new Exception("Temporary failure"))

// Customize retry schedule
val schedule = 
    Schedule.exponentialBackoff(initial = 100.millis, factor = 2, maxBackoff = 5.seconds)
        .take(5)

val a: Int < (Abort[Exception] & Async) =
    Retry[Exception](schedule)(unreliableComputation)

```

The `Retry` effect automatically adds the `Async` effect to handle the provided `Schedule`. `Retry` will continue attempting the computation until it succeeds, the retry schedule is done, or an unhandled exception is thrown. If all retries fail, the last failure is propagated.

### Queue: Concurrent Queuing

The `Queue` effect operates atop of `Sync` and provides thread-safe queue data structures based on the high-performance [JCTools](https://github.com/JCTools/JCTools) library on the JVM. For ScalaJS, a simple `ArrayQueue` is used.

> Warning: The actual capacity of a `Queue` is rounded up to the next power of two for performance reasons. For example, if you specify a capacity of `10`, the actual capacity will be `16`.

**Bounded queues**
```scala
import kyo.*

// A bounded queue that rejects new
// elements once full
val a: Queue[Int] < Sync =
    Queue.init(capacity = 42)

// Obtain the number of items in the queue
// via the method 'size' in 'Queue'
val b: Int < (Sync & Abort[Closed]) =
    a.map(_.size)

// Get the queue capacity
val c: Int < Sync =
    a.map(_.capacity)

// Try to offer a new item
val d: Boolean < (Sync & Abort[Closed]) =
    a.map(_.offer(42))

// Try to poll an item
val e: Maybe[Int] < (Sync & Abort[Closed]) =
    a.map(_.poll)

// Try to 'peek' an item without removing it
val f: Maybe[Int] < (Sync & Abort[Closed]) =
    a.map(_.peek)

// Check if the queue is empty
val g: Boolean < (Sync & Abort[Closed]) =
    a.map(_.empty)

// Check if the queue is full
val h: Boolean < (Sync & Abort[Closed]) =
    a.map(_.full)

// Drain the queue items
val i: Seq[Int] < (Sync & Abort[Closed]) =
    a.map(_.drain)

// Close the queue. If successful,
// returns a Some with the drained
// elements
val j: Maybe[Seq[Int]] < Sync =
    a.map(_.close)
```

**Unbounded queues**
```scala
import kyo.*

// Avoid `Queue.unbounded` since if queues can
// grow without limits, the GC overhead can make
// the system fail
val a: Queue.Unbounded[Int] < Sync =
    Queue.Unbounded.init()

// A 'dropping' queue discards new entries
// when full
val b: Queue.Unbounded[Int] < Sync =
    Queue.Unbounded.initDropping(capacity = 42)

// A 'sliding' queue discards the oldest
// entries if necessary to make space for new
// entries
val c: Queue.Unbounded[Int] < Sync =
    Queue.Unbounded.initSliding(capacity = 42)

// Note how 'dropping' and 'sliding' queues
// return 'Queue.Unbounded`. It provides
// an additional method to 'add' new items
// unconditionally
val d: Unit < Sync =
    c.map(_.add(42))
```

**Concurrent Access Policies**

It's also possible to specify a concurrent `Access` policy as the second parameter of the `Queue.init` methods. This configuration has an effect only on the JVM and is ignored in ScalaJS.

| Policy | Full Form                              | Description                                                                                                                                                                                                          |
| ------ | -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| MultiProducerMultiConsumer   | Multiple Producers, Multiple Consumers | Supports multiple threads/fibers simultaneously enqueuing and dequeuing elements. This is the most flexible but may incur the most overhead due to the need to synchronize between multiple producers and consumers. |
| MultiProducerSingleConsumer   | Multiple Producers, Single Consumer    | Allows multiple threads/fibers to enqueue elements but restricts dequeuing to a single consumer. This can be more efficient than `Mpmc` when only one consumer is needed.                                            |
| SingleProducerMultiConsumer   | Single Producer, Multiple Consumers    | Allows only a single thread/fiber to enqueue elements, but multiple threads/fibers can dequeue elements. Useful when only one source is generating elements to be processed by multiple consumers.                   |
| SingleProducerSingleConsumer   | Single Producer, Single Consumer       | The most restrictive but potentially fastest policy. Only one thread/fiber can enqueue elements, and only one thread/fiber can dequeue elements.                                                                     |

Each policy is suitable for different scenario and comes with its own trade-offs. For example, `Mpmc` is highly flexible but can be slower due to the need for more complex synchronization. `Spsc`, being the most restrictive, allows for optimizations that could make it faster for specific single-producer, single-consumer scenario.

You can specify the access policy when initializing a queue, and it is important to choose the one that aligns with your application's needs for optimal performance.

```scala
import kyo.*

// Initialize a bounded queue with a
// Multiple Producers, Multiple
// Consumers policy
val a: Queue[Int] < Sync =
    Queue.init(
        capacity = 42,
        access = Access.MultiProducerMultiConsumer
    )
```

### Channel: Backpressured Communication

The `Channel` effect serves as an advanced concurrency primitive, designed to facilitate seamless and backpressured data transfer between various parts of your application. Built upon the `Async` effect, `Channel` not only ensures thread-safe communication but also incorporates a backpressure mechanism. This mechanism temporarily suspends fibers under specific conditions—either when waiting for new items to arrive or when awaiting space to add new items.

> Warning: The actual capacity of a `Channel` is rounded up to the next power of two for performance reasons. For example, if you specify a capacity of `10`, the actual capacity will be `16`.

```scala    
import kyo.*

// A 'Channel' is initialized
// with a fixed capacity
val a: Channel[Int] < Sync =
    Channel.init(capacity = 42)

// It's also possible to specify
// an 'Access' policy
val b: Channel[Int] < Sync =
    Channel.init(
        capacity = 42,
        access = Access.MultiProducerMultiConsumer
    )
```

While `Channel` share similarities with `Queue`—such as methods for querying size (`size`), adding an item (`offer`), or retrieving an item (`poll`)—they go a step further by offering backpressure-sensitive methods, namely `put` and `take`.

```scala
import kyo.*

// An example channel
val a: Channel[Int] < Sync =
    Channel.init(capacity = 42)

// Adds a new item to the channel.
// If there's no capacity, the fiber
// is automatically suspended until
// space is made available
val b: Unit < (Async & Abort[Closed]) =
    a.map(_.put(42))

// Takes an item from the channel.
// If the channel is empty, the fiber
// is suspended until a new item is
// made available
val c: Int < (Async & Abort[Closed]) =
    a.map(_.take)

// Closes the channel. If successful,
// returns a Some with the drained
// elements. All pending puts and takes
// are automatically interrupted
val f: Maybe[Seq[Int]] < Sync =
    a.map(_.close)
```

The ability to suspend fibers during `put` and `take` operations allows `Channel` to provide a more controlled form of concurrency. This is particularly beneficial for rate-sensitive or resource-intensive tasks where maintaining system balance is crucial.

> Important: While a `Channel` comes with a predefined capacity, it's crucial to understand that there is no upper limit on the number of fibers that can be suspended by it. In scenario where your application spawns an unrestricted number of fibers—such as an HTTP service where each incoming request initiates a new fiber—this can lead to significant memory consumption. The channel's internal queue for suspended fibers could grow indefinitely, making it a potential source of unbounded queuing and memory issues. Exercise caution in such use-cases to prevent resource exhaustion.

### Hub: Broadcasting with Backpressure

`Hub` provide a broadcasting mechanism where messages are sent to multiple listeners simultaneously. They are similar to `Channel`, but they are uniquely designed for scenario involving multiple consumers. The key feature of `Hub` is their ability to apply backpressure automatically. This means if the `Hub` and any of its listeners' buffers are full, the `Hub` will pause both the producers and consumers to prevent overwhelming the system. Unlike `Channel`, `Hub` don't offer customization in concurrent access policy as they are inherently meant for multi-producer, multi-consumer environments.

```scala
import kyo.*
import kyo.Hub.Listener

// Initialize a Hub with a buffer
val a: Hub[Int] < (Sync & Resource) =
    Hub.init[Int](3)

// Hub provide APIs similar to
// channels: size, offer, isEmpty,
// isFull, put
val b: Boolean < (Sync & Abort[Closed] & Resource) =
    a.map(_.offer(1))

// But reading from hubs can only
// happen via listener. Listeners
// only receive messages sent after
// their creation. To create call
// `listen`:
val c: Listener[Int] < (Sync & Abort[Closed] & Resource) =
    a.map(_.listen)

// Each listener can have an
// additional message buffer
val d: Listener[Int] < (Sync & Abort[Closed] & Resource) =
    a.map(_.listen(bufferSize = 3))

// Listeners provide methods for
// receiving messages similar to
// channels: size, isEmpty, isFull,
// poll, take
val e: Int < (Async & Abort[Closed] & Resource) =
    d.map(_.take)

// A listener can be closed
// individually. If successful,
// a Some with the backlog of
// pending messages is returned
val f: Maybe[Seq[Int]] < (Sync & Abort[Closed] & Resource) =
    d.map(_.close)

// Listeners are also managed
// resources. They are closed 
// when their `Resource` effect
// is handled
val g: Int < (Async & Abort[Closed]) =
    Resource.run(e)

// If the Hub is closed, all
// listeners are automatically
// closed. The returned backlog
// only include items pending in
// the hub's buffer. The listener
// buffers are discarded
val h: Maybe[Seq[Int]] < (Sync & Resource) =
    a.map(_.close)
```

Hub are implemented with an internal structure that efficiently manages message distribution. At their core, Hub utilize a single channel for incoming messages. This central channel acts as the primary point for all incoming data. For each listener attached to a Hub, a separate channel is created. These individual channels are dedicated to each listener, ensuring that messages are distributed appropriately.

The functioning of Hub is orchestrated by a dedicated fiber. This fiber continuously monitors the main incoming channel. Whenever a new message arrives, it takes this message and concurrently distributes it to all the listener channels. This process involves submitting the message to each listener's channel in parallel, ensuring simultaneous delivery of messages.

After distributing a message, the fiber waits until all the listener channels have successfully received it. This waiting mechanism is crucial for maintaining the integrity of message distribution, ensuring that each listener gets the message before the fiber proceeds to the next one and backpressure is properly applied.

### Meter: Computational Limits

The `Meter` effect offers utilities to regulate computational execution, be it limiting concurrency or managing rate. It is equipped with a range of pre-set limitations, including mutexes, semaphores, and rate limiters, allowing you to apply fine-grained control over task execution.

```scala
import kyo.*

// 'mutex': One computation at a time
val a: Meter < Sync =
    Meter.initMutex

// 'semaphore': Limit concurrent tasks
val b: Meter < Sync =
    Meter.initSemaphore(concurrency = 42)

// 'rateLimiter': Tasks per time window
val c: Meter < Sync =
    Meter.initRateLimiter(
        rate = 10,
        period = 1.second
    )

// 'pipeline': Combine multiple 'Meter's
val d: Meter < Sync =
    Meter.pipeline(a, b, c)
```

The `Meter` class comes with a handful of methods designed to provide insights into and control over computational execution.

```scala
import kyo.*

// An example 'Meter'
val a: Meter < Sync =
    Meter.initMutex

// Get the number available permits
val b: Int < (Async & Abort[Closed]) =
    a.map(_.availablePermits)

// Get the number of waiting fibers
val c: Int < (Async & Abort[Closed]) =
    a.map(_.pendingWaiters)

// Use 'run' to execute tasks
// respecting meter limits
val d: Int < (Async & Abort[Closed]) =
    a.map(_.run(Math.cos(42).toInt))

// 'tryRun' executes if a permit is
// available; returns 'None' otherwise
val e: Maybe[Int] < (Async & Abort[Closed]) =
    a.map(_.tryRun(Math.cos(42).toInt))
```

### Latch: Countdown Synchronization

The `Latch` effect serves as a coordination mechanism for fibers in a concurrent environment, primarily used for task synchronization. It provides a low-level API for controlling the flow of execution and ensuring certain tasks are completed before others, all while maintaining thread safety.

```scala
import kyo.*

// Initialize a latch with 'n' permits
val a: Latch < Sync =
    Latch.init(3)

// Await until the latch releases
val b: Unit < Async =
    a.map(_.await)

// Release a permit from the latch
val c: Unit < Sync =
    a.map(_.release)

// Get the number of pending permits
val d: Int < Sync =
    a.map(_.pending)
```

### Barrier: Multi-party Rendezvous

The `Barrier` effect provides a synchronization primitive that allows a fixed number of parties to wait for each other to reach a common point of execution. It's particularly useful in scenarios where multiple fibers need to synchronize their progress.

```scala
import kyo.*

// Initialize a barrier for 3 parties
val a: Barrier < Sync =
    Barrier.init(3)

// Wait for the barrier to be released
val b: Unit < Async =
    a.map(_.await)

// Get the number of parties still waiting
val c: Int < Sync =
    a.map(_.pending)

// Example usage with multiple fibers
val d: Unit < Async =
    for
        barrier <- Barrier.init(3)
        _       <- Async.zip(
                     barrier.await,
                     barrier.await,
                     barrier.await
                   )
    yield ()

// Fibers can join the barrier at different points of the computation
val e: Unit < Async =
    for
        barrier <- Barrier.init(3)
        fiber1  <- Async.run(Async.sleep(1.second))
        fiber2  <- Async.run(Async.sleep(2.seconds))
        _       <- Async.zip(
                     fiber1.get.map(_ => barrier.await),
                     fiber2.get.map(_ => barrier.await),
                     Async.run(barrier.await).map(_.get)
                   )
    yield ()
```

The `Barrier` is initialized with a specific number of parties. Each party calls `await` when it reaches the barrier point. The barrier releases all waiting parties when the last party arrives. After all parties have been released, the barrier cannot be reset or reused.

### Atomic: Concurrent State

The `Atomic` effect provides a set of thread-safe atomic variables to manage mutable state in a concurrent setting. Available atomic types include Int, Long, Boolean, and generic references.

```scala
import kyo.*

// Initialize atomic variables
val aInt: AtomicInt < Sync =
    AtomicInt.init(0)
val aLong: AtomicLong < Sync =
    AtomicLong.init(0L)
val aBool: AtomicBoolean < Sync =
    AtomicBoolean.init(false)
val aRef: AtomicRef[String] < Sync =
    AtomicRef.init("initial")

// Fetch values
val b: Int < Sync =
    aInt.map(_.get)
val c: Long < Sync =
    aLong.map(_.get)
val d: Boolean < Sync =
    aBool.map(_.get)
val e: String < Sync =
    aRef.map(_.get)

// Update values
val f: Unit < Sync =
    aInt.map(_.set(1))
val g: Unit < Sync =
    aLong.map(_.lazySet(1L))
val h: Boolean < Sync =
    aBool.map(_.compareAndSet(false, true))
val i: String < Sync =
    aRef.map(_.getAndSet("new"))
```

### Adder: Concurrent Accumulation

The `Adder` effect offers thread-safe variables for efficiently accumulating numeric values. The two primary classes, `LongAdder` and `DoubleAdder`, are optimized for high-throughput scenario where multiple threads update the same counter.

```scala
import kyo.*

// Initialize Adder
val longAdder: LongAdder < Sync =
    LongAdder.init
val doubleAdder: DoubleAdder < Sync =
    DoubleAdder.init

// Adding values
val a: Unit < Sync =
    longAdder.map(_.add(10L))
val b: Unit < Sync =
    doubleAdder.map(_.add(10.5))

// Increment and Decrement LongAdder
val c: Unit < Sync =
    longAdder.map(_.increment)
val d: Unit < Sync =
    longAdder.map(_.decrement)

// Fetch summed values
val e: Long < Sync =
    longAdder.map(_.get)
val f: Double < Sync =
    doubleAdder.map(_.get)

// Resetting the adders
val g: Unit < Sync =
    longAdder.map(_.reset)
val h: Unit < Sync =
    doubleAdder.map(_.reset)
```

### Debug: Interactive Development

The `Debug` effect is a powerful tool for developers during the development process. Unlike other effects in Kyo, `Debug` intentionally performs side effects (printing to the console) without effect suspensions to provide immediate, visible feedback to developers. This makes it a valuable tool for debugging and understanding code behavior, but it's crucial to use it only in development environments and remove it before moving to production.

```scala
import kyo.*

// Note that 'Debug' requires a separate import
import kyo.debug.*

// Wraps a computation, printing the source code location,
// and the result (or exception) of the computation
val a: Int < Sync =
    Debug {
        Sync(42)
    }

// Similar to `apply`, but also prints intermediate steps
// of the computation, providing a trace of execution
val b: Int < Sync =
    Debug.trace {
        Sync(41).map(_ + 1)
    }

// Allows printing of specific values along with their 
// variable names, useful for inspecting particular states.
// The return type of 'values' is 'Unit', not an effectful
// computation.
val c: Unit < Sync =
    Sync {
        val x = 42
        val y = "Hello"
        Debug.values(x, y)
    }
```

## Data Types

### Maybe: Allocation-free Optional Values

`Maybe` provides an allocation-free alternative to Scala's standard `Option` type. It is designed to be a drop-in replacement for `Option`, offering similar functionality while minimizing memory allocation.

```scala
import kyo._

// Create a 'Maybe' value
val a: Maybe[Int] = Maybe(42)

// 'Absent' represents the absence of a value
val b: Maybe[Int] = Absent

// 'Maybe.when' conditionally creates a 'Maybe' value
val c: Maybe[Int] = Maybe.when(true)(42)

// 'Maybe.fromOption' converts an 'Option' to a 'Maybe'
val d: Maybe[Int] = Maybe.fromOption(Some(42))

// 'isEmpty' checks if the 'Maybe' is empty
val e: Boolean = a.isEmpty

// 'isDefined' checks if the 'Maybe' has a value
val f: Boolean = a.isDefined

// 'get' retrieves the value, throwing if empty
val g: Int = a.get

// 'getOrElse' provides a default value if empty
val h: Int = b.getOrElse(0)

// 'fold' applies a function based on emptiness
val i: String = a.fold("Empty")(_.toString)

// 'map' transforms the value if present
val j: Maybe[String] = a.map(_.toString)

// 'flatMap' allows chaining 'Maybe' operations
val k: Maybe[Int] = a.flatMap(v => Maybe(v + 1))

// 'filter' conditionally keeps or discards the value
val l: Maybe[Int] = a.filter(_ > 0)

// 'contains' checks if the 'Maybe' contains a value
val m: Boolean = a.contains(42)

// 'exists' checks if a predicate holds for the value
val n: Boolean = a.exists(_ > 0)

// 'foreach' applies a side-effecting function if non-empty
a.foreach(println)

// 'collect' applies a partial function if defined
val o: Maybe[String] = a.collect { case 42 => "forty-two" }

// 'orElse' returns an alternative if empty
val p: Maybe[Int] = b.orElse(Maybe(0))

// 'zip' combines two 'Maybe' values into a tuple
val q: Maybe[(Int, String)] = a.zip(Maybe("hello"))

// 'toOption' converts a 'Maybe' to an 'Option'
val r: Option[Int] = a.toOption

// Using 'Maybe' in a for-comprehension
val s: Maybe[Int] = for {
  x <- Maybe(1)
  y <- Maybe(2)
  if x < y
} yield x + y

// Nesting 'Maybe' values
val nested: Maybe[Maybe[Int]] = Maybe(Maybe(42))
val flattened: Maybe[Int] = nested.flatten

// Pattern matching with 'Present' and 'Absent'
val result: String = 
    flattened match
        case Present(value) => s"Value: $value"
        case Absent        => "No value"
```

`Maybe`'s high performance is due to the fact that it is unboxed. Accordingly, we recommend using t-string interpolation when logging `Maybe`s:

```scala
import kyo.*

val maybe: Maybe[Maybe[Int]] = Maybe(Maybe(42))
val maybeNot: Maybe[Maybe[Int]] = Maybe(Maybe.Absent)

println(s"s-string nested maybes: $maybe, $maybeNot")
// Output: s-string nested maybes: 42, Absent

println(t"t-string nested maybes: $maybe, $maybeNot")
// Output: t-string nested maybes: Present(Present(42)), Present(Absent)
```

### Duration: Time Representation

`Duration` provides a convenient and efficient way to represent and manipulate time durations. It offers a wide range of operations and conversions, making it easy to work with time intervals in various units.

```scala
import kyo.*
import kyo.Duration.Units.*

// Create durations using convenient extension methods
val a: Duration = 5.seconds
val b: Duration = 100.millis
val c: Duration = 1.hour

// Perform arithmetic operations
val d: Duration = a + b
val e: Duration = c * 2

// Compare durations
val f: Boolean = a > b
val g: Boolean = c <= 60.minutes

// Convert to different time units
val h: Long = c.toMinutes // 60
val i: Long = a.toMillis  // 5000

// Create durations from various units
val j: Duration = Duration.fromNanos(1000000)
val k: Duration = Duration.fromUnits(2, Weeks)

// Convert to and from Java and Scala durations
import java.time.Duration as JavaDuration
import scala.concurrent.duration.Duration as ScalaDuration

val l: Duration = Duration.fromJava(JavaDuration.ofSeconds(30))
val m: Duration = Duration.fromScala(ScalaDuration(1, "day"))

val n: JavaDuration = c.toJava
val o: ScalaDuration = b.toScala

// Special durations
val p: Duration = Duration.Zero
val q: Duration = Duration.Infinity

// Render duration as a string
val r: String = a.show // "Duration(5000000000 ns)"
```

`Duration` is implemented as an `opaque type` alias for `Long`, representing nanoseconds internally. This design ensures type safety while maintaining high performance.

### Result: Typed Failure Handling

`Result` is a type that combines features of Scala's `Try` and `Either` types, designed to represent the result of a computation that may either succeed with a value or fail with an exception. It provides a flexible way to handle both successful outcomes and typed failures.

```scala
import kyo._
import scala.util.Try

// Create a 'Result' from a value
val a: Result[Nothing, Int] = Result.succeed(42)

// Create a 'Result' from an failure
val b: Result[Exception, Int] = Result.fail(new Exception("Oops"))

// Use 'apply' to create a 'Result' from a block of code
val c: Result[Nothing, Int] = Result(42 / 0)

// 'isSuccess' checks if the 'Result' is a success
val d: Boolean = a.isSuccess

// 'isFail' checks if the 'Result' is a failure
val e: Boolean = b.isFailure

// 'get' retrieves the value if successful, otherwise throws
val f: Int = a.getOrThrow

// 'getOrElse' provides a default value for failures
val g: Int = b.getOrElse(0)

// 'foldError' applies a function based on success or failure
val h: String = a.foldError(_.toString, e => "failure " + e)

// 'map' transforms the value if successful
val i: Result[Nothing, String] = a.map(_.toString)

// 'flatMap' allows chaining 'Result' operations
val j: Result[Nothing, Int] = a.flatMap(v => Result.succeed(v + 1))

// 'flatten' removes one level of nesting from a 'Result[Result[T]]'
val k: Result[Nothing, Result[Nothing, Int]] = Result.succeed(a)
val l: Result[Nothing, Int] = k.flatten

// 'filter' conditionally keeps or discards the value
val m: Result[NoSuchElementException, Int] = a.filter(_ > 0)

// 'mapFailure' allows mapping failures 
val n: Result[Int, Int] = b.mapFailure { _ => 0 }

// 'flatMapFailure' allows handling failures with a function returning a 'Result'
val o: Result[Nothing, Int] = b.flatMapFailure { case _ => Result.succeed(0) }

// 'toEither' converts a 'Result' to an 'Either'
val p: Either[Throwable, Int] = a.toEither

// 'toTry' converts a 'Result' to a 'Try'
val q: Try[Int] = a.toTry
```

Under the hood, `Result` is defined as an opaque type that is a supertype of `Success[T]` and `Failure[T]`. Success[T] represents a successful result and is encoded as either the value itself (`T`) or a special SuccessFailure[`T`] case class. The `SuccessFailure[T]` case class is used to handle the rare case where a `Failure[T]` needs to be wrapped in a `Success[T]`. On the other hand, a failed `Result` is always represented by a `Failure[T]` case class, which contains the exception that caused the failure. This means that creating a `Failure[T]` does incur an allocation cost. Additionally, some methods on `Result`, such as `fold`, `map`, and `flatMap`, may allocate in certain cases due to the need to catch and handle exceptions.

Since `Result.Success` is unboxed, we recommend using t-string interpolation when logging `Result`s:

```scala
import kyo.*

val success: Result[String, Result[String, Int]] = Result.succeed(Result.succeed(42))
val failure: Result[String, Result[String, Int]] = Result.succeed(Result.fail("failure!"))

println(s"s-string nested results: $success, $failure")
// Output: s-string nested results: 42, Fail(failure!)

println(t"t-string nested results: $success, $failure")
// Output: t-string nested results: Success(Success(42)), Success(Fail(failure!))
```

### TypeMap: Type-Safe Heterogeneous Maps

`TypeMap` provides a type-safe heterogeneous map implementation, allowing you to store and retrieve values of different types using their types as keys. This is particularly useful for managing multiple types of data in a single structure with type safety.

```scala
import kyo.*

// Create an empty TypeMap
val empty: TypeMap[Any] = TypeMap.empty

// Constructors for up to 4 elements
val map1: TypeMap[String] = TypeMap("Hello")
val map2: TypeMap[String & Int] = TypeMap("Hello", 42)
val map3: TypeMap[String & Int & Boolean] = TypeMap("Hello", 42, true)
val map4: TypeMap[String & Int & Boolean & Double] = TypeMap("Hello", 42, true, 3.14)

// Add a value to an existing TypeMap
val mapWithNewValue: TypeMap[String & Int] = map1.add(42)

// Retrieve a value from the TypeMap
val str: String = map2.get[String]
val num: Int = map2.get[Int]

// Combine two TypeMaps
val combined: TypeMap[String & Int & Boolean] = map2.union(TypeMap(true))

// Filter the TypeMap to only include subtypes of a given type
val pruned: TypeMap[String] = map2.prune[String]

// Check if the TypeMap is empty and get its size
val isEmpty: Boolean = map2.isEmpty
val size: Int = map2.size

// Get a string representation of the TypeMap
val representation: String = map2.show
```

The type parameter `A` in `TypeMap[A]` represents the intersection type of all stored values, ensuring type safety when retrieving values.

### Ansi: Text Color and Formatting

The `Ansi` object provides utilities for adding ANSI color and formatting to strings, as well as a code highlighting feature. This can be useful for creating colorful console output or formatting text for better readability.

```scala
import kyo.*

// The 'String' extension methods require a separate import
import kyo.Ansi.*

// Add colors to strings
val redText: String = "Error".red
val blueText: String = "Info".blue

// Add text formatting
val boldText: String = "Important".bold
val underlinedText: String = "Underlined".underline

// Combine colors and formatting
val importantError: String = "Critical Error".red.bold

// Strip ANSI codes from a string
val plainText: String = "\u001b[31mColored\u001b[0m".stripAnsi

// Highlight code snippets
val code = """
def hello(name: String): Unit =
println(s"Hello, $name!")
"""
lazy val highlightedCode: String = Ansi.highlight(code)

// Highlight code with custom header and trailer
lazy val customHighlight: String = 
    Ansi.highlight(
        header = "// File: example.scala",
        code = code,
        trailer = "// End of file",
        startLine = 1
    )
```

The `Ansi` object provides the following color extensions for strings:
- `black`, `red`, `green`, `yellow`, `blue`, `magenta`, `cyan`, `white`, `grey`

And the following formatting extensions:
- `bold`, `dim`, `italic`, `underline`

The code highlighting feature supports basic syntax highlighting for Scala keywords, string literals, and comments.

## Integrations

### Cache: Memoized Functions via Caffeine

Kyo provides caching through memoization. A single `Cache` instance can be reused by multiple memoized functions. This allows for flexible scoping of caches, enabling users to use the same cache for various operations.

```scala
import kyo.*

val a: Int < Async =
    for

        // The initialization takes a
        // builder function that mirrors
        // Caffeine's builder
        cache <- Cache.init(_.maxSize(100))

        // Create a memoized function
        fun = cache.memo { (v: String) =>
            // Note how the implementation
            // can use other effects
            Sync(v.toInt)
        }

        // Use the function
        v <- fun("10")
    yield v
```

Although multiple memoized functions can reuse the same `Cache`, each function operates as an isolated cache and doesn't share any values with others. Internally, cache entries include the instance of the function as part of the key to ensure this separation. Only the cache space is shared, allowing for efficient use of resources without compromising the independence of each function's cache.

### Requests: HTTP Client via Sttp

`Requests` provides a simplified API for [Sttp 3](https://github.com/softwaremill/sttp) implemented on top of Kyo's concurrent package.

To perform a request, use the `apply` method. It takes a builder function based on Sttp's request building API.

```scala
import kyo.*
import kyo.Requests.Backend
import sttp.client3.*

// Perform a request using a builder function
val a: String < (Async & Abort[FailedRequest]) =
    Requests(_.get(uri"https://httpbin.org/get"))

// Alternatively, requests can be
// defined separately
val b: String < (Async & Abort[FailedRequest]) =
    Requests.request(Requests.basicRequest.get(uri"https://httpbin.org/get"))

// It's possible to use the default implementation or provide
// a custom `Backend` via `let`

// An example request
val c: String < (Async & Abort[FailedRequest]) =
    Requests(_.get(uri"https://httpbin.org/get"))

// Implementing a custom mock backend
val backend: Backend =
    new Backend:
        def send[T](r: Request[T, Any]) =
            Response.ok(Right("mocked")).asInstanceOf[Response[T]]

// Use the custom backend
val d: String < (Async & Abort[FailedRequest]) =
    Requests.let(backend)(a)
```

Please refer to Sttp's documentation for details on how to build requests. Streaming is currently unsupported.

Users are free to use any JSON libraries supported by Sttp; however, [zio-json](https://github.com/zio/zio-json) is recommended, as it is used in Kyo's tests and modules requiring HTTP communication, such as `AIs`.

### Routes: HTTP Server via Tapir

`Routes` integrates with the Tapir library to help set up HTTP servers. The method `Routes.add` is used for adding routes. This method requires the definition of a route, which can be a Tapir Endpoint instance or a builder function. Additionally, the method requires the implementation of the endpoint, which is provided as the second parameter group. To start the server, the `Routes` effect is handled, which initializes the HTTP server with the specified routes.

```scala
import kyo.*
import sttp.tapir.*
import sttp.tapir.server.netty.*

// A simple health route using an endpoint builder
val a: Unit < Routes =
    Routes.add(
        _.get.in("health")
            .out(stringBody)
    ) { _ =>
        "ok"
    }

// The endpoint can also be defined separately
val health2 = endpoint.get.in("health2").out(stringBody)

val b: Unit < Routes =
    Routes.add(health2)(_ => "ok")

// Starting the server by handling the effect
val c: NettyKyoServerBinding < Async =
    Routes.run(a.andThen(b))

// Alternatively, a customized server configuration can be used
val d: NettyKyoServerBinding < Async =
    Routes.run(NettyKyoServer().port(9999))(a.andThen(b))
```

The parameters for Tapir's endpoint type are aligned with Kyo effects as follows:

`Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, CAPABILITIES]`

This translates to the endpoint function format:

`INPUT => OUTPUT < (Env[SECURITY_INPUT] & Abort[ERROR_OUTPUT])`

Currently, the `CAPABILITIES` parameter is not supported in Kyo since streaming functionality is not available. An example of using these parameters is shown below:

```scala
import kyo.*
import sttp.model.*
import sttp.tapir.*

// An endpoint with an 'Int' path input and 'StatusCode' error output
val a: Unit < Routes =
    Routes.add(
        _.get.in("test" / path[Int]("id"))
            .errorOut(statusCode)
            .out(stringBody)
    ) { (id: Int) =>
        if id == 42 then "ok"
        else Abort.fail(StatusCode.NotFound)
        // returns a 'String < Abort[StatusCode]'
    }
```

For further examples, refer to TechEmpower's benchmark [subproject](https://github.com/TechEmpower/FrameworkBenchmarks/tree/master/frameworks/Scala/kyo-tapir) for a simple runnable demonstration, and  Kyo's [example ledger service](https://github.com/getkyo/kyo/tree/main/kyo-examples/jvm/src/main/scala/examples/ledger) for practical applications of these concepts.

### ZIOs: Integration with ZIO

The `ZIOs` effect provides seamless integration between Kyo and the ZIO library. The effect is designed to enable gradual adoption of Kyo within a ZIO codebase. The integration properly suspends side effects and propagates fiber cancellations/interrupts between both libraries.

```scala
import kyo.*
import zio.*

// Use the 'get' method to extract a 'ZIO' effect
val a: Int < (Abort[Nothing] & Async) =
    ZIOs.get(ZIO.succeed(42))

// 'get' also supports error handling with 'Abort'
val b: Int < (Abort[String] & Async) =
    ZIOs.get(ZIO.fail("error"))

// Handle the 'ZIO' effect to obtain a 'ZIO' effect
val c: Task[Int] =
    ZIOs.run(a)
```

Kyo and ZIOs effects can be seamlessly mixed and matched within computations, allowing developers to leverage the power of both libraries. Here are a few examples showcasing this integration:

```scala
import kyo.*
import zio.*

// Note how ZIO includes the
// Sync and Async effects
val a: Int < (Abort[Nothing] & Async) =
    for
        v1 <- ZIOs.get(ZIO.succeed(21))
        v2 <- Sync(21)
        v3 <- Async.run(-42).map(_.get)
    yield v1 + v2 + v3

// Using fibers from both libraries
val b: Int < (Abort[Nothing] & Async) =
    for
        f1 <- ZIOs.get(ZIO.succeed(21).fork)
        f2 <- Async.run(Sync(21))
        v1 <- ZIOs.get(f1.join)
        v2 <- f2.get
    yield v1 + v2

// Transforming ZIO effects within Kyo computations
val c: Int < (Abort[Nothing] & Async) =
    ZIOs.get(ZIO.succeed(21)).map(_ * 2)

// Transforming Kyo effects within ZIO effects
val d: Task[Int] =
    ZIOs.run(Sync(21).map(_ * 2))
```

> Note: Support for ZIO environments (`R` in `ZIO[R, E, A]`) is currently in development. Once implemented, it will be possible to use ZIO effects with environments directly within Kyo computations.

### Cats: Integration with Cats Effect

The `Cats` effect provides seamless integration between Kyo and the Cats Effect library. This integration is designed to enable gradual adoption of Kyo within a Cats Effect codebase. The integration properly suspends side effects and propagates fiber cancellations/interrupts between both libraries.

```scala
import kyo.*
import cats.effect.IO as CatsIO

// Use the 'get' method to extract a 'Sync' effect from Cats Effect:
val a: Int < (Abort[Throwable] & Async) =
    Cats.get(CatsIO.pure(42))

// Handle the 'Cats' effect to obtain a 'CatsIO' effect:
val b: CatsIO[Int] =
    Cats.run(a)
```

Kyo and Cats effects can be seamlessly mixed and matched within computations, allowing developers to leverage the power of both libraries. Here are a few examples showcasing this integration:

```scala
import kyo.*
import cats.effect.IO as CatsIO
import cats.effect.kernel.Outcome.Succeeded

// Note how Cats includes the Sync, Async, and Abort[Nothing] effects:
val a: Int < (Abort[Nothing] & Async) =
    for
        v1 <- Cats.get(CatsIO.pure(21))
        v2 <- Sync(21)
        v3 <- Async.run(-42).map(_.get)
    yield v1 + v2 + v3

// Using fibers from both libraries:
val b: Int < (Abort[Nothing] & Async) =
    for
        f1 <- Cats.get(CatsIO.pure(21).start)
        f2 <- Async.run(Sync(21))
        v1 <- Cats.get(f1.joinWith(CatsIO(99)))
        v2 <- f2.get
    yield v1 + v2

// Transforming Cats Effect Sync within Kyo computations:
val c: Int < (Abort[Nothing] & Async) =
    Cats.get(CatsIO.pure(21)).map(_ * 2)

// Transforming Kyo effects within Cats Effect Sync:
val d: CatsIO[Int] =
    Cats.run(Sync(21).map(_ * 2))
```

### Resolvers: GraphQL Server via Caliban

`Resolvers` integrates with the [Caliban](https://github.com/ghostdogpr/caliban) library to help setup GraphQL servers.

The first integration is that you can use Kyo effects inside your Caliban schemas by importing `kyo.given`.
- If your Kyo effects is `(Abort[Throwable] & ZIO)` or a subtype of it (`ZIO` includes `Async & Sync`), a Caliban `Schema` can be derived automatically.
- If your Kyo effect is something else, a Caliban schema can be derived if it has a `Runner` for that effect as part of ZIO environment.

```scala
import caliban.schema.*
import kyo.*
import kyo.given

// this works by just importing kyo.*
case class Query(k: Int < Abort[Throwable]) derives Schema.SemiAuto

// for other effects, you need to extend `SchemaDerivation[Runner[YourCustomEffects]]`
type CustomEffects = Var[Int] & Env[String]
object schema extends SchemaDerivation[Runner[CustomEffects]]

case class Query2(k: Int < CustomEffects) derives schema.SemiAuto
```

Then, the `Resolvers` effect allows easily turning these schemas into a GraphQL server.
The method `Resolvers.get` is used for importing a `GraphQL` object from Caliban into Kyo.
You can then run this effect using `Resolvers.run` to get an HTTP server. This effect requires `ZIO` because Caliban uses ZIO internally to run.

```scala
import caliban.*
import caliban.schema.*
import kyo.*
import kyo.given
import sttp.tapir.server.netty.*
import zio.Task

case class Query(k: Int < Abort[Throwable]) derives Schema.SemiAuto
val api = graphQL(RootResolver(Query(42)))

val a: NettyKyoServerBinding < (Async & Abort[CalibanError]) =
    Resolvers.run { Resolvers.get(api) }

// similarly to the tapir integration, you can also pass a `NettyKyoServer` explicitly
val b: NettyKyoServerBinding < (Async & Abort[CalibanError]) =
    Resolvers.run(NettyKyoServer().port(9999)) { Resolvers.get(api) }

// you can turn this into a ZIO as seen in the ZIO integration
val c: Task[NettyKyoServerBinding] = ZIOs.run(b)
```

When using arbitrary Kyo effects, you need to provide the `Runner` for that effect when calling the `run` function.
```scala
import caliban.*
import caliban.schema.*
import kyo.*
import kyo.given
import zio.Task

type CustomEffects = Var[Int] & Env[String]
object schema extends SchemaDerivation[Runner[CustomEffects]]
case class Query(k: Int < CustomEffects) derives schema.SemiAuto

val api = graphQL(RootResolver(Query(42)))

// runner for our CustomEffects
val runner = new Runner[CustomEffects]:
    def apply[T](v: T < CustomEffects): Task[T] = ZIOs.run(Env.run("kyo")(Var.run(0)(v)))

val d = Resolvers.run(runner) { Resolvers.get(api) }
```

### AIs: LLM Abstractions via OpenAI

Coming soon..

## ZIO-like Combinators

For ZIO users, Kyo's core API can be frustrating for three reasons:

1. It is minimal by design.

While its uncluttered namespaces make it more approachable for beginners, users addicted to ZIO's powerful and intuitive combinators may find it unwieldy and possibly not worth the effort.

2. Effects are handled by functions that take effects as arguments, rather than by methods on effects.

ZIO users are used to having a large menu of combinators on `ZIO` values that can be chained together to manipulate effects fluently. `kyo-core`, by contrast, requires nesting effects within method calls, inverting the order in which users handle effects and requiring them either to create deeply nested expressions or to break expressions up into many intermediate expressions.

3. Factory methods are distributed among different objects

Being more modular than ZIO, Kyo separates effect constructors in the companion objects to their corresponding types. This is not a problem given the minimal API that Kyo offers, but ZIO users will miss typing `ZIO.` and seeing a rich menu of factory methods pop up on their IDE.

`kyo-combinators` alleviates these frustrations by providing:
1. Factory methods on the `Kyo` object, styled after those found on `ZIO`, for many of the core Kyo effect types.
2. Extension methods on Kyo effects modeled similarly to ZIO's methods.

Generally speaking, the names of `kyo-combinators` methods are the same as the corresponding methods in ZIO. When this is not possible or doesn't make sense, `kyo-combinators` tries to keep close to ZIO conventions.

### Simple example

```scala 3
import kyo.*
import scala.concurrent.duration.*
import java.io.IOException

trait HelloService:
    def sayHelloTo(saluee: String): Unit < (Sync & Abort[Throwable])

object HelloService:
    val live = Layer(Live)

    object Live extends HelloService:
        override def sayHelloTo(saluee: String): Unit < (Sync & Abort[Throwable]) =
            Kyo.suspendAttempt { // Introduces Sync & Abort[Throwable] effect
                println(s"Hello $saluee!")
            }
    end Live
end HelloService

val keepTicking: Nothing < (Async & Emit[String]) =
    (Kyo.emit(".") *> Kyo.sleep(1.second)).forever

val effect: Unit < (Async & Resource & Abort[Throwable] & Env[HelloService]) =
    for
        nameService <- Kyo.service[HelloService]      // Introduces Env[NameService]
        _           <- keepTicking                    // Introduces Async and Emit[String]
            .foreachEmit(Console.print)               // Handles Emit[String] and introduces Abort[IOException]
            .forkScoped                               // Introduces Resource
        saluee      <- Console.readln
        _           <- Kyo.sleep(2.seconds)
        _           <- nameService.sayHelloTo(saluee) // Lifts Abort[IOException] to Abort[Throwable]
    yield ()
    end for
end effect

// There are no combinators for handling Sync or blocking Async, since this should
// be done at the edge of the program
Sync.Unsafe.run {                        // Handles Sync
    Async.runAndBlock(Duration.Inf) {  // Handles Async
        Kyo.scoped {                   // Handles Resource
            Memo.run:                  // Handles Memo (introduced by .provide, below)
                effect
                    .catching((thr: Throwable) =>             // Handles Abort[Throwable]
                        Kyo.debug(s"Failed printing to console: ${throwable}")
                    )
                    .provide(HelloService.live)                 // Works like ZIO[R,E,A]#provide, but introduces Memo effect
        }
    }
}
```

### Error handling

Whereas ZIO has a single channel for describing errors, Kyo has different effect types that can describe failure in the basic sense of "short-circuiting": `Abort` and `Choice` (an empty `Seq` being equivalent to a short-circuit). `Abort[Absent]` can also be used like `Choice` to model short-circuiting an empty result.

For each of these, to handle the effect, lifting the result type to `Result`, `Seq`, and `Maybe`, use `.result`, `.handleChoice`, and `.maybe` respectively. Alternatively, you can convert between these different error types using methods usually in the form of `def effect1ToEffect2`, where `effect1` and `effect2` can be "abort" (`Abort[?]`), "absent" (`Abort[Absent]`), "empty" (`Choice`, when reduced to an empty sequence), and "throwable" (`Abort[Throwable]`).

Some examples:

```scala 
val abortEffect: Int < Abort[String] = 1

// Converts failures to empty failure
val maybeEffect: Int < Abort[Absent] = abortEffect.abortToAbsent

// Converts an aborted Absent to a dropped (i.e., empty) "choice"
val choiceEffect: Int < Choice = maybeEffect.absentToChoiceDrop

// Fails with exception if choice is dropped
val newAbortEffect: Int < (Choice & Abort[Throwable]) = choiceEffect.choiceDropToThrowable
```

To swallow errors à la ZIO's `orDie` and `resurrect` methods, you can use `orPanic`/`orThrow` and `unpanic` respectively:

```scala
import kyo.*
import java.io.IOException

val abortEffect: Int < Abort[String | Throwable] = 1

// Will panic with a `PanicException(err)` rather than fail
val panicEffect: Int < Abort[Nothing] = abortEffect.orPanic

// Will throw `PanicException(err)` when evaluated
val unsafeEffect: Int < Any = abortEffect.orThrow

// Catch any suspended throws
val safeEffect: Int < Abort[Throwable] = unsafeEffect.unpanic

// Use orPanic after forAbort[E] to swallow only errors of type E
val unsafeForThrowables: Int < Abort[String] = abortEffect.forAbort[Throwable].orPanic
```

In general `orPanic` should be preferred over `orThrow`, especially when used in conjunction with `Sync` or `Async`, both of which include `Abort[Nothing]`. This will avoid unnecessary catching/re-throwing.

Other error-handling methods are as follows:

```scala
import kyo.*

trait A
trait B
trait C

val effect: Int < Abort[A | B | C] = 1

val handled: Result[A | B | C, Int] < Any = effect.result
val handledWithoutPanic: Result.Partial[A | B | C, Int] < Abort[Nothing] = effect.resultPartial
val unsafeHandled: Result.Partial[A | B | C, Int] < Any = effect.resultPartialOrThrow
val folded: String < Any = effect.foldAbort(_.toString, _.toString, _.toString)
val foldedWithoutPanic: String < Abort[Nothing] = effect.foldAbort(_.toString, _.toString)
val unsafeFolded: String < Any = effect.foldAbortOrThrow(_.toString, _.toString)
val mappedError: Int < Abort[String] = effect.mapAbort(_.toString)
val recovered: Int < Abort[Nothing] = effect.recover(_.toString.size)
val partiallyRecovered: Int < Abort[A | B | C] = effect.recoverSome { case err if err.toString.size > 5 => 0 }
val swapped: (A | B | C) < Abort[Int] = effect.swapAbort
val retried: Int < Abort[A | B | C] = effect.retry(5)
val retriedUntilSucceed: Int < Any = effect.retryForever

// Select error types within the Abort union for handling
val handledA: Result[A, Int] < Abort[B | C] = effect.forAbort[A].result
val handledWithoutPanicA: Result.Partial[A, Int] < Abort[B | C] = effect.forAbort[A].resultPartial
val foldedA: String < Abort[B | C] = effect.forAbort[A].fold(_.toString, _.toString, _.toString)
val foldedWithoutPanicA: String < Abort[B | C] = effect.forAbort[A].fold(_.toString, _.toString)
val recoveredA: Int < Abort[B | C] = effect.forAbort[A].recover(_.toString.size)
val partiallyRecoveredA: Int < Abort[A | B | C] = effect.forAbort[A].recoverSome { case err if err.toString.size > 5 => 0 }
val aSwapped: A < Abort[Int | B | C] = effect.forAbort[A].swap
val aToAbsent: Int < Abort[Absent | B | C] = effect.forAbort[A].toAbsent
val aToEmpty: Int < (Choice & Abort[B | C]) = effect.forAbort[A].toChoiceDrop
val aToThrowable: Int < Abort[Throwable | B | C] = effect.forAbort[A].toThrowable
val retriedA: Int < (Abort[A | B | C]) = effect.forAbort[A].retry(5)
val retriedAUntilSucceed: Int < (Abort[B | C]) = effect.forAbort[A].retryForever
```


## Acknowledgements

Kyo's development was originally inspired by the paper ["Do Be Do Be Do"](https://arxiv.org/pdf/1611.09259.pdf) and its implementation in the [Unison](https://www.unison-lang.org/learn/language-reference/abilities-and-ability-handlers/) programming language. Kyo's design evolved from using interface-based effects to suspending concrete values associated with specific effects, making it more efficient when executed on the JVM.

Additionally, Kyo draws inspiration from [ZIO](https://zio.dev/) in various aspects. The core mechanism for algebraic effects can be seen as a generalization of ZIO's effect rotation, and many of Kyo's effects are directly influenced by ZIO's mature set of primitives. For instance, `Env` and `Abort` correspond to ZIO's effect channels, `Resource` function similarly to `Scope`, and `Hub` was introduced based on ZIO.

Kyo's asynchronous primitives take several aspects from [Twitter's util](https://github.com/twitter/util) and [Finagle](https://github.com/twitter/finagle), including features like async root compression, to provide stack safety, and support for cancellations (interruptions in Kyo).

Lastly, the name "Kyo" is derived from the last character of Nam-myoho-renge-kyo, the mantra practiced in [SGI Buddhism](https://www.sokaglobal.org/). It literally translates to "Sutra," referring to a compiled teaching of Shakyamuni Buddha, and is also interpreted as the "threads" that weave the fundamental fabric of life's reality.

License
-------

See the [LICENSE](https://github.com/getkyo/kyo/blob/master/LICENSE.txt) file for details.
