### Please visit https://getkyo.io for an indexed version of this documentation.
##

<img src="https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png" width="200" alt="Kyo">

## Introduction

[![Build Status](https://github.com/getkyo/kyo/workflows/build/badge.svg)](https://github.com/getkyo/kyo/actions)
[![Discord](https://img.shields.io/discord/1087005439859904574)](https://discord.gg/KxxkBbW8bq)
[![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)](https://search.maven.org/search?q=g:io.getkyo)

Kyo is a toolkit for Scala development, spanning from browser-based apps in ScalaJS to high-performance backends on the JVM. It introduces a novel approach based on algebraic effects to deliver straightforward APIs in the pure Functional Programming paradigm. Unlike similar solutions, Kyo achieves this without inundating developers with esoteric concepts from Category Theory or using cryptic symbolic operators, resulting in a development experience that is both intuitive and robust.

Drawing inspiration from [ZIO](https://zio.dev/)'s [effect rotation](https://degoes.net/articles/rotating-effects), Kyo takes a more generalized approach. While ZIO restricts effects to two channels, dependency injection and short-circuiting, Kyo allows for an arbitrary number of effectful channels. This enhancement gives developers greater flexibility in effect management, while also simplifying Kyo's internal codebase through more principled design patterns.

### Getting Started

Kyo is available on Maven Central in multiple modules:

| Module         | Scala 3 | Scala JS | Description                      |
| -------------- | ------- | -------- | -------------------------------- |
| kyo-core       | ✅      | ✅       | Core and concurrent effects      |
| kyo-direct     | ✅      | ✅       | Direct syntax support            |
| kyo-combinators| ✅      | ✅       | ZIO-like effect composition      |
| kyo-sttp       | ✅      | ✅       | Sttp HTTP Client                 |
| kyo-tapir      | ✅      |          | Tapir HTTP Server                |
| kyo-caliban    | ✅      |          | Caliban GraphQL Server           |
| kyo-cache      | ✅      |          | Caffeine caching                 |
| kyo-stats-otel | ✅      |          | Stats exporter for OpenTelemetry |

For Scala 3:

```scala 
libraryDependencies += "io.getkyo" %% "kyo-core"        % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-direct"      % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-combinators" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-cache"       % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-stats-otel"  % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-sttp"        % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-tapir"       % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-caliban"     % "<version>"
```

For ScalaJS (applicable only to `kyo-core`, `kyo-direct`, `kyo-combinators`, and `kyo-sttp`):

```scala 
libraryDependencies += "io.getkyo" %%% "kyo-core"        % "<version>"
libraryDependencies += "io.getkyo" %%% "kyo-direct"      % "<version>"
libraryDependencies += "io.getkyo" %%  "kyo-combinators" % "<version>"
libraryDependencies += "io.getkyo" %%% "kyo-sttp"        % "<version>"
```

Replace `<version>` with the latest version: ![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3).


### The "Pending" type: `<`

In Kyo, computations are expressed via the infix type `<`, known as "Pending". It takes two type parameters:

1. The type of the expected output.
2. The pending effects that need to be handled, represented as an unordered type-level set via a type intersection.

```scala 
import kyo.*

// 'Int' pending 'Abort[Maybe.Empty]'
Int < Abort[Maybe.Empty]

// 'String' pending 'Abort[Maybe.Empty]' and 'IO'
String < (Abort[Maybe.Empty] & IO)
```

> Note: The naming convention for effect types is the plural form of the functionalities they manage.

Any type `T` is automatically considered to be of type `T < Any`, where `Any` denotes an absence of pending effects. In simpler terms, this means that every value in Kyo is automatically a computation, but one without any effects that you need to handle. 

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
    a: Int < IO,
    b: Int < Abort[Exception]
): Int < (IO & Abort[Exception]) =
    a.flatMap(v => b.map(_ + v))

// But using only `map` is recommended
// since it functions like `flatMap` due
// to effect widening.
def example2(
    a: Int < IO,
    b: Int < Abort[Exception]
): Int < (IO & Abort[Exception]) =
    a.map(v => b.map(_ + v))
```

The `map` method automatically updates the set of pending effects. When you apply `map` to computations that have different pending effects, Kyo reconciles these into a new computation type that combines all the unique pending effects from both operands.

When a computation produces a `Unit` value, Kyo also offers an `andThen` method for more fluent code:

```scala
import kyo.*

// An example computation that
// produces 'Unit'.
val a: Unit < IO =
    IO(println("hello"))

// Use 'andThen'.
val b: String < IO =
    a.andThen("test")
```

### Effect widening

Kyo's set of pending effects is a contravariant type parameter. This encoding permits computations to be widened to encompass a larger set of effects.

```scala
import kyo.*

// An 'Int' with an empty effect set (`Any`)
val a: Int < Any =
    1

// Widening the effect set from empty (`Any`)
// to include `IO`
val b: Int < IO =
    a

// Further widening the effect set to include
// both `IO` and `Abort[Exception]`
val c: Int < (IO & Abort[Exception]) =
    b

// Directly widening a pure value to have
// `IO` and `Abort[Exception]`
val d: Int < (IO & Abort[Exception]) =
    42
```

This characteristic enables a fluent API for effectful code. Methods can accept parameters with a specific set of pending effects while also permitting those with fewer or no effects.

```scala
import kyo.*

// The function expects a parameter with both
// 'IO' and 'Abort' effects pending
def example1(v: Int < (IO & Abort[Exception])) =
    v.map(_ + 1)

// A value with only the 'Abort' effect can be
// automatically widened to include 'IO'
def example2(v: Int < Abort[Exception]) =
    example1(v)

// A pure value can also be automatically widened
def example3 = example1(42)
```

Here, `example1` is designed to accept an `Int < (Options & Abort[Exception])`. However, thanks to the contravariant encoding of the type-level set of effects, `example2` and `example3` demonstrate that you can also pass in computations with a smaller set of effects—or even a pure value—and they will be automatically widened to fit the expected type.

### Using effects

Effects follow a naming convention for common operations:

- `init*`: Initializes an instance of the container type handled by the effect. For instance, `Async.run` returns a new `Fiber`.
- `get*`: Allows the "extraction" of the value of the container type. `Async.get` returns a `T < Async` for a `Fiber[T]`.
- `run*`: Handles the effect.

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

Kyo provides direct syntax for a more intuitive and concise way to express computations, especially when dealing with multiple effects. This syntax leverages two primary constructs: `defer` and `await`.

Essentially, `await` is a syntactic sugar for the `map` function, allowing developers to directly access values from computations without the need for repetitive `map` chaining. This makes the code more linear and intuitive.

```scala
import kyo.*

// Use the direct syntax
val a: String < (Abort[Exception] & IO) =
    defer {
        val b: String =
            await(IO("hello"))
        val c: String =
            await(Abort.get(Right("world")))
        b + " " + c
    }

// Equivalent desugared
val b: String < (Abort[Exception] & IO) =
    IO("hello").map { b =>
        Abort.get(Right("world")).map { c =>
            b + " " + c
        }
    }
```

The `defer` macro translates the `defer` and `await` constructs by virtualizing control flow. It modifies value definitions, conditional branches, loops, and pattern matching to express compurations in terms of `map`. 

For added safety, the direct syntax enforces effectful hygiene. Within a `defer` block, values of the `<` type must be enclosed by an `await` block. This approach ensures all effectful computations are explicitly processed, reducing the potential for missed effects or operation misalignment.

```scala 
import kyo.*

// This code fails to compile
val a: Int < IO =
    defer {
        // Incorrect usage of a '<' value
        // without 'await'
        IO(println(42))
        42
    }
```

> Note: In the absence of effectful hygiene, the side effect `IO(println(42))` would be overlooked and never executed. With the hygiene in place, such code results in a compilation error.

The syntac sugar supports a variety of constructs to handle effectful computations. These include pure expressions, value definitions, control flow statements like `if`-`else`, logical operations (`&&` and `||`), `while`, and pattern matching.

```scala
import kyo.*

defer {
    // Pure expression
    val a: Int = 5

    // Effectful value
    val b: Int = await(IO(10))

    // Control flow
    val c: String =
        if await(IO(true)) then "True branch" else "False branch"

    // Logical operations
    val d: Boolean =
        await(IO(true)) && await(IO(false))

    val e: Boolean =
        await(IO(true)) || await(IO(true))

    // Loop (for demonstration; this loop
    // won't execute its body)
    while await(IO(false)) do "Looping"

    // Pattern matching
    val matchResult: String =
        await(IO(1)) match
            case 1 => "One"
            case _ => "Other"
}
```

The `defer` method in Kyo mirrors Scala's `for`-comprehensions in providing a constrained yet expressive syntax. In `defer`, features like nested `defer` blocks, `var` declarations, `return` statements, `lazy val`, `lambda` and `def` with `await`, `try`/`catch` blocks, methods and constructors accepting by-name parameters, `throw` expressions, as well as `class`, `for`-comprehension, `trait`, and `object`s are disallowed. This design allows clear virtualization of control flow, eliminating potential ambiguities or unexpected results.

The `kyo-direct` module is constructed as a wrapper around [dotty-cps-async](https://github.com/rssh/dotty-cps-async).

> Note: `defer` is currently the only user-facing macro in Kyo. All other features use regular language constructs.

### Defining an App

`KyoApp` offers a structured approach similar to Scala's `App` for defining application entry points. However, it comes with added capabilities, handling a suite of default effects. As a result, the `run` method within `KyoApp` can accommodate various effects, such as IO, Async, Resource, Clock, Console, Random, Timer, and Aspect.

```scala
import kyo.*

object MyApp extends KyoApp:
    // Use 'run' blocks to execute Kyo computations.
    // The execution of the run block is lazy to avoid
    // field initialization issues.
    run {
        for
            _            <- Console.println(s"Main args: $args")
            currentTime  <- Clock.now
            _            <- Console.println(s"Current time is: $currentTime")
            randomNumber <- Random.nextInt(100)
            _            <- Console.println(s"Generated random number: $randomNumber")
        yield
        // The produced value can be of any type and is
        // automatically printed to the console.
        "example"
    }
end MyApp
```

While the companion object of `KyoApp` provides utility methods to run isolated effectful computations, it's crucial to approach these with caution. Direct handling of effects like `IO` through these methods can compromise referential transparency, an essential property for functional programming.

```scala
import kyo.*

// An example computation
val a: Int < IO =
    IO(Math.cos(42).toInt)

// Avoid! Run the application with a specific timeout
val b: Int =
    KyoApp.run(2.minutes)(a)

// Avoid! Run the application without specifying a timeout
val c: Int =
    KyoApp.run(a)
```

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

> Note that the `Abort` effect has a type parameter and its methods can only be accessed if the type parameter is provided.

### IO: Side Effects

Kyo is unlike traditional effect systems since its base type `<` does not assume that the computation can perform side effects. The `IO` effect is introduced whenever a side effect needs to be performed.

```scala
import kyo.*

def aSideEffect = 1 // placeholder

// 'apply' is used to suspend side effects
val a: Int < IO =
    IO(aSideEffect)
```

Users shouldn't typically handle the `IO` effect directly since it triggers the execution of side effects, which breaks referential transparency. Prefer `KyoApp` instead.

In some specific cases where Kyo isn't used as the main effect system of an application, it might make sense for the user to handle the `IO` effect directly. The `run` method can only be used if `IO` is the only pending effect.

```scala
import kyo.*

val a: Int < IO =
    IO(42)

// ** Avoid 'IO.run', use 'KyoApp' instead. **
val b: Int =
    IO.run(a).eval
// ** Avoid 'IO.run', use 'KyoApp' instead. **
```

The `runLazy` method accepts computations with other effects but it doesn't guarantee that all side effects are performed before the method returns. If other effects still have to be handled, the side effects can be executed later once the other effects are handled. This a low-level API that must be used with caution.

```scala
import kyo.*

// Computation with 'Env' and then 'IO' suspensions
val a: Int < (Env[Int] & IO) =
    Env.get[Int].map { v =>
        IO {
            println(v)
            v
        }
    }

// ** Avoid 'IO.runLazy', use 'KyoApp' instead. **
// Handle the 'IO' effect lazily
val b: Int < Env[Int] =
    IO.runLazy(a)
// ** Avoid 'IO.runLazy', use 'KyoApp' instead. **

// Since the computation is suspended with the
// 'Env' effect first, the lazy 'IO' execution
// will be triggered once 'Env' is handled
val c: Int =
    Env.run(42)(b).eval
```

> IMPORTANT: Avoid handling the `IO` effect directly since it breaks referential transparency. Use `KyoApp` instead.

### Env: Dependency Injection

`Env` is similar to ZIO's environment feature but offers more granular control. Unlike ZIO, which has built-in layering for dependencies, `Env` allows you to inject individual services directly. However, it lacks ZIO's structured dependency management; you manage and initialize your services yourself.

```scala
import kyo.*

// Given an interface
trait Database:
    def count: Int < IO

// The 'Env' effect can be used to summon an instance.
// Note how the computation produces a 'Database' but at the
// same time requires a 'Database' from its environment
val a: Database < Env[Database] =
    Env.get[Database]

// Use the 'Database' to obtain the count
val b: Int < (Env[Database] & IO) =
    a.map(_.count)

// A 'Database' mock implementation
val db = new Database:
    def count = 1

// Handle the 'Env' effect with the mock database
val c: Int < IO =
    Env.run(db)(b)

// Additionally, a computation can require multiple values
// from its environment.

// A second interface to be injected
trait Cache:
    def clear: Unit < IO

// A computation that requires two values
val d: Unit < (Env[Database] & Env[Cache] & IO) =
    Env.get[Database].map { db =>
        db.count.map {
            case 0 =>
                Env.get[Cache].map(_.clear)
            case _ =>
                ()
        }
    }
```

### Local: Scoped Values

The `Local` effect operates on top of `IO` and enables the definition of scoped values. This mechanism is typically used to store contextual information of a computation. For example, in request processing, locals can be used to store information about the user who initiated the request. In a library for database access, locals can be used to propagate transactions.

```scala
import kyo.*

// Local need to be initialized with a default value
val myLocal: Local[Int] =
    Local.init(42)

// The 'get' method returns the current value of the local
val a: Int < IO =
    myLocal.get

// The 'let' method assigns a value to a local within the
// scope of a computation. This code produces 43 (42 + 1)
val b: Int < IO =
    myLocal.let(42)(a.map(_ + 1))
```

> Note: Kyo's effects are designed so locals are properly propagated. For example, they're automatically inherited by forked computations in `Async`.

### Resource: Resource Safety

The `Resource` effect handles the safe use of external resources like network connections, files, and any other resource that needs to be freed once the computation finalizes. It serves as a mechanism similar to ZIO's `Scope`.

```scala
import java.io.Closeable
import kyo.*

class Database extends Closeable:
    def count: Int < IO = 42
    def close()          = {}

// The `acquire` method accepts any object that
// implements Java's `Closeable` interface
val db: Database < (Resource & Async) =
    Resource.acquire(new Database)

// Use `run` to handle the effect, while also
// closing the resources utilized by the
// computationation
val b: Int < Async =
    Resource.run(db.map(_.count))

// The `ensure` method provides a low-level API to handle the finalization of
// resources directly. The `acquire` method is implemented in terms of `ensure`.

// Example method to execute a function on a database
def withDb[T](f: Database => T < Async): T < (Resource & Async) =
    // Initializes the database ('new Database' is a placeholder)
    IO(new Database).map { db =>
        // Registers `db.close` to be finalized
        Resource.ensure(db.close).map { _ =>
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

### Choice: Exploratory Branching

The `Choice` effect is designed to aid in handling and exploring multiple options, pathways, or outcomes in a computation. This effect is particularly useful in scenario where you're dealing with decision trees, backtracking algorithms, or any situation that involves dynamically exploring multiple options.

```scala
import kyo.*

// Evaluate each of the provided `Seq`s.
// Note how 'get' takes a 'Seq[T]'
// and returns a 'T < Choice'
val a: Int < Choice =
    Choice.get(Seq(1, 2, 3, 4))

// 'filter' discards the current element if
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

// Mixing 'IO' with 'Loop'
val d: Int < IO =
    Loop(1)(i =>
        if i < 5 then
            IO(println(s"Iteration: $i")).map(_ => Loop.continue(i + 1))
        else
            Loop.done(i)
    )

// Mixing 'Console' with 'Loop'
val e: Int < IO =
    Loop(1)(i =>
        if i < 5 then
            Console.println(s"Iteration: $i").map(_ => Loop.continue(i + 1))
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

// Print a message every 3 iterations
val a: Int < IO =
    Loop.indexed(1)((idx, i) =>
        if idx < 10 then
            if idx % 3 == 0 then
                Console.println(s"Iteration $idx").map(_ => Loop.continue(i + 1))
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

### Chunk: Efficient Sequences

`Chunk` is an efficient mechanism for processing sequences of data in a purely functional manner. It offers a wide range of operations optimized for different scenarIO, ensuring high performance without compromising functional programming principles.

`Chunk` is designed as a lightweight wrapper around arrays, allowing for efficient random access and transformation operations. Its internal representation is carefully crafted to minimize memory allocation and ensure stack safety. Many of its operations have an algorithmic complexity of `O(1)`, making them highly performant for a variety of use cases.

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
    Chunk(a, b).flatten

// Obtain sequentially distict elements.
// Outputs: Chunk(1, 2, 3, 1)
val p: Chunk[Int] =
    Chunk(1, 1, 2, 3, 3, 1, 1).changes
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
val a: Emit.Ack < Emit[Int] =
    Emit(42)

// Add multiple values
val b: String < Emit[Int] =
    for
        _ <- Emit(1)
        _ <- Emit(2)
        _ <- Emit(3)
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
        _ <- Emit(1)
        _ <- Emit("log")
        _ <- Emit(2)
    yield "result"

// Note how `run` requires an explicit type
// parameter when a computation has multiple
// pending `Sum`s.
val b: (Chunk[Int], (Chunk[String], String)) < Any =
    Emit.run[Int](Emit.run[String](a))
```

The `Emit` effect is useful for collecting diagnostic information, accumulating intermediate results, or building up data structures during a computation.

### Aspect: Aspect-Oriented Programming

The `Aspect` effect in Kyo allows for high-level customization of behavior across your application. This is similar to how some frameworks use aspects for centralized control over diverse functionalities like database timeouts, authentication, authorization, and transaction management. You can modify these core operations without altering their individual codebases, streamlining how centralized logic is applied across different parts of an application. This makes `Aspect` ideal for implementing cross-cutting concerns in a clean and efficient manner.

To instantiate an aspect, use the `Aspect.init` method. It takes three type parameters:

1. `T`: The input type of the aspect
2. `U`: The output type of the aspect
3. `S`: The effects the aspect may perform

```scala
import java.io.Closeable
import kyo.*

class Database extends Closeable:
    def count: Int < IO = 42
    def close()          = {}

// Initialize an aspect that takes a 'Database' and returns
// an 'Int', potentially performing 'IO' effects
val countAspect: Aspect[Database, Int, IO] =
    Aspect.init[Database, Int, IO]

// The method 'apply' activates the aspect for a computation
def count(db: Database): Int < IO =
    countAspect(db)(_.count)

// To bind an aspect to an implementation, first create a new 'Cut'
val countPlusOne =
    new Cut[Database, Int, IO]:
        // The first param is the input of the computation and the second is
        // the computation being handled
        def apply[S](v: Database < S)(f: Database => Int < IO) =
            v.map(db => f(db).map(_ + 1))

// Bind the 'Cut' to a computation with the 'let' method.
// The first param is the 'Cut' and the second is the computation
// that will run with the custom binding of the aspect
def example(db: Database): Int < IO =
    countAspect.let(countPlusOne) {
        count(db)
    }

// If an aspect is bound to multiple `Cut` implementations, the order of
// their execution is determined by the sequence in which they are scoped
// within the computation.

// Another 'Cut' implementation
val countTimesTen =
    new Cut[Database, Int, IO]:
        def apply[S](v: Database < S)(f: Database => Int < IO) =
            v.map(db => f(db).map(_ * 10))

// First bind 'countPlusOne' then 'countTimesTen'
// the result will be (db.count + 1) * 10
def example1(db: Database) =
    countAspect.let(countPlusOne) {
        countAspect.let(countTimesTen) {
            count(db)
        }
    }

// First bind 'countTimesTen' then 'countPlusOne'
// the result will be (db.count * 10) + 1
def example2(db: Database) =
    countAspect.let(countTimesTen) {
        countAspect.let(countPlusOne) {
            count(db)
        }
    }

// Cuts can also be composed via `andThen`
def example3(db: Database) =
    countAspect.let(countTimesTen.andThen(countPlusOne)) {
        count(db)
    }
```

### Console: Console Interaction

```scala
import kyo.*

// Read a line from the console
val a: String < IO =
    Console.readln

// Print to stdout
val b: Unit < IO =
    Console.print("ok")

// Print to stdout with a new line
val c: Unit < IO =
    Console.println("ok")

// Print to stderr
val d: Unit < IO =
    Console.printErr("fail")

// Print to stderr with a new line
val e: Unit < IO =
    Console.printlnErr("fail")

// Explicitly specifying the 'Console' implementation
val f: Unit < IO =
    Console.let(Console.live)(e)
```

### Clock: Time Management

```scala
import java.time.Instant
import kyo.*

// Obtain the current time
val a: Instant < IO =
    Clock.now

// Run with an explicit 'Clock'
val c: Instant < IO =
    Clock.let(Clock.live)(a)
```

### Random: Random Values

```scala
import kyo.*

// Generate a random 'Int'
val a: Int < IO = Random.nextInt

// Generate a random 'Int' within a bound
val b: Int < IO = Random.nextInt(42)

// A few method variants
val c: Long < IO    = Random.nextLong
val d: Double < IO  = Random.nextDouble
val e: Boolean < IO = Random.nextBoolean
val f: Float < IO   = Random.nextFloat
val g: Double < IO  = Random.nextGaussian

// Obtain a random value from a sequence
val h: Int < IO =
    Random.nextValue(List(1, 2, 3))

// Explicitly specify the `Random` implementation
val k: Int < IO =
    Random.let(Random.live)(h)
```

### Log: Logging

`Log` is designed to streamline the logging process without requiring the instantiation of a `Logger`. By leveraging the [sourcecode](https://github.com/com-lihaoyi/sourcecode) library, log messages automatically include source code position information, enhancing the clarity and usefulness of the logs.

```scala 
import kyo.*

// Log provide trace, debug, info,
// warn, and error method variants.
val a: Unit < IO =
    Log.error("example")

// Each variant also has a method overload
// that takes a 'Throwable' as a second param
val d: Unit < IO =
    Log.error("example", new Exception)
```

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
val a: Int < IO =
    IO(42)

// Trace the execution of the
// `a` example computation
val b: Int < IO =
    stats2.traceSpan("my_span")(a)
```

### Path: File System Utilities

`Path` provides utilities for interacting with the file system. It offers methods for reading, writing, and manipulating files and directories in a purely functional manner.

```scala
import kyo.*

// Create a Path instance representing a path
val path: Path = Path("tmp", "file.txt")

// Read the entire contents of a file as a String
val content: String < IO =
    path.read

// Write a String to a file
val writeResult: Unit < IO =
    path.write("Hello, world!")

// Check if a path exists
val exists: Boolean < IO =
    path.exists

// Create a directory
val createDir: Unit < IO =
    Path("tmp", "test").mkDir
```

`Path` instances are created by providing a list of path segments, which can be either `String`s or other `Path` instances. This allows for easy composition of paths. `Path` also provides methods for other common file operations:

- Reading: `read`, `readBytes`, `readLines`, `readStream`, `readLinesStream`, `readBytesStream`
- Writing: `write`, `writeBytes`, `writeLines`, `append`, `appendBytes`, `appendLines`
- Directory operations: `list`, `walk`
- File metadata: `exists`, `isDir`, `isFile`, `isLink`
- File manipulation: `mkDir`, `mkFile`, `move`, `copy`, `remove`, `removeAll`

All methods that perform side effects are suspended using the `IO` effect, ensuring referential transparency. Methods that work with streams of data, such as `readStream` and `walk`, return a `Stream` of the appropriate type, suspended using the `Resource` effect to ensure proper resource handling.

```scala
import kyo.*

val path: Path = Path("tmp", "file.txt")

// Read a file as a stream of lines
val lines: Stream[String, Resource & IO] =
    path.readLinesStream()

// Process the stream
val result: Unit < (Resource & Console & Async) =
    lines.map(line => Console.println(line)).runDiscard

// Walk a directory tree
val tree: Stream[Path, IO] =
    Path("tmp").walk

// Process each file in the tree
val processedTree: Unit < (Console & Async) =
    tree.map(file => file.read.map(content => Console.println(s"File: ${file}, Content: $content"))).runDiscard
```

`Path` integrates with Kyo's `Stream` API, allowing for efficient processing of file contents using streams. The `sink` and `sinkLines` extension methods on `Stream` enable writing streams of data back to files.

```scala
import kyo.*

// Create a stream of bytes
val bytes: Stream[Byte, IO] = Stream.init(Seq[Byte](1, 2, 3))

// Write the stream to a file
val sinkResult: Unit < (Resource & IO) =
    bytes.sink(Path("path", "to", "file.bin"))
```

### Process: Process Execution

`Process` provides a way to spawn and interact with external processes from within Kyo. It offers a purely functional interface for process creation, execution, and management.

```scala
import kyo.*

// Create a simple command
val command: Process.Command = Process.Command("echo", "Hello, World!")

// Spawn the process and obtain the result
val result: String < IO = command.text
```

The core of `Process` is the `Process.Command` type, which represents a command to be executed. It can be created using the `Process.Command.apply` method, which takes a variable number of arguments representing the command and its arguments.

The `Process` object also provides a `jvm` sub-object for spawning JVM processes directly.

```scala
import kyo.*

class MyClass extends KyoApp:
    run {
        Console.println(s"Executed with args: $args")
    }
end MyClass

// Spawn a new JVM process
val jvmProcess: Process < IO =
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
val modifiedResult: String < IO = modifiedCommand.text
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

The `Async` effect allows for the asynchronous execution of computations via a managed thread pool. The core function, `init`, spawns a new "green thread," also known as a fiber, to handle the given computation. This provides a powerful mechanism for parallel execution and efficient use of system resources. Moreover, fibers maintain proper propagation of `Local`, ensuring that context information is carried along during the forking process.

```scala
import kyo.*

// Fork a computation. The parameter is
// taken by reference and automatically
// suspended with 'IO'
val a: Fiber[Nothing, Int] < IO =
    Async.run(Math.cos(42).toInt)

// It's possible to "extract" the value of a
// 'Fiber' via the 'get' method. This is also
// referred as "joining the fiber"
val b: Int < Async =
    a.map(_.get)
```

The `parallel` methods fork multiple computations in parallel, join the fibers, and return their results.

```scala
import kyo.*

// An example computation
val a: Int < IO =
    IO(Math.cos(42).toInt)

// There are method overloadings for up to four
// parallel computations. Paramters taken by
// reference
val b: (Int, String) < Async =
    Async.parallel(a, "example")

// Alternatively, it's possible to provide
// a 'Seq' of computations and produce a 'Seq'
// with the results
val c: Seq[Int] < Async =
    Async.parallel(Seq(a, a.map(_ + 1)))

// The 'Fiber.parallel' method is similar but
// it doesn't automatically join the fibers and
// produces a 'Fiber[Seq[T]]'
val d: Fiber[Nothing, Seq[Int]] < IO =
    Fiber.parallel(Seq(a, a.map(_ + 1)))
```

The `race` methods are similar to `parallel` but they return the first computation to complete with either a successful result or a failure. Once the first result is produced, the other computations are automatically interrupted.

```scala
import kyo.*

// An example computation
val a: Int < IO =
    IO(Math.cos(42).toInt)

// There are method overloadings for up to four
// computations. Pameters taken by reference
val b: Int < Async =
    Async.race(a, a.map(_ + 1))

// It's also possible to to provide a 'Seq'
// of computations
val c: Int < Async =
    Async.race(Seq(a, a.map(_ + 1)))

// 'Fiber.race' produces a 'Fiber' without
// joining it
val d: Fiber[Nothing, Int] < IO =
    Fiber.race(Seq(a, a.map(_ + 1)))
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

The `fromFuture` method sprovide interoperability with Scala's `Future`.

```scala
import kyo.*
import scala.concurrent.Future

// An example 'Future' instance
val a: Future[Int] = Future.successful(42)

// Transform a 'Future' into a 'Fiber'
val b: Fiber[Nothing, Int] < IO =
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
val b: Boolean < IO =
    a.isDone

// Instance-level version of 'Async.get'
val c: Int < Async =
    a.get

// Avoid this low-level API to attach a
// a callback to a fiber
val d: Unit < IO =
    a.onComplete(println(_))

// A variant of `get` that returns a `Result`
// with the failed or successful result
val e: Result[Nothing, Int] < Async =
    a.getResult

// Try to interrupt/cancel a fiber
val f: Boolean < IO =
    a.interrupt

// Transforms a fiber into a Scala 'Future'
val h: Future[Int] < IO =
    a.toFuture

// 'Fiber' provides a monadic API with both
// 'map' and 'flatMap'
val i: Fiber[Nothing, Int] < IO =
    a.flatMap(v => Fiber.success(v + 1))
```

Similarly to `IO`, users should avoid handling the `Async` effect directly and rely on `KyoApp` instead. If strictly necessary, there are two methods to handle the `Async` effect:

1. `run` takes a computation that has only the `Async` effect pending and returns a `Fiber` instance without blocking threads.
2. `runAndBlock` accepts computations with arbitrary pending effects but it handles asynchronous operations by blocking the current thread.

```scala
import kyo.*

// An example computation with fibers
val a: Int < Async =
    Async.run(Math.cos(42).toInt).map(_.get)

// Avoid handling 'Async' directly
// Note how the code has to handle the
// 'IO' effect and then handle 'Async'
val b: Fiber[Nothing, Int] < IO =
    Async.run(IO.runLazy(a))

// The 'runAndBlock' method accepts
// arbitrary pending effects but relies
// on thread blocking and requires a timeout
val c: Int < (Abort[Timeout] & IO) =
    Async.runAndBlock(5.seconds)(a)
```

> Note: Handling the `Async` effect doesn't break referential transparency as with `IO` but its usage is not trivial due to the limitations of the pending effects, especially `IO`. Prefer `KyoApp` instead.

The `Async` effect also offers a low-level API to create `Promise`s as way to integrate external async operations with fibers. These APIs should be used only in low-level integration code.

```scala
import kyo.*

// Initialize a promise
val a: Promise[Nothing, Int] < IO =
    Promise.init[Nothing, Int]

// Try to fulfill a promise
val b: Boolean < IO =
    a.map(_.complete(Result.success(42)))

// Fullfil the promise with
// another fiber
val c: Boolean < IO =
    a.map(fiber => Async.run(1).map(fiber.become(_)))
```

> A `Promise` is basically a `Fiber` with all the regular functionality plus the `complete` and `become` methods to manually fulfill the promise.

### Queue: Concurrent Queuing

The `Queue` effect operates atop of `IO` and provides thread-safe queue data structures based on the high-performance [JCTools](https://github.com/JCTools/JCTools) library on the JVM. For ScalaJS, a simple `ArrayQueue` is used.

**Bounded queues**
```scala
import kyo.*

// A bounded channel rejects new
// elements once full
val a: Queue[Int] < IO =
    Queue.init(capacity = 42)

// Obtain the number of items in the queue
// via the method 'size' in 'Queue'
val b: Int < IO =
    a.map(_.size)

// Get the queue capacity
val c: Int < IO =
    a.map(_.capacity)

// Try to offer a new item
val d: Boolean < IO =
    a.map(_.offer(42))

// Try to poll an item
val e: Maybe[Int] < IO =
    a.map(_.poll)

// Try to 'peek' an item without removing it
val f: Maybe[Int] < IO =
    a.map(_.peek)

// Check if the queue is empty
val g: Boolean < IO =
    a.map(_.isEmpty)

// Check if the queue is full
val h: Boolean < IO =
    a.map(_.isFull)

// Drain the queue items
val i: Seq[Int] < IO =
    a.map(_.drain)

// Close the queue. If successful,
// returns a Some with the drained
// elements
val j: Maybe[Seq[Int]] < IO =
    a.map(_.close)
```

**Unbounded queues**
```scala
import kyo.*

// Avoid `Queue.unbounded` since if queues can
// grow without limits, the GC overhead can make
// the system fail
val a: Queue.Unbounded[Int] < IO =
    Queue.initUnbounded()

// A 'dropping' queue discards new entries
// when full
val b: Queue.Unbounded[Int] < IO =
    Queue.initDropping(capacity = 42)

// A 'sliding' queue discards the oldest
// entries if necessary to make space for new
// entries
val c: Queue.Unbounded[Int] < IO =
    Queue.initSliding(capacity = 42)

// Note how 'dropping' and 'sliding' queues
// return 'Queue.Unbounded`. It provides
// an additional method to 'add' new items
// unconditionally
val d: Unit < IO =
    c.map(_.add(42))
```

**Concurrent access policies**

It's also possible to specify a concurrent `Access` policy as the second parameter of the `Queue.init` methods. This configuration has an effect only on the JVM and is ignored in ScalaJS.

| Policy | Full Form                              | Description                                                                                                                                                                                                          |
| ------ | -------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Mpmc   | Multiple Producers, Multiple Consumers | Supports multiple threads/fibers simultaneously enqueuing and dequeuing elements. This is the most flexible but may incur the most overhead due to the need to synchronize between multiple producers and consumers. |
| Mpsc   | Multiple Producers, Single Consumer    | Allows multiple threads/fibers to enqueue elements but restricts dequeuing to a single consumer. This can be more efficient than `Mpmc` when only one consumer is needed.                                            |
| Spmc   | Single Producer, Multiple Consumers    | Allows only a single thread/fiber to enqueue elements, but multiple threads/fibers can dequeue elements. Useful when only one source is generating elements to be processed by multiple consumers.                   |
| Spsc   | Single Producer, Single Consumer       | The most restrictive but potentially fastest policy. Only one thread/fiber can enqueue elements, and only one thread/fiber can dequeue elements.                                                                     |

Each policy is suitable for different scenarIO and comes with its own trade-offs. For example, `Mpmc` is highly flexible but can be slower due to the need for more complex synchronization. `Spsc`, being the most restrictive, allows for optimizations that could make it faster for specific single-producer, single-consumer scenarIO.

You can specify the access policy when initializing a queue, and it is important to choose the one that aligns with your application's needs for optimal performance.

```scala
import kyo.*

// Initialize a bounded queue with a
// Multiple Producers, Multiple
// Consumers policy
val a: Queue[Int] < IO =
    Queue.init(
        capacity = 42,
        access = Access.Mpmc
    )
```

### Channel: Backpressured Communication

The `Channel` effect serves as an advanced concurrency primitive, designed to facilitate seamless and backpressured data transfer between various parts of your application. Built upon the `Async` effect, `Channel` not only ensures thread-safe communication but also incorporates a backpressure mechanism. This mechanism temporarily suspends fibers under specific conditions—either when waiting for new items to arrive or when awaiting space to add new items.

```scala
import kyo.*

// A 'Channel' is initialized
// with a fixed capacity
val a: Channel[Int] < IO =
    Channel.init(capacity = 42)

// It's also possible to specify
// an 'Access' policy
val b: Channel[Int] < IO =
    Channel.init(
        capacity = 42,
        access = Access.Mpmc
    )
```

While `Channel` share similarities with `Queue`—such as methods for querying size (`size`), adding an item (`offer`), or retrieving an item (`poll`)—they go a step further by offering backpressure-sensitive methods, namely `put` and `take`.

```scala
import kyo.*

// An example channel
val a: Channel[Int] < IO =
    Channel.init(capacity = 42)

// Adds a new item to the channel.
// If there's no capacity, the fiber
// is automatically suspended until
// space is made available
val b: Unit < Async =
    a.map(_.put(42))

// Takes an item from the channel.
// If the channel is empty, the fiber
// is suspended until a new item is
// made available
val c: Int < Async =
    a.map(_.take)

// 'putFiber' returns a `Fiber` that
// will complete once the put completes
val d: Fiber[Nothing, Unit] < IO =
    a.map(_.putFiber(42))

// 'takeFiber' also returns a fiber
val e: Fiber[Nothing, Int] < IO =
    a.map(_.takeFiber)

// Closes the channel. If successful,
// returns a Some with the drained
// elements. All pending puts and takes
// are automatically interrupted
val f: Maybe[Seq[Int]] < IO =
    a.map(_.close)
```

The ability to suspend fibers during `put` and `take` operations allows `Channel` to provide a more controlled form of concurrency. This is particularly beneficial for rate-sensitive or resource-intensive tasks where maintaining system balance is crucial.

> Important: While a `Channel` comes with a predefined item capacity, it's crucial to understand that there is no upper limit on the number of fibers that can be suspended by it. In scenarIO where your application spawns an unrestricted number of fibers—such as an HTTP service where each incoming request initiates a new fiber—this can lead to significant memory consumption. The channel's internal queue for suspended fibers could grow indefinitely, making it a potential source of unbounded queuing and memory issues. Exercise caution in such use-cases to prevent resource exhaustion.

### Hub: Broadcasting with Backpressure

`Hub` provide a broadcasting mechanism where messages are sent to multiple listeners simultaneously. They are similar to `Channel`, but they are uniquely designed for scenarIO involving multiple consumers. The key feature of `Hub` is their ability to apply backpressure automatically. This means if the `Hub` and any of its listeners' buffers are full, the `Hub` will pause both the producers and consumers to prevent overwhelming the system. Unlike `Channel`, `Hub` don't offer customization in concurrent access policy as they are inherently meant for multi-producer, multi-consumer environments.

```scala
import kyo.*
import kyo.Hub.Listener

// Initialize a Hub with a buffer
val a: Hub[Int] < IO =
    Hub.init[Int](3)

// Hub provide APIs similar to
// channels: size, offer, isEmpty,
// isFull, putFiber, put
val b: Boolean < IO =
    a.map(_.offer(1))

// But reading from hubs can only
// happen via listener. Listeners
// only receive messages sent after
// their cration. To create call
// `listen`:
val c: Listener[Int] < IO =
    a.map(_.listen)

// Each listener can have an
// additional message buffer
val d: Listener[Int] < IO =
    a.map(_.listen(bufferSize = 3))

// Listeners provide methods for
// receiving messages similar to
// channels: size, isEmpty, isFull,
// poll, takeFiber, take
val e: Int < Async =
    d.map(_.take)

// A listener can be closed
// individually. If successful,
// a Some with the backlog of
// pending messages is returned
val f: Maybe[Seq[Int]] < IO =
    d.map(_.close)

// If the Hub is closed, all
// listeners are automatically
// closed. The returned backlog
// only include items pending in
// the hub's buffer. The listener
// buffers are discarded
val g: Maybe[Seq[Int]] < IO =
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
val a: Meter < IO =
    Meter.initMutex

// 'semaphore': Limit concurrent tasks
val b: Meter < IO =
    Meter.initSemaphore(concurrency = 42)

// 'rateLimiter': Tasks per time window
val c: Meter < IO =
    Meter.initRateLimiter(
        rate = 10,
        period = 1.second
    )

// 'pipeline': Combine multiple 'Meter's
val d: Meter < IO =
    Meter.pipeline(a, b, c)
```

The `Meter` class comes with a handful of methods designed to provide insights into and control over computational execution.

```scala
import kyo.*

// An example 'Meter'
val a: Meter < IO =
    Meter.initMutex

// Get available permits
val b: Int < IO =
    a.map(_.available)

// Check for available permit
val c: Boolean < IO =
    a.map(_.isAvailable)

// Use 'run' to execute tasks
// respecting meter limits
val d: Int < Async =
    a.map(_.run(Math.cos(42).toInt))

// 'tryRun' executes if a permit is
// available; returns 'None' otherwise
val e: Maybe[Int] < IO =
    a.map(_.tryRun(Math.cos(42).toInt))
```

### Timer: Scheduled Execution

The `Timer` effect is designed for control over the timing of task execution.

```scala
import kyo.*

// An example computation to
// be scheduled
val a: Unit < IO =
    IO(())

// Schedule a delayed task
val b: TimerTask < IO =
    Timer.schedule(delay = 1.second)(a)

// Recurring task with
// intial delay
val c: TimerTask < IO =
    Timer.scheduleAtFixedRate(
        initialDelay = 1.minutes,
        period = 1.minutes
    )(a)

// Recurring task without
// initial delay
val d: TimerTask < IO =
    Timer.scheduleAtFixedRate(
        period = 1.minutes
    )(a)

// Schedule with fixed delay between tasks
val e: TimerTask < IO =
    Timer.scheduleWithFixedDelay(
        initialDelay = 1.minutes,
        period = 1.minutes
    )(a)

// without initial delay
val f: TimerTask < IO =
    Timer.scheduleWithFixedDelay(
        period = 1.minutes
    )(a)

// Specify the 'Timer' explictly
val i: TimerTask < IO =
    Timer.let(Timer.live)(f)
```

`TimerTask` offers methods for more granular control over the scheduled tasks.

```scala
import kyo.*

// Example TimerTask
val a: TimerTask < IO =
    Timer.schedule(1.second)(())

// Try to cancel the task
val b: Boolean < IO =
    a.map(_.cancel)

// Check if the task is cancelled
val c: Boolean < IO =
    a.map(_.isCancelled)

// Check if the task is done
val d: Boolean < IO =
    a.map(_.isDone)
```

### Latch: Fiber Coordination

The `Latch` effect serves as a coordination mechanism for fibers in a concurrent environment, primarily used for task synchronization. It provides a low-level API for controlling the flow of execution and ensuring certain tasks are completed before others, all while maintaining thread safety.

```scala
import kyo.*

// Initialize a latch with 'n' permits
val a: Latch < IO =
    Latch.init(3)

// Await until the latch releases
val b: Unit < Async =
    a.map(_.await)

// Release a permit from the latch
val c: Unit < IO =
    a.map(_.release)

// Get the number of pending permits
val d: Int < IO =
    a.map(_.pending)
```

### Atomic: Concurrent State

The `Atomic` effect provides a set of thread-safe atomic variables to manage mutable state in a concurrent setting. Available atomic types include Int, Long, Boolean, and generic references.

```scala
import kyo.*

// Initialize atomic variables
val aInt: AtomicInt < IO =
    AtomicInt.init(0)
val aLong: AtomicLong < IO =
    AtomicLong.init(0L)
val aBool: AtomicBoolean < IO =
    AtomicBoolean.init(false)
val aRef: AtomicRef[String] < IO =
    AtomicRef.init("initial")

// Fetch values
val b: Int < IO =
    aInt.map(_.get)
val c: Long < IO =
    aLong.map(_.get)
val d: Boolean < IO =
    aBool.map(_.get)
val e: String < IO =
    aRef.map(_.get)

// Update values
val f: Unit < IO =
    aInt.map(_.set(1))
val g: Unit < IO =
    aLong.map(_.lazySet(1L))
val h: Boolean < IO =
    aBool.map(_.cas(false, true))
val i: String < IO =
    aRef.map(_.getAndSet("new"))
```

### Adder: Concurrent Accumulation

The `Adder` effect offers thread-safe variables for efficiently accumulating numeric values. The two primary classes, `LongAdder` and `DoubleAdder`, are optimized for high-throughput scenarIO where multiple threads update the same counter.

```scala
import kyo.*

// Initialize Adder
val longAdder: LongAdder < IO =
    LongAdder.init
val doubleAdder: DoubleAdder < IO =
    DoubleAdder.init

// Adding values
val a: Unit < IO =
    longAdder.map(_.add(10L))
val b: Unit < IO =
    doubleAdder.map(_.add(10.5))

// Increment and Decrement LongAdder
val c: Unit < IO =
    longAdder.map(_.increment)
val d: Unit < IO =
    longAdder.map(_.decrement)

// Fetch summed values
val e: Long < IO =
    longAdder.map(_.get)
val f: Double < IO =
    doubleAdder.map(_.get)

// Resetting the adders
val g: Unit < IO =
    longAdder.map(_.reset)
val h: Unit < IO =
    doubleAdder.map(_.reset)
```

## Data Types

### Maybe: Allocation-free Optional Values

`Maybe` provides an allocation-free alternative to Scala's standard `Option` type. It is designed to be a drop-in replacement for `Option`, offering similar functionality while minimizing memory allocation.

```scala
import kyo._

// Create a 'Maybe' value
val a: Maybe[Int] = Maybe(42)

// 'Maybe.empty' represents the absence of a value
val b: Maybe[Int] = Maybe.empty[Int]

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
```

### Result: Low-allocation Try Alternative

`Result` is a low-allocation alternative to Scala's `Try` type, designed to represent the result of a computation that may either succeed with a value or fail with an exception. It minimizes memory allocation overhead, especially in the case of successful results.

```scala
import kyo._
import scala.util.Try

// Create a 'Result' from a value
val a: Result[Nothing, Int] = Result.success(42)

// Create a 'Result' from an failure
val b: Result[Exception, Int] = Result.fail(new Exception("Oops"))

// Use 'apply' to create a 'Result' from a block of code
val c: Result[Nothing, Int] = Result(42 / 0)

// 'isSuccess' checks if the 'Result' is a success
val d: Boolean = a.isSuccess

// 'isFail' checks if the 'Result' is a failure
val e: Boolean = b.isFail

// 'get' retrieves the value if successful, otherwise throws
val f: Int = a.get

// 'getOrElse' provides a default value for failures
val g: Int = b.getOrElse(0)

// 'fold' applies a function based on success or failure
val h: String = a.fold(e => "failure " + e)(_.toString)

// 'map' transforms the value if successful
val i: Result[Nothing, String] = a.map(_.toString)

// 'flatMap' allows chaining 'Result' operations
val j: Result[Nothing, Int] = a.flatMap(v => Result.success(v + 1))

// 'flatten' removes one level of nesting from a 'Result[Result[T]]'
val k: Result[Nothing, Result[Nothing, Int]] = Result.success(a)
val l: Result[Nothing, Int] = k.flatten

// 'filter' conditionally keeps or discards the value
val m: Result[NoSuchElementException, Int] = a.filter(_ > 0)

// 'recover' allows handling failures with a partial function
val n: Result[Exception, Int] = b.recover { case Result.Fail(_: ArithmeticException) => 0 }

// 'recoverWith' allows handling failures with a partial function returning a 'Result'
val o: Result[Exception, Int] = b.recoverWith { case Result.Fail(_: ArithmeticException) => Result.success(0) }

// 'toEither' converts a 'Result' to an 'Either'
val p: Either[Throwable, Int] = a.toEither

// 'toTry' converts a 'Result' to a 'Try'
val q: Try[Int] = a.toTry
```

Under the hood, `Result` is defined as an opaque type that is a supertype of `Success[T]` and `Failure[T]`. Success[T] represents a successful result and is encoded as either the value itself (`T`) or a special SuccessFailure[`T`] case class. The `SuccessFailure[T]` case class is used to handle the rare case where a `Failure[T]` needs to be wrapped in a `Success[T]`. On the other hand, a failed `Result` is always represented by a `Failure[T]` case class, which contains the exception that caused the failure. This means that creating a `Failure[T]` does incur an allocation cost. Additionally, some methods on `Result`, such as `fold`, `map`, and `flatMap`, may allocate in certain cases due to the need to catch and handle exceptions.

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
            IO(v.toInt)
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

`Routes` integrates with the Tapir library to help set up HTTP servers. The method `Routes.add` is used for adding routes. This method requires the definition of a route, which can be an Tapir Endpoint instance or a builder function. Additionally, the method requires the implementation of the endpoint, which is provided as the second parameter group. To start the server, the `Routes` effect is handled, which initializes the HTTP server with the specified routes.

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

For further examples, Kyo's [example ledger service](https://github.com/getkyo/kyo/tree/main/kyo-examples/jvm/src/main/scala/kyo/examples/ledger) provides practical applications of these concepts.

### ZIOs: Integration with ZIO

The `ZIOs` effect provides seamless integration between Kyo and the ZIO library. The effect is designed to enable gradual adoption of Kyo within a ZIO codebase. The integration properly suspends side effects and propagates fiber cancellations/interrupts between both libraries.

```scala
import kyo.*
import zio.*

// Use the 'get' method to extract a 'ZIO' effect
val a: Int < ZIOs =
    ZIOs.get(ZIO.succeed(42))

// 'get' also supports error handling with 'Abort'
val b: Int < (Abort[String] & ZIOs) =
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
// IO and Async effects
val a: Int < ZIOs =
    for
        v1 <- ZIOs.get(ZIO.succeed(21))
        v2 <- IO(21)
        v3 <- Async.run(-42).map(_.get)
    yield v1 + v2 + v3

// Using fibers from both libraries
val b: Int < ZIOs =
    for
        f1 <- ZIOs.get(ZIO.succeed(21).fork)
        f2 <- Async.run(IO(21))
        v1 <- ZIOs.get(f1.join)
        v2 <- f2.get
    yield v1 + v2

// Transforming ZIO effects within Kyo computations
val c: Int < ZIOs =
    ZIOs.get(ZIO.succeed(21)).map(_ * 2)

// Transforming Kyo effects within ZIO effects
val d: Task[Int] =
    ZIOs.run(IO(21).map(_ * 2))
```

> Note: Support for ZIO environments (`R` in `ZIO[R, E, A]`) is currently in development. Once implemented, it will be possible to use ZIO effects with environments directly within Kyo computations.

### Resolvers: GraphQL Server via Caliban

`Resolvers` integrates with the [Caliban](https://github.com/ghostdogpr/caliban) library to help setup GraphQL servers.

The first integration is that you can use Kyo effects inside your Caliban schemas by importing `kyo.given`.
- If your Kyo effects is `(Abort[Throwable] & ZIO)` or a subtype of it (`ZIO` includes `Async & IO`), a Caliban `Schema` can be derived automatically.
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
import sttp.tapir.json.zio.*
import sttp.tapir.server.netty.*
import zio.Task

case class Query(k: Int < Abort[Throwable]) derives Schema.SemiAuto
val api = graphQL(RootResolver(Query(42)))

val a: NettyKyoServerBinding < (ZIOs & Abort[CalibanError]) =
    Resolvers.run { Resolvers.get(api) }

// similarly to the tapir integration, you can also pass a `NettyKyoServer` explicitly
val b: NettyKyoServerBinding < (ZIOs & Abort[CalibanError]) =
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
import sttp.tapir.json.zio.*
import zio.Task

type CustomEffects = Var[Int] & Env[String]
object schema extends SchemaDerivation[Runner[CustomEffects]]
case class Query(k: Int < CustomEffects) derives schema.SemiAuto

val api = graphQL(RootResolver(Query(42)))

// runner for our CustomEffects
val runner = new Runner[CustomEffects]:
    def apply[T: Flat](v: T < CustomEffects): Task[T] = ZIOs.run(Env.run("kyo")(Var.run(0)(v)))

val d = Resolvers.run(runner) { Resolvers.get(api) }
```

### AIs: LLM Abstractions via OpenAI

Coming soon..

## Restrictions

### Nested Effects

In addition recursion, Kyo's unboxed representation of computations in certain scenarIO introduces a restriction where it's not possible to handle effects of computations with nested effects like `Int < IO < IO`.

```scala
import kyo.*

// An example computation with
// nested effects
val a: Int < IO < Abort[Maybe.Empty] =
    Abort.get(Some(IO(1)))

// Can't handle a effects of a
// computation with nested effects

// Abort.run(a)
// Compilation failure:
//   Method doesn't accept nested Kyo computations.
//   Cannot prove 'scala.Int < kyo.IO' isn't nested. This error can be reported an unsupported pending effect is passed to a method. If that's not the case, provide an implicit evidence 'kyo.Flat[scala.Int < kyo.IO]'.

// Use `flatten` before handling
Abort.run(a.flatten)
```

Kyo performs checks at compilation time to ensure that nested effects are not used. This includes generic methods where the type system cannot confirm whether the computation is nested:

```scala
import kyo.*

// def test[T](v: T < Abort[Maybe.Empty]) =
//   Abort.run(v)
// Compilation failure:
//   Method doesn't accept nested Kyo computations.
//   Cannot prove 'T' isn't nested. This error can be reported an unsupported pending effect is passed to a method. If that's not the case, provide an implicit evidence 'kyo.Flat[T]'.

// It's possible to provide an implicit
// evidence of `Flat` to resolve
def test[T](v: T < Abort[Maybe.Empty])(using Flat[T]) =
    Abort.run(v)
```

All APIs that trigger effect handling have this restriction, which includes not only methods that handle effects directly but also methods that use effect handling internally.

## ZIO-like Combinators

For ZIO users, Kyo's core API can be frustrating for three reasons:

1. It is minimal by design.

While its uncluttered namespaces make it more approachable for beginners, users addicted to ZIO's powerful and intuitive combinators may find it unwieldy and possibly not worth the effort.

2. Effects are handled by functions that take effects as arguments, rather than by methods on effects.

ZIO users are used to having a large menu of combinators on `ZIO` values that can be chained together to manipulate effects fluently. `kyo-core`, by contrast, requires nesting effects within method calls, inverting the order in which users handle effects and requiring them either to create deeply nested expressions or to break expressions up into many intermediate expressions.

3. Factory methods are distributed among different objects

Being more modular that ZIO, Kyo segregates its effect types more cleanly, placing its effect constructors in the companion objects to their corresponding types. This is not a problem given the minimal API that Kyo offers, but ZIO users will miss typing `ZIO.` and seeing a rich menu of factory methods pop up on their IDE.

`kyo-combinators` alleviates these frustrations by providing:
1. Factory methods on the `Kyo` object, styled after those found on `ZIO`, for many of the core Kyo effect types.
2. Extension methods on Kyo effects modeled on ZIO combinators.

Generally speaking, the names of `kyo-combinators` methods are the same as the corresponding methods in ZIO. When this is not possible or doesn't make sense, `kyo-combinators` tries to keep close to ZIO conventions.

### Simple example

```scala 3
import kyo.*
import scala.concurrent.duration.*

trait HelloService:
    def sayHelloTo(saluee: String): Unit < (IO & Abort[Throwable])

object HelloService:
    object Live extends HelloService:
        override def sayHelloTo(saluee: String): Unit < (IO & Abort[Throwable]) =
            Kyo.suspendAttempt { // Adds IO & Abort[Throwable] effect
                println(s"Hello $saluee!")
            }
    end Live
end HelloService

val keepTicking: Nothing < (Console & Async) =
    (Console.print(".") *> Kyo.sleep(1.second)).forever

val effect: Unit < (Console & Async & Resource & Abort[Throwable] & Env[NameService]) =
    for
        nameService <- Kyo.service[NameService]       // Adds Env[NameService] effect
        _           <- keepTicking.forkScoped         // Adds Console, Async, and Resource effects
        saluee      <- Console.readln                 // Uses Console effect
        _           <- Kyo.sleep(2.seconds)           // Uses Async (semantic blocking)
        _           <- nameService.sayHelloTo(saluee) // Adds Abort[Throwable] effect
    yield ()

// There are no combinators for handling IO or blocking Async, since this should
// be done at the edge of the program
IO.run {                              // Handles IO
    Async.runAndBlock(Duration.Inf) { // Handles Async
        Kyo.scoped {                   // Handles Resource
            effect
                .provideAs[HelloService](HelloService.Live) // Handles Env[HelloService]
                .catchAbort((thr: Throwable) =>             // Handles Abort[Throwable]
                    Kyo.debug(s"Failed printing to console: ${throwable}")
                )
                .provideDefaultConsole // Handles Console
        }
    }
}
```

### Failure conversions

One notable departure from the ZIO API worth calling out is a set of combinators for converting between failure effects. Whereas ZIO has a single channel for describing errors, Kyo has different effect types that can describe failure in the basic sense of "short-circuiting": `Abort` and `Choice` (an empty `Seq` being equivalent to a short-circuit). `Abort[Maybe.Empty]` can also be used like `Choice` to model short-circuiting an empty result. It's useful to be able to move between these effects easily, so `kyo-combinators` provides a number of extension methods, usually in the form of `def effect1ToEffect2`.

Some examples:

```scala 
val abortEffect: Int < Abort[String] = ???

// Converts failures to empty failure
val maybeEffect: Int < Abort[Maybe.Empty] = abortEffect.abortToEmpty

// Converts empty failure to a single "choice" (or Seq)
val choiceEffect: Int < Choice = maybeEffect.emptyAbortToChoice

// Fails with Nil#head exception if empty and succeeds with Seq.head if non-empty
val newAbortEffect: Int < Abort[Throwable] = choiceEffect.choiceToThrowable

// Throws a throwable Abort failure (will actually throw unless suspended)
val unsafeEffect: Int < Any = newAbortEffect.implicitAborts

// Catch any suspended throws
val safeEffect: Int < Abort[Throwable] = unsafeEffect.explicitAborts
```

## Acknowledgements

Kyo's development was originally inspired by the paper ["Do Be Do Be Do"](https://arxiv.org/pdf/1611.09259.pdf) and its implementation in the [Unison](https://www.unison-lang.org/learn/language-reference/abilities-and-ability-handlers/) programming language. Kyo's design evolved from using interface-based effects to suspending concrete values associated with specific effects, making it more efficient when executed on the JVM.

Additionally, Kyo draws inspiration from [ZIO](https://zio.dev/) in various aspects. The core mechanism for algebraic effects can be seen as a generalization of ZIO's effect rotation, and many of Kyo's effects are directly influenced by ZIO's mature set of primitives. For instance, `Env` and `Abort` correspond to ZIO's effect channels, `Resource` function similarly to `Scope`, and `Hub` was introduced based on ZIO.

Kyo's asynchronous primitives take several aspects from [Twitter's util](https://github.com/twitter/util) and [Finagle](https://github.com/twitter/finagle), including features like async root compression, to provide stack safety, and support for cancellations (interruptions in Kyo).

Lastly, the name "Kyo" is derived from the last character of Nam-myoho-renge-kyo, the mantra practiced in [SGI Buddhism](https://www.sokaglobal.org/). It literally translates to "Sutra," referring to a compiled teaching of Shakyamuni Buddha, and is also interpreted as the "threads" that weave the fundamental fabric of life's reality.

License
-------

See the [LICENSE](https://github.com/getkyo/kyo/blob/master/LICENSE.txt) file for details.
 
