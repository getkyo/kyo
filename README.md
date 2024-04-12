### Please visit https://getkyo.io for an indexed version of this documentation.
##

<img src="https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png" width="200" alt="Kyo">

## Introduction

[![Build Status](https://github.com/getkyo/kyo/workflows/build/badge.svg)](https://github.com/getkyo/kyo/actions)
[![Discord](https://img.shields.io/discord/1087005439859904574)](https://discord.gg/EpVqFxY7)
[![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)](https://search.maven.org/search?q=g:io.getkyo)

Kyo is a toolkit for Scala development, spanning from browser-based apps in ScalaJS to high-performance backends on the JVM. It introduces a novel approach based on algebraic effects to deliver straightforward APIs in the pure Functional Programming paradigm. Unlike similar solutions, Kyo achieves this without inundating developers with esoteric concepts from Category Theory or using cryptic symbolic operators, resulting in a development experience that is both intuitive and robust.

Drawing inspiration from [ZIO](https://zio.dev/)'s [effect rotation](https://degoes.net/articles/rotating-effects), Kyo takes a more generalized approach. While ZIO restricts effects to two channels, dependency injection and short-circuiting, Kyo allows for an arbitrary number of effectful channels. This enhancement gives developers greater flexibility in effect management, while also simplifying Kyo's internal codebase through more principled design patterns.

### Getting Started

Kyo is available on Maven Central in multiple modules:

| Module           | Scala 3 | Scala JS | Description                         |
|------------------|---------|----------|-------------------------------------|
| kyo-core         | ✅      | ✅       | Core and concurrent effects         |
| kyo-direct       | ✅      | ✅       | Direct syntax support               |
| kyo-sttp         | ✅      | ✅       | Sttp HTTP Client                    |
| kyo-tapir        | ✅      | ✅       | Tapir HTTP Server                   |
| kyo-cache        | ✅      |          | Caffeine caching                    |
| kyo-stats-otel   | ✅      |          | Stats exporter for OpenTelemetry    |

For Scala 3:

```scala 
libraryDependencies += "io.getkyo" %% "kyo-core" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-direct" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-cache" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-stats-otel" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-sttp" % "<version>"
libraryDependencies += "io.getkyo" %% "kyo-tapir" % "<version>"
```

For ScalaJS (applicable only to `kyo-core`, `kyo-direct`, and `kyo-sttp`):

```scala 
libraryDependencies += "io.getkyo" %%% "kyo-core" % "<version>"
libraryDependencies += "io.getkyo" %%% "kyo-direct" % "<version>"
libraryDependencies += "io.getkyo" %%% "kyo-sttp" % "<version>"
```

Replace `<version>` with the latest version: ![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3).


### The "Pending" type: `<`

In Kyo, computations are expressed via the infix type `<`, known as "Pending". It takes two type parameters:

1. The type of the expected output.
2. The pending effects that need to be handled, represented as an unordered type-level set via a type intersection.

```scala 
import kyo._

// 'Int' pending 'Options'
Int < Options

// 'String' pending 'Options' and 'IOs'
String < (Options & IOs)
```

> Note: The naming convention for effect types is the plural form of the functionalities they manage.

Any type `T` is automatically considered to be of type `T < Any`, where `Any` denotes an absence of pending effects. In simpler terms, this means that every value in Kyo is automatically a computation, but one without any effects that you need to handle. 

This design choice streamlines your code by removing the necessity to differentiate between pure values and computations that may have effects. So, when you're dealing with a value of type `T < Any`, you can safely extract the `pure` value directly, without worrying about handling any effects.

```scala
import kyo._

// An 'Int' is also an 'Int < Any'
val a: Int < Any = 1

// Since there are no pending effects, 
// the computation can produce a pure value
val b: Int = a.pure
```

> Note: This README provides explicit type declarations for clarity. However, Scala's type inference is generally able to infer Kyo types properly.

This unique property removes the need to juggle between `map` and `flatMap`. All values are automatically promoted to a Kyo computation with zero pending effects, enabling you to focus on your application logic rather than the intricacies of effect handling.

```scala
import kyo._

// Kyo still supports both `map` 
// and `flatMap`.
def example1(
    a: Int < Options, 
    b: Int < Aborts[Exception]
  ): Int < (Options & Aborts[Exception]) =
    a.flatMap(v => b.map(_ + v))

// But using only `map` is recommended 
// since it funcions like `flatMap` due 
// to effect widening.
def example2(
    a: Int < Options, 
    b: Int < Aborts[Exception]
  ): Int < (Options & Aborts[Exception]) =
    a.map(v => b.map(_ + v))
```

The `map` method automatically updates the set of pending effects. When you apply `map` to computations that have different pending effects, Kyo reconciles these into a new computation type that combines all the unique pending effects from both operands.

When a computation produces a `Unit` value, Kyo also offers an `andThen` method for more fluent code:

```scala
import kyo._

// An example computation that 
// produces 'Unit'.
val a: Unit < IOs = 
  IOs(println("hello"))

// Use 'andThen'.
val b: String < IOs =
  a.andThen("test")
```

### Effect widening

Kyo's set of pending effects is a contravariant type parameter. This encoding permits computations to be widened to encompass a larger set of effects.

```scala
import kyo._

// An 'Int' with an empty effect set (`Any`)
val a: Int < Any = 
  1

// Widening the effect set from empty (`Any`) 
// to include `Options`
val b: Int < Options = 
  a

// Further widening the effect set to include 
// both `Options` and `Aborts[Exception]`
val c: Int < (Options & Aborts[Exception]) = 
  b

// Directly widening a pure value to have 
// `Options` and `Aborts[Exception]`
val d: Int < (Options & Aborts[Exception]) = 
  42
```

This characteristic enables a fluent API for effectful code. Methods can accept parameters with a specific set of pending effects while also permitting those with fewer or no effects.

```scala
import kyo._

// The function expects a parameter with both 
// 'Options' and 'Aborts' effects pending
def example1(v: Int < (Options & Aborts[Exception])) = 
  v.map(_ + 1)

// A value with only the 'Aborts' effect can be 
// automatically widened to include 'Options'
def example2(v: Int < Aborts[Exception]) = 
  example1(v)

// A pure value can also be automatically widened
def example3 = example1(42)
```

Here, `example1` is designed to accept an `Int < (Options & Aborts[Exception])`. However, thanks to the contravariant encoding of the type-level set of effects, `example2` and `example3` demonstrate that you can also pass in computations with a smaller set of effects—or even a pure value—and they will be automatically widened to fit the expected type.

### Using effects

Effects follow a naming convention for common operations:

- `init*`: Initializes an instance of the container type handled by the effect. For instance, `Fibers.init` returns a new `Fiber`.
- `get*`: Allows the "extraction" of the value of the container type. `Fibers.get` returns a `T < Fibers` for a `Fiber[T]`.
- `run*`: Handles the effect.

Though named `run`, effect handling doesn't necessarily execute the computation immediately, as the effect handling itself can also be suspended if another effect is pending.

```scala
import kyo._

val a: Int < Options = 42

// Handle the 'Options' effect
val b: Option[Int] < Any = 
  Options.run(a)

// Retrieve pure value as there are no more pending effects
val c: Option[Int] = 
  b.pure

// Computations with no pending effects 
// (`Any`) provide only the `pure` method. 
// For example, this code fails to compile:
// val c: String < Any = a.map(_.toString)
```

The order in which you handle effects in Kyo can significantly influence both the type and value of the result. Since effects are unordered at the type level, the runtime behavior depends on the sequence in which effects are processed.

```scala
import kyo._
import scala.util._

def optionsFirst(a: Int < (Options & Aborts[Exception])): Either[Exception, Option[Int]] = {
  val b: Option[Int] < Aborts[Exception] = 
    Options.run(a)
  val c: Either[Exception, Option[Int]] < Any = 
    Aborts[Exception].run(b)
  c.pure
}
def abortsFirst(a: Int < (Options & Aborts[Exception])): Option[Either[Exception, Int]] = {
  val b: Either[Exception, Int] < Options =
    Aborts[Exception].run(a)
  val c: Option[Either[Exception, Int]] < Any = 
    Options.run(b)
  c.pure
}

// The sequence in which effects are handled has a significant impact on the outcome. 
// This is especially true for effects that can short-circuit the computation.

val ex = new Exception

// If the effects don't short-circuit, only the 
// order of nested types in the result changes
optionsFirst(Options.get(Some(1)))            // Right(Some(1))
optionsFirst(Aborts[Exception].get(Right(1))) // Right(Some(1))

// Note how the result type changes from 
// 'Try[Option[T]]' to 'Option[Try[T]]'
abortsFirst(Options.get(Some(1)))             // Some(Right(1))
abortsFirst(Aborts[Exception].get(Right(1)))  // Some(Right(1))

// If there's short-circuiting, the 
// resulting value can be different
optionsFirst(Options.get(None))               // Right(None)
optionsFirst(Aborts[Exception].get(Left(ex))) // Left(ex)

abortsFirst(Options.get(None))                // None
abortsFirst(Aborts[Exception].get(Left(ex)))  // Some(Left(ex))
```

### Direct Syntax

Kyo provides direct syntax for a more intuitive and concise way to express computations, especially when dealing with multiple effects. This syntax leverages two primary constructs: `defer` and `await`.

Essentially, `await` is a syntactic sugar for the `map` function, allowing developers to directly access values from computations without the need for repetitive `map` chaining. This makes the code more linear and intuitive.

```scala
import kyo._

// Use the direct syntax
val a: String < (Aborts[Exception] & Options) =
  defer {
    val b: String = 
      await(Options.get(Some("hello")))
    val c: String = 
      await(Aborts[Exception].get(Right("world")))
    b + " " + c
  }

// Equivalent desugared
val b: String < (Aborts[Exception] & Options) =
  Options.get(Some("hello")).map { b =>
    Aborts[Exception].get(Right("world")).map { c =>
      b + " " + c
    }
  }
```

The `defer` macro translates the `defer` and `await` constructs by virtualizing control flow. It modifies value definitions, conditional branches, loops, and pattern matching to express compurations in terms of `map`. 

For added safety, the direct syntax enforces effectful hygiene. Within a `defer` block, values of the `<` type must be enclosed by an `await` block. This approach ensures all effectful computations are explicitly processed, reducing the potential for missed effects or operation misalignment.

```scala 
import kyo._

// This code fails to compile
val a: Int < (IOs & Options) =
  defer {
    // Incorrect usage of a '<' value 
    // without 'await' 
    IOs(println(42))
    val c: Int = 
      await(Options.get(Some(1)))
    c + 10
  }
```

> Note: In the absence of effectful hygiene, the side effect `IOs(println(42))` would be overlooked and never executed. With the hygiene in place, such code results in a compilation error.

The syntac sugar supports a variety of constructs to handle effectful computations. These include pure expressions, value definitions, control flow statements like `if`-`else`, logical operations (`&&` and `||`), `while`, and pattern matching.

```scala
import kyo._

defer {
  // Pure expression
  val a: Int = 5
  
  // Effectful value
  val b: Int = await(IOs(10))
  
  // Control flow
  val c: String = 
    if (await(IOs(true))) "True branch" else "False branch"
  
  // Logical operations
  val d: Boolean = 
    await(IOs(true)) && await(IOs(false))
  
  val e: Boolean = 
    await(IOs(true)) || await(IOs(true))
  
  // Loop (for demonstration; this loop 
  // won't execute its body)
  while (await(IOs(false))) { "Looping" }
  
  // Pattern matching
  val matchResult: String = 
    await(IOs(1)) match {
      case 1 => "One"
      case _ => "Other"
    }
}
```

The `defer` method in Kyo mirrors Scala's `for`-comprehensions in providing a constrained yet expressive syntax. In `defer`, features like nested `defer` blocks, `var` declarations, `return` statements, `lazy val`, `lambda` and `def` with `await`, `try`/`catch` blocks, methods and constructors accepting by-name parameters, `throw` expressions, as well as `class`, `for`-comprehension, `trait`, and `object`s are disallowed. This design allows clear virtualization of control flow, eliminating potential ambiguities or unexpected results.

The `kyo-direct` module is constructed as a wrapper around [dotty-cps-async](https://github.com/rssh/dotty-cps-async).

> Note: `defer` is currently the only macro in Kyo. All other features use regular language constructs.

### Defining an App

`KyoApp` offers a structured approach similar to Scala's `App` for defining application entry points. However, it comes with added capabilities, handling a suite of default effects. As a result, the `run` method within `KyoApp` can accommodate various effects, such as IOs, Fibers, Resources, Clocks, Consoles, Randoms, Timers, and Aspects.

```scala
import kyo._

object MyApp extends KyoApp {
  // Use 'run' blocks to execute Kyo computations.
  // The execution of the run block is lazy to avoid
  // field initialization issues.
  run {
    for {
      _ <- Consoles.println(s"Main args: $args")
      currentTime <- Clocks.now
      _ <- Consoles.println(s"Current time is: $currentTime")
      randomNumber <- Randoms.nextInt(100)
      _ <- Consoles.println(s"Generated random number: $randomNumber")
    } yield {
      // The produced value can be of any type and is 
      // automatically printed to the console.
      "example"
    }
  }
}
```

While the companion object of `KyoApp` provides utility methods to run isolated effectful computations, it's crucial to approach these with caution. Direct handling of effects like `IOs` through these methods can compromise referential transparency, an essential property for functional programming.

```scala
import kyo._
import scala.concurrent.duration._

// An example computation
val a: Int < IOs =
  IOs(Math.cos(42).toInt)

// Avoid! Run the application with a specific timeout
val b: Int = 
  KyoApp.run(2.minutes)(a)

// Avoid! Run the application without specifying a timeout
val c: Int = 
  KyoApp.run(a)
```

## Core Effects

Kyo's core effects act as the essential building blocks that power your application's various functionalities. Unlike other libraries that might require heavy boilerplate or specialized knowledge, Kyo's core effects are designed to be straightforward and flexible. These core effects not only simplify the management of side-effects, dependencies, and several other aspects but also allow for a modular approach to building maintainable systems.

### Aborts: Short Circuiting

The `Aborts` effect is a generic implementation for short-circuiting effects. It's equivalent to ZIO's failure channel.

```scala
import kyo._

// The 'get' method "extracts" the value
// from an 'Either' (right projection)
val a: Int < Aborts[String] = 
  Aborts[String].get(Right(1))

// short-circuiting via 'Left'
val b: Int < Aborts[String] = 
  Aborts[String].get(Left("failed!"))

// short-circuiting via 'Fail'
val c: Int < Aborts[String] = 
  Aborts[String].fail("failed!")

// 'catching' automatically catches exceptions
val d: Int < Aborts[Exception] = 
  Aborts[Exception].catching(throw new Exception)
```

> Note that the `Aborts` effect has a type parameter and its methods can only be accessed if the type parameter is provided.

### IOs: Side Effects and Exception Handling

Kyo is unlike traditional effect systems since its base type `<` does not assume that the computation can perform side effects. The `IOs` effect is introduced whenever a side effect needs to be performed.

```scala
import kyo._
import scala.util._

def aSideEffect = 1 // placeholder

// 'apply' is used to suspend side effects
val a: Int < IOs = 
  IOs(aSideEffect)

// 'fail' be used for unchecked exceptions (prefer 'Aborts')
val b: Int < IOs =
  IOs.fail(new Exception)

// 'fail' can also take an error message instead
val c: Int < IOs =
  IOs.fail("exception message")

// 'fromTry' obtains the value of a 'Try'
val d: Int < IOs =
  IOs.fromTry(Try(1))

// 'attempt' handles any exceptions and returns a 'Try'
val e: Try[Int] < IOs =
  IOs.attempt("1".toInt)

// 'handle' takes a partial function to handle exceptions
val f: Int < IOs =
  IOs.handle("1".toInt) {
    case _: NumberFormatException => 0
  }
```

Users shouldn't typically handle the `IOs` effect directly since it triggers the execution of side effects, which breaks referential transparency. Prefer `KyoApp` instead.

In some specific cases where Kyo isn't used as the main effect system of an application, it might make sense for the user to handle the `IOs` effect directly. The `run` method can only be used if `IOs` is the only pending effect.

```scala
import kyo._

val a: Int < IOs = 
  IOs(42)

// ** Avoid 'IOs.run', use 'KyoApp' instead. **
val b: Int = 
  IOs.run(a).pure
// ** Avoid 'IOs.run', use 'KyoApp' instead. **
```

The `runLazy` method accepts computations with other effects but it doesn't guarantee that all side effects are performed before the method returns. If other effects still have to be handled, the side effects can be executed later once the other effects are handled. This a low-level API that must be used with caution.

```scala
import kyo._

// Computation with 'Options' and then 
// 'IOs' suspensions
val a: Int < (Options & IOs) = 
  Options.get(Some(42)).map { v => 
    IOs { 
      println(v)
      v
    }
  }

// ** Avoid 'IOs.runLazy', use 'KyoApp' instead. **
// Handle the 'IOs' effect lazily
val b: Int < Options = 
  IOs.runLazy(a)
// ** Avoid 'IOs.runLazy', use 'KyoApp' instead. **

// Since the computation is suspended with the 
// 'Options' effect first, the lazy 'IOs' execution 
// will be triggered once 'Options' is handled
val c: Option[Int] = 
  Options.run(b).pure
```

> IMPORTANT: Avoid handling the `IOs` effect directly since it breaks referential transparency. Use `KyoApp` instead.

### Envs: Dependency Injection

`Envs` is similar to ZIO's environment feature but offers more granular control. Unlike ZIO, which has built-in layering for dependencies, `Envs` allows you to inject individual services directly. However, it lacks ZIO's structured dependency management; you manage and initialize your services yourself.

```scala
import kyo._

// Given an interface
trait Database {
  def count: Int < IOs 
}

// The 'Envs' effect can be used to summon an instance.
// Note how the computation produces a 'Database' but at the
// same time requires a 'Database' from its environment
val a: Database < Envs[Database] = 
  Envs[Database].get

// Use the 'Database' to obtain the count
val b: Int < (Envs[Database] & IOs) = 
  a.map(_.count)

// A 'Database' mock implementation
val db = new Database { 
  def count = 1
}

// Handle the 'Envs' effect with the mock database
val c: Int < IOs = 
  Envs[Database].run(db)(b)

// Additionally, a computation can require multiple values 
// from its environment.

// A second interface to be injected
trait Cache {
  def clear: Unit < IOs
}

// A computation that requires two values
val d: Unit < (Envs[Database] & Envs[Cache] & IOs) = 
  Envs[Database].get.map { db =>
    db.count.map {
      case 0 => 
        Envs[Cache].get.map(_.clear)
      case _ => 
        ()
    }
  }
```

### Locals: Scoped Values

The `Locals` effect operates on top of `IOs` and enables the definition of scoped values. This mechanism is typically used to store contextual information of a computation. For example, in request processing, locals can be used to store information about the user who initiated the request. In a library for database access, locals can be used to propagate transactions.

```scala
import kyo._

// Locals need to be initialized with a default value
val myLocal: Local[Int] = 
  Locals.init(42)

// The 'get' method returns the current value of the local
val a: Int < IOs = 
  myLocal.get

// The 'let' method assigns a value to a local within the
// scope of a computation. This code produces 43 (42 + 1)
val b: Int < IOs =
  myLocal.let(42)(a.map(_ + 1))
```

> Note: Kyo's effects are designed so locals are properly propagated. For example, they're automatically inherited by forked computations in `Fibers`.

### Resources: Resource Safety

The `Resources` effect handles the safe use of external resources like network connections, files, and any other resource that needs to be freed once the computation finalizes. It serves as a mechanism similar to ZIO's `Scope`.

```scala
import kyo._
import java.io.Closeable

class Database extends Closeable {
  def count: Int < IOs = 42
  def close() = {}
}

// The `acquire` method accepts any object that 
// implements Java's `Closeable` interface
val db: Database < (Resources & IOs) = 
  Resources.acquire(new Database)

// Use `run` to handle the effect, while also 
// closing the resources utilized by the 
// computationation
val b: Int < IOs = 
  Resources.run(db.map(_.count))

// The `ensure` method provides a low-level API to handle the finalization of 
// resources directly. The `acquire` method is implemented in terms of `ensure`.

// Example method to execute a function on a database
def withDb[T](f: Database => T < IOs): T < (IOs & Resources) =
  // Initializes the database ('new Database' is a placeholder)
  IOs(new Database).map { db =>
    // Registers `db.close` to be finalized
    Resources.ensure(db.close).map { _ =>
      // Invokes the function
      f(db)
    }
  }

// Execute a function
val c: Int < (IOs & Resources) =
  withDb(_.count)

// Close resources
val d: Int < IOs = 
  Resources.run(c)
```

### Choices: Exploratory Branching

The `Choices` effect is designed to aid in handling and exploring multiple options, pathways, or outcomes in a computation. This effect is particularly useful in scenarios where you're dealing with decision trees, backtracking algorithms, or any situation that involves dynamically exploring multiple options.

```scala
import kyo._

// Evaluate each of the provided `Seq`s.
// Note how 'get' takes a 'Seq[T]'
// and returns a 'T < Choices'
val a: Int < Choices =
  Choices.get(Seq(1, 2, 3, 4))

// 'filter' discards the current element if 
// a condition is not met. Produces a 'Seq(1, 2)'
// since values greater than 2 are dropped
val b: Int < Choices =
  a.map(v => Choices.filter(v < 2).map(_ => v))

// 'drop' unconditionally discards the 
// current choice. Produces a 'Seq(42)'
// since only the value 1 is transformed
// to 42 and all other values are dropped
val c: Int < Choices = 
  b.map {
    case 1 => 42
    case _ => Choices.drop
  }

// Handle the effect to evaluate all elements 
// and return a 'Seq' with the results
val d: Seq[Int] < Any =
  Choices.run(c)
```

The `Choices` effect becomes exceptionally powerful when combined with other effects. This allows you not just to make decisions or explore options in isolation but also to do so in contexts that may involve factors such as asynchronicity, resource management, or even user interaction.

### Aspects: Aspect-Oriented Programming

The `Aspects` effect in Kyo allows for high-level customization of behavior across your application. This is similar to how some frameworks use aspects for centralized control over diverse functionalities like database timeouts, authentication, authorization, and transaction management. You can modify these core operations without altering their individual codebases, streamlining how centralized logic is applied across different parts of an application. This makes `Aspects` ideal for implementing cross-cutting concerns in a clean and efficient manner.

To instantiate an aspect, use the `Aspects.init` method. It takes three type parameters:

1. `T`: The input type of the aspect
2. `U`: The output type of the aspect
3. `S`: The effects the aspect may perform

```scala
import kyo._
import java.io.Closeable

class Database extends Closeable {
  def count: Int < IOs = 42
  def close() = {}
}

// Initialize an aspect that takes a 'Database' and returns
// an 'Int', potentially performing 'IOs' effects
val countAspect: Aspect[Database, Int, IOs] = 
  Aspects.init[Database, Int, IOs]

// The method 'apply' activates the aspect for a computation
def count(db: Database): Int < IOs =
  countAspect(db)(_.count)

// To bind an aspect to an implementation, first create a new 'Cut'
val countPlusOne =
  new Cut[Database, Int, IOs] {
    // The first param is the input of the computation and the second is
    // the computation being handled
    def apply[S](v: Database < S)(f: Database => Int < IOs) =
      v.map(db => f(db).map(_ + 1))
  }

// Bind the 'Cut' to a computation with the 'let' method.
// The first param is the 'Cut' and the second is the computation
// that will run with the custom binding of the aspect
def example(db: Database): Int < IOs =
  countAspect.let(countPlusOne) {
    count(db)
  }

// If an aspect is bound to multiple `Cut` implementations, the order of 
// their execution is determined by the sequence in which they are scoped 
// within the computation.

// Another 'Cut' implementation
val countTimesTen =
  new Cut[Database, Int, IOs] {
    def apply[S](v: Database < S)(f: Database => Int < IOs) =
      v.map(db => f(db).map(_ * 10))
  }

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

### Options: Optional Values

```scala
import kyo._

// 'get' is used to 'extract' the value of an 'Option'
val a: Int < Options = 
  Options.get(Some(1))

// 'apply' is the effectful version of 'Option.apply'
val b: Int < Options = 
  Options(1)

// If 'apply' receives a 'null', it becomes equivalent to 'Options.get(None)'
assert(Options.run(Options(null)).pure == Options.run(Options.get(None)).pure)

// Effectful version of `Option.getOrElse`
val c: Int < Options = 
  Options.getOrElse(None, 42)

// Effectful version of 'Option.orElse
val d: Int < Options = 
  Options.getOrElse(Some(1), c)
```

### Consoles: Console Interaction

```scala
import kyo._

// Read a line from the console
val a: String < Consoles = 
  Consoles.readln

// Print to stdout
val b: Unit < Consoles = 
  Consoles.print("ok")

// Print to stdout with a new line
val c: Unit < Consoles = 
  Consoles.println("ok")

// Print to stderr
val d: Unit < Consoles = 
  Consoles.printErr("fail")

// Print to stderr with a new line
val e: Unit < Consoles = 
  Consoles.printlnErr("fail")

// Handling the effect
val f: Unit < IOs =
  Consoles.run(e)

// Explicitly specifying the 'Console' implementation
val g: Unit < IOs = 
  Consoles.run(Console.default)(e)
```

### Clocks: Time Management

```scala
import kyo._
import java.time.Instant

// Obtain the current time
val a: Instant < IOs = 
  Clocks.now

// Run with an explicit 'Clock'
val c: Instant < IOs = 
  Clocks.let(Clock.default)(a)
```

### Randoms: Random Values

```scala
import kyo._

// Generate a random 'Int'
val a: Int < IOs = Randoms.nextInt

// Generate a random 'Int' within a bound
val b: Int < IOs = Randoms.nextInt(42)

// A few method variants
val c: Long < IOs = Randoms.nextLong
val d: Double < IOs = Randoms.nextDouble
val e: Boolean < IOs = Randoms.nextBoolean
val f: Float < IOs = Randoms.nextFloat
val g: Double < IOs = Randoms.nextGaussian

// Obtain a random value from a sequence
val h: Int < IOs = 
  Randoms.nextValue(List(1, 2, 3))

// Explicitly specify the `Random` implementation
val k: Int < IOs =
  Randoms.let(Random.default)(h)
```

### Logs: Logging

`Logs` is designed to streamline the logging process without requiring the instantiation of a `Logger`. By leveraging the [sourcecode](https://github.com/com-lihaoyi/sourcecode) library, log messages automatically include source code position information, enhancing the clarity and usefulness of the logs.

```scala 
import kyo._

// Logs provide trace, debug, info, 
// warn, and error method variants.
val a: Unit < IOs = 
  Logs.error("example")

// Each variant also has a method overload
// that takes a 'Throwable' as a second param
val d: Unit < IOs = 
  Logs.error("example", new Exception)
```

### Stats: Observability

`Stats` is a pluggable implementation that provides counters, histograms, gauges, and tracing. It uses Java's [service loading](https://docs.oracle.com/javase/8/docs/api/java/util/ServiceLoader.html) to locate exporters. 

The module [`kyo-stats-otel`](https://central.sonatype.com/artifact/io.getkyo/kyo-stats-otel_3) provides exporters for [OpenTelemetry](https://opentelemetry.io/).

```scala
import kyo._
import kyo.stats._

// Initialize a Stats instance
// for a scope path
val stats: Stats =
  Stats.initScope("my_application", "my_module")

// Initialize a counter
val a: Counter =
  stats.initCounter("my_counter")

// It's also possible to provide
// metadata when initializing
val b: Histogram =
  stats.initHistogram(
    name = "my_histogram",
    description = "some description",
    unit = "some unit",
    attributes = Attributes.add("key", "value")
  )

// Gauges take a by-name function to 
// be observed periodically
val c: Gauge =
  stats.initGauge("free_memory") {
    Runtime.getRuntime().freeMemory()
  }

// Ensure gauges are closed once 
// they're not needed to avoid leaks
val d: Unit < IOs =
  c.close
```

> Note: Although stats initialization may perform side effects, Kyo chooses to consider the operation pure since stats are meant to be initialized in a static scope for optimal performance.

Tracing can be performed via the `traceSpan` method. It automatically initializes the span and closes it at the end of the traced computation even in the presence of failures or asynchronous operations. Nested traces are bound to their parent span via `Locals`.

```scala
import kyo._

val stats2: Stats =
  Stats.initScope("my_application", "my_module")

// Some example computation
val a: Int < IOs =
  IOs(42)

// Trace the execution of the
// `a` example computation
val b: Int < IOs =
  stats2.traceSpan("my_span")(a)
```

## Concurrent Effects

The `kyo.concurrent` package provides utilities for dealing with concurrency in Scala applications. It's a powerful set of effects designed for easier asynchronous programming, built on top of other core functionalities provided by the `kyo` package.

### Fibers: Green Threads

The `Fibers` effect allows for the asynchronous execution of computations via a managed thread pool. The core function, `init`, spawns a new "green thread," also known as a fiber, to handle the given computation. This provides a powerful mechanism for parallel execution and efficient use of system resources. Moreover, fibers maintain proper propagation of `Locals`, ensuring that context information is carried along during the forking process.

```scala
import kyo._

// Fork a computation. The parameter is
// taken by reference and automatically
// suspended with 'IOs'
val a: Fiber[Int] < IOs =
  Fibers.init(Math.cos(42).toInt)

// It's possible to "extract" the value of a 
// 'Fiber' via the 'get' method. This is also
// referred as "joining the fiber"
val b: Int < Fibers =
  Fibers.get(a)
```

The `parallel` methods fork multiple computations in parallel, join the fibers, and return their results.

```scala
import kyo._

// An example computation
val a: Int < IOs =
  IOs(Math.cos(42).toInt)

// There are method overloadings for up to four
// parallel computations. Paramters taken by
// reference
val b: (Int, String) < Fibers =
  Fibers.parallel(a, "example")

// Alternatively, it's possible to provide
// a 'Seq' of computations and produce a 'Seq'
// with the results
val c: Seq[Int] < Fibers =
  Fibers.parallel(Seq(a, a.map(_ + 1)))

// The 'parallelFiber' method is similar but
// it doesn't automatically join the fibers and
// produces a 'Fiber[Seq[T]]'
val d: Fiber[Seq[Int]] < IOs =
  Fibers.parallelFiber(Seq(a, a.map(_ + 1)))
```

The `race` methods are similar to `parallel` but they return the first computation to complete with either a successful result or a failure. Once the first result is produced, the other computations are automatically interrupted.

```scala
import kyo._

// An example computation
val a: Int < IOs =
  IOs(Math.cos(42).toInt)

// There are method overloadings for up to four
// computations. Pameters taken by reference
val b: Int < Fibers =
  Fibers.race(a, a.map(_ + 1))

// It's also possible to to provide a 'Seq' 
// of computations 
val c: Int < Fibers =
  Fibers.race(Seq(a, a.map(_ + 1)))

// 'raceFiber' produces a 'Fiber' without
// joining it
val d: Fiber[Int] < IOs =
  Fibers.raceFiber(Seq(a, a.map(_ + 1)))
```

The `sleep` and `timeout` methods pause a computation or time it out after a duration.

```scala
import kyo._
import scala.concurrent.duration._

// A computation that sleeps for 1s
val a: Unit < Fibers =
  Fibers.sleep(1.second)

// Times out and interrupts the provided 
// computation in case it doesn't produce 
// a result within 1s
val b: Int < Fibers =
  Fibers.timeout(1.second)(Math.cos(42).toInt)
```

The `fromFuture` methods provide interoperability with Scala's `Future`.

```scala
import kyo._
import scala.concurrent.Future

// An example 'Future' instance
val a: Future[Int] = Future.successful(42)

// Join the result of a 'Future'
val b: Int < Fibers =
  Fibers.fromFuture(a)

// Use 'fromFutureFiber' to produce 'Fiber' 
// instead of joining the computation
val c: Fiber[Int] < IOs =
  Fibers.fromFutureFiber(a)
```

> Important: Keep in mind that Scala's Future lacks built-in support for interruption. As a result, any computations executed through Future will run to completion, even if they're involved in a race operation where another computation finishes first.

A `Fiber` instance also provides a few relevant methods.

```scala
import kyo._
import scala.util._
import scala.concurrent._

// An example fiber
val a: Fiber[Int] = Fiber.value(42)

// Check if the fiber is done
val b: Boolean < IOs =
  a.isDone

// Instance-level version of 'Fibers.get'
val c: Int < Fibers =
  a.get

// Avoid this low-level API to attach a 
// a callback to a fiber
val d: Unit < IOs =
  a.onComplete(println(_))

// A variant of `get` that returns a `Try`
// with the failed or successful result
val e: Try[Int] < Fibers =
  a.getTry

// Try to interrupt/cancel a fiber
val f: Boolean < IOs =
  a.interrupt

// Transforms a fiber into a Scala 'Future'
val h: Future[Int] < IOs =
  a.toFuture

// The 'transform' method is equivalent to `flatMap`
// in Scala's 'Future'
val i: Fiber[Int] < IOs =
  a.transform(v => Fiber.value(v + 1))
```

Similarly to `IOs`, users should avoid handling the `Fibers` effect directly and rely on `KyoApp` instead. If strictly necessary, there are two methods to handle the `Fibers` effect:

1. `run` takes a computation that has only the `Fibers` effect pending and returns a `Fiber` instance without blocking threads.
2. `runAndBlock` accepts computations with arbitrary pending effects but it handles asynchronous operations by blocking the current thread.

```scala
import kyo._
import scala.concurrent.duration._

// An example computation with fibers
val a: Int < Fibers =
  Fibers.init(Math.cos(42).toInt).map(_.get)

// Avoid handling 'Fibers' directly
// Note how the code has to handle the
// 'IOs' effect and then handle 'Fibers'
val b: Fiber[Int] < IOs =
  Fibers.run(IOs.runLazy(a))

// The 'runAndBlock' method accepts
// arbitrary pending effects but relies
// on thread blocking and requires a timeout
val c: Int < IOs =
  Fibers.runAndBlock(5.seconds)(a)
```

> Note: Handling the `Fibers` effect doesn't break referential transparency as with `IOs` but its usage is not trivial due to the limitations of the pending effects, especially `IOs`. Prefer `KyoApp` instead.

The `Fibers` effect also offers a low-level API to create `Promise`s as way to integrate external async operations with fibers. These APIs should be used only in low-level integration code.

```scala
import kyo._

// Initialize a promise
val a: Promise[Int] < IOs =
  Fibers.initPromise[Int]

// Try to fulfill a promise
val b: Boolean < IOs =
  a.map(_.complete(42))

// Fullfil the promise with 
// another fiber
val c: Boolean < IOs =
  a.map(fiber => Fibers.init(1).map(fiber.become(_)))
```

> A `Promise` is basically a `Fiber` with all the regular functionality plus the `complete` and `become` methods to manually fulfill the promise.

### Queues: Concurrent Queuing

The `Queues` effect operates atop of `IOs` and provides thread-safe queue data structures based on the high-performance [JCTools](https://github.com/JCTools/JCTools) library on the JVM. For ScalaJS, a simple `ArrayQueue` is used.

**Bounded queues**
```scala
import kyo._

// A bounded channel rejects new
// elements once full
val a: Queue[Int] < IOs =
  Queues.init(capacity = 42)

// Obtain the number of items in the queue
// via the method 'size' in 'Queue'
val b: Int < IOs =
  a.map(_.size)

// Get the queue capacity
val c: Int < IOs =
  a.map(_.capacity)

// Try to offer a new item
val d: Boolean < IOs =
  a.map(_.offer(42))

// Try to poll an item
val e: Option[Int] < IOs =
  a.map(_.poll)

// Try to 'peek' an item without removing it
val f: Option[Int] < IOs =
  a.map(_.peek)

// Check if the queue is empty
val g: Boolean < IOs =
  a.map(_.isEmpty)

// Check if the queue is full
val h: Boolean < IOs =
  a.map(_.isFull)

// Drain the queue items
val i: Seq[Int] < IOs =
  a.map(_.drain)

// Close the queue. If successful,
// returns a Some with the drained
// elements
val j: Option[Seq[Int]] < IOs =
  a.map(_.close)
```

**Unbounded queues**
```scala
import kyo._

// Avoid `Queues.unbounded` since if queues can 
// grow without limits, the GC overhead can make 
// the system fail
val a: Queues.Unbounded[Int] < IOs =
  Queues.initUnbounded()

// A 'dropping' queue discards new entries
// when full
val b: Queues.Unbounded[Int] < IOs =
  Queues.initDropping(capacity = 42)

// A 'sliding' queue discards the oldest
// entries if necessary to make space for new 
// entries
val c: Queues.Unbounded[Int] < IOs =
  Queues.initSliding(capacity = 42)

// Note how 'dropping' and 'sliding' queues
// return 'Queues.Unbounded`. It provides
// an additional method to 'add' new items
// unconditionally
val d: Unit < IOs =
  c.map(_.add(42))
```

**Concurrent access policies**

It's also possible to specify a concurrent `Access` policy as the second parameter of the `Queues.init` methods. This configuration has an effect only on the JVM and is ignored in ScalaJS.

| Policy | Full Form                           | Description |
|--------|-------------------------------------|-------------|
| Mpmc   | Multiple Producers, Multiple Consumers | Supports multiple threads/fibers simultaneously enqueuing and dequeuing elements. This is the most flexible but may incur the most overhead due to the need to synchronize between multiple producers and consumers. |
| Mpsc   | Multiple Producers, Single Consumer   | Allows multiple threads/fibers to enqueue elements but restricts dequeuing to a single consumer. This can be more efficient than `Mpmc` when only one consumer is needed. |
| Spmc   | Single Producer, Multiple Consumers   | Allows only a single thread/fiber to enqueue elements, but multiple threads/fibers can dequeue elements. Useful when only one source is generating elements to be processed by multiple consumers. |
| Spsc   | Single Producer, Single Consumer      | The most restrictive but potentially fastest policy. Only one thread/fiber can enqueue elements, and only one thread/fiber can dequeue elements. |

Each policy is suitable for different scenarios and comes with its own trade-offs. For example, `Mpmc` is highly flexible but can be slower due to the need for more complex synchronization. `Spsc`, being the most restrictive, allows for optimizations that could make it faster for specific single-producer, single-consumer scenarios.

You can specify the access policy when initializing a queue, and it is important to choose the one that aligns with your application's needs for optimal performance.

```scala
import kyo._

// Initialize a bounded queue with a 
// Multiple Producers, Multiple 
// Consumers policy
val a: Queue[Int] < IOs =
  Queues.init(
    capacity = 42, 
    access = Access.Mpmc
  )
```

### Channels: Backpressured Communication

The `Channels` effect serves as an advanced concurrency primitive, designed to facilitate seamless and backpressured data transfer between various parts of your application. Built upon the `Fibers` effect, `Channels` not only ensures thread-safe communication but also incorporates a backpressure mechanism. This mechanism temporarily suspends fibers under specific conditions—either when waiting for new items to arrive or when awaiting space to add new items.

```scala
import kyo._

// A 'Channel' is initialized
// with a fixed capacity
val a: Channel[Int] < IOs =
  Channels.init(capacity = 42)

// It's also possible to specify
// an 'Access' policy
val b: Channel[Int] < IOs =
  Channels.init(
    capacity = 42, 
    access = Access.Mpmc
  )
```

While `Channels` share similarities with `Queues`—such as methods for querying size (`size`), adding an item (`offer`), or retrieving an item (`poll`)—they go a step further by offering backpressure-sensitive methods, namely `put` and `take`.

```scala
import kyo._

// An example channel
val a: Channel[Int] < IOs =
  Channels.init(capacity = 42)

// Adds a new item to the channel.
// If there's no capacity, the fiber
// is automatically suspended until
// space is made available
val b: Unit < Fibers =
  a.map(_.put(42))

// Takes an item from the channel.
// If the channel is empty, the fiber
// is suspended until a new item is
// made available
val c: Int < Fibers =
  a.map(_.take)

// 'putFiber' returns a `Fiber` that
// will complete once the put completes
val d: Fiber[Unit] < IOs =
  a.map(_.putFiber(42))

// 'takeFiber' also returns a fiber
val e: Fiber[Int] < IOs =
  a.map(_.takeFiber)

// Closes the channel. If successful,
// returns a Some with the drained
// elements. All pending puts and takes
// are automatically interrupted
val f: Option[Seq[Int]] < IOs =
  a.map(_.close)
```

The ability to suspend fibers during `put` and `take` operations allows `Channels` to provide a more controlled form of concurrency. This is particularly beneficial for rate-sensitive or resource-intensive tasks where maintaining system balance is crucial.

> Important: While a `Channel` comes with a predefined item capacity, it's crucial to understand that there is no upper limit on the number of fibers that can be suspended by it. In scenarios where your application spawns an unrestricted number of fibers—such as an HTTP service where each incoming request initiates a new fiber—this can lead to significant memory consumption. The channel's internal queue for suspended fibers could grow indefinitely, making it a potential source of unbounded queuing and memory issues. Exercise caution in such use-cases to prevent resource exhaustion.

### Hubs: Broadcasting with Backpressure

`Hubs` provide a broadcasting mechanism where messages are sent to multiple listeners simultaneously. They are similar to `Channels`, but they are uniquely designed for scenarios involving multiple consumers. The key feature of `Hubs` is their ability to apply backpressure automatically. This means if the `Hub` and any of its listeners' buffers are full, the `Hub` will pause both the producers and consumers to prevent overwhelming the system. Unlike `Channels`, `Hubs` don't offer customization in concurrent access policy as they are inherently meant for multi-producer, multi-consumer environments.

```scala
import kyo._
import kyo.Hubs.Listener

// Initialize a Hub with a buffer
val a: Hub[Int] < IOs =
  Hubs.init[Int](3)

// Hubs provide APIs similar to
// channels: size, offer, isEmpty,
// isFull, putFiber, put
val b: Boolean < IOs =
  a.map(_.offer(1))

// But reading from hubs can only 
// happen via listener. Listeners
// only receive messages sent after
// their cration. To create call 
// `listen`:
val c: Listener[Int] < IOs =
  a.map(_.listen)

// Each listener can have an
// additional message buffer
val d: Listener[Int] < IOs =
  a.map(_.listen(bufferSize = 3))

// Listeners provide methods for
// receiving messages similar to
// channels: size, isEmpty, isFull,
// poll, takeFiber, take
val e: Int < Fibers =
  d.map(_.take)

// A listener can be closed
// individually. If successful,
// a Some with the backlog of 
// pending messages is returned
val f: Option[Seq[Int]] < IOs =
  d.map(_.close)

// If the Hub is closed, all
// listeners are automatically
// closed. The returned backlog
// only include items pending in
// the hub's buffer. The listener
// buffers are discarded
val g: Option[Seq[Int]] < IOs =
  a.map(_.close)
```

Hubs are implemented with an internal structure that efficiently manages message distribution. At their core, Hubs utilize a single channel for incoming messages. This central channel acts as the primary point for all incoming data. For each listener attached to a Hub, a separate channel is created. These individual channels are dedicated to each listener, ensuring that messages are distributed appropriately.

The functioning of Hubs is orchestrated by a dedicated fiber. This fiber continuously monitors the main incoming channel. Whenever a new message arrives, it takes this message and concurrently distributes it to all the listener channels. This process involves submitting the message to each listener's channel in parallel, ensuring simultaneous delivery of messages.

After distributing a message, the fiber waits until all the listener channels have successfully received it. This waiting mechanism is crucial for maintaining the integrity of message distribution, ensuring that each listener gets the message before the fiber proceeds to the next one and backpressure is properly applied.

### Meters: Computational Limits

The `Meters` effect offers utilities to regulate computational execution, be it limiting concurrency or managing rate. It is equipped with a range of pre-set limitations, including mutexes, semaphores, and rate limiters, allowing you to apply fine-grained control over task execution.

```scala
import kyo._
import scala.concurrent.duration._

// 'mutex': One computation at a time
val a: Meter < IOs = 
  Meters.initMutex

// 'semaphore': Limit concurrent tasks
val b: Meter < IOs =
  Meters.initSemaphore(concurrency = 42)

// 'rateLimiter': Tasks per time window
val c: Meter < IOs =
  Meters.initRateLimiter(
    rate = 10, 
    period = 1.second
  )

// 'pipeline': Combine multiple 'Meter's
val d: Meter < IOs =
  Meters.pipeline(a, b, c)
```

The `Meter` class comes with a handful of methods designed to provide insights into and control over computational execution.

```scala
import kyo._

// An example 'Meter'
val a: Meter < IOs = 
  Meters.initMutex

// Get available permits
val b: Int < IOs =
  a.map(_.available)

// Check for available permit
val c: Boolean < IOs =
  a.map(_.isAvailable)

// Use 'run' to execute tasks
// respecting meter limits
val d: Int < Fibers =
  a.map(_.run(Math.cos(42).toInt))

// 'tryRun' executes if a permit is
// available; returns 'None' otherwise
val e: Option[Int] < IOs =
  a.map(_.tryRun(Math.cos(42).toInt))
```

### Timers: Scheduled Execution

The `Timers` effect is designed for control over the timing of task execution.

```scala
import kyo._
import scala.concurrent.duration._

// An example computation to
// be scheduled
val a: Unit < IOs = 
  IOs(())

// Schedule a delayed task
val b: TimerTask < IOs =
  Timers.schedule(delay = 1.second)(a)

// Recurring task with
// intial delay
val c: TimerTask < IOs =
  Timers.scheduleAtFixedRate(
    initialDelay = 1.minute,
    period = 1.minute
  )(a)

// Recurring task without
// initial delay
val d: TimerTask < IOs =
  Timers.scheduleAtFixedRate(
    period = 1.minute
  )(a)

// Schedule with fixed delay between tasks
val e: TimerTask < IOs =
  Timers.scheduleWithFixedDelay(
    initialDelay = 1.minute,
    period = 1.minute
  )(a)

// without initial delay
val f: TimerTask < IOs =
  Timers.scheduleWithFixedDelay(
    period = 1.minute
  )(a)

// Specify the 'Timer' explictly
val i: TimerTask < IOs =
  Timers.let(Timer.default)(f)
```

`TimerTask` offers methods for more granular control over the scheduled tasks.

```scala
import kyo._
import scala.concurrent.duration._

// Example TimerTask
val a: TimerTask < IOs = 
  Timers.schedule(1.second)(())

// Try to cancel the task
val b: Boolean < IOs =
  a.map(_.cancel)

// Check if the task is cancelled
val c: Boolean < IOs =
  a.map(_.isCancelled)

// Check if the task is done
val d: Boolean < IOs =
  a.map(_.isDone)
```

### Latches: Fiber Coordination

The `Latches` effect serves as a coordination mechanism for fibers in a concurrent environment, primarily used for task synchronization. It provides a low-level API for controlling the flow of execution and ensuring certain tasks are completed before others, all while maintaining thread safety.

```scala
import kyo._

// Initialize a latch with 'n' permits
val a: Latch < IOs = 
  Latches.init(3)

// Await until the latch releases
val b: Unit < Fibers =
  a.map(_.await)

// Release a permit from the latch
val c: Unit < IOs =
  a.map(_.release)

// Get the number of pending permits
val d: Int < IOs =
  a.map(_.pending)
```

### Atomics: Concurrent State

The `Atomics` effect provides a set of thread-safe atomic variables to manage mutable state in a concurrent setting. Available atomic types include Int, Long, Boolean, and generic references.

```scala
import kyo._

// Initialize atomic variables
val aInt: AtomicInt < IOs = 
  Atomics.initInt(0)
val aLong: AtomicLong < IOs = 
  Atomics.initLong(0L)
val aBool: AtomicBoolean < IOs = 
  Atomics.initBoolean(false)
val aRef: AtomicRef[String] < IOs = 
  Atomics.initRef("initial")

// Fetch values
val b: Int < IOs = 
  aInt.map(_.get)
val c: Long < IOs = 
  aLong.map(_.get)
val d: Boolean < IOs = 
  aBool.map(_.get)
val e: String < IOs = 
  aRef.map(_.get)

// Update values
val f: Unit < IOs = 
  aInt.map(_.set(1))
val g: Unit < IOs = 
  aLong.map(_.lazySet(1L))
val h: Boolean < IOs = 
  aBool.map(_.cas(false, true))
val i: String < IOs = 
  aRef.map(_.getAndSet("new"))
```

### Adders: Concurrent Accumulation

The `Adders` effect offers thread-safe variables for efficiently accumulating numeric values. The two primary classes, `LongAdder` and `DoubleAdder`, are optimized for high-throughput scenarios where multiple threads update the same counter.

```scala
import kyo._

// Initialize Adders
val longAdder: LongAdder < IOs = 
  Adders.initLong
val doubleAdder: DoubleAdder < IOs = 
  Adders.initDouble

// Adding values
val a: Unit < IOs = 
  longAdder.map(_.add(10L))
val b: Unit < IOs = 
  doubleAdder.map(_.add(10.5))

// Increment and Decrement LongAdder
val c: Unit < IOs = 
  longAdder.map(_.increment)
val d: Unit < IOs = 
  longAdder.map(_.decrement)

// Fetch summed values
val e: Long < IOs = 
  longAdder.map(_.get)
val f: Double < IOs = 
  doubleAdder.map(_.get)

// Resetting the adders
val g: Unit < IOs = 
  longAdder.map(_.reset)
val h: Unit < IOs = 
  doubleAdder.map(_.reset)
```

## Integrations

### Caches: Memoized Functions via Caffeine

Kyo provides caching through memoization. A single `Cache` instance can be reused by multiple memoized functions. This allows for flexible scoping of caches, enabling users to use the same cache for various operations.

```scala
import kyo._

val a: Int < Fibers =
  for {

    // The initialization takes a 
    // builder function that mirrors
    // Caffeine's builder
    cache <- Caches.init(_.maxSize(100))

    // Create a memoized function
    fun = cache.memo { (v: String) =>
      // Note how the implementation
      // can use other effects
      IOs(v.toInt)
    }

    // Use the function
    v <- fun("10")
  } yield {
    v
  }
```

Although multiple memoized functions can reuse the same `Cache`, each function operates as an isolated cache and doesn't share any values with others. Internally, cache entries include the instance of the function as part of the key to ensure this separation. Only the cache space is shared, allowing for efficient use of resources without compromising the independence of each function's cache.

### Requests: HTTP Client via Sttp

`Requests` provides a simplified API for [Sttp 3](https://github.com/softwaremill/sttp) implemented on top of Kyo's concurrent package.

To perform a request, use the `apply` method. It takes a builder function based on Sttp's request building API.

```scala
import kyo._
import sttp.client3._
import kyo.Requests.Backend

// Perform a request using a builder function
val a: String < Requests =
  Requests[String](_.get(uri"https://httpbin.org/get"))

// Alternatively, requests can be 
// defined separately
val b: String < Requests =
  Requests.request[String](Requests.basicRequest.get(uri"https://httpbin.org/get"))

// It's possible to use the default implementation or provide 
// a custom `Backend` via `let`

// An example request
val c: String < Requests =
  Requests[String](_.get(uri"https://httpbin.org/get"))

// Handle the effect using the default backend
val d: String < Fibers =
  Requests.run(c)

// Implementing a custom mock backend
val backend: Backend =
  new Backend {
    def send[T](r: Request[T, Any]) = {
      Response.ok(Right("mocked")).asInstanceOf[Response[T]]
    }
  }

// Use the custom backend
val e: String < Fibers =
  Requests.run(backend)(a)
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
val c: NettyKyoServerBinding < Fibers =
  Routes.run(a.andThen(b))

// Alternatively, a customized server configuration can be used
val d: NettyKyoServerBinding < Fibers =
  Routes.run(NettyKyoServer().port(9999))(a.andThen(b))
```

The parameters for Tapir's endpoint type are aligned with Kyo effects as follows:

`Endpoint[SECURITY_INPUT, INPUT, ERROR_OUTPUT, OUTPUT, CAPABILITIES]`

This translates to the endpoint function format:

`INPUT => OUTPUT < (Envs[SECURITY_INPUT] & Aborts[ERROR_OUTPUT])`

Currently, the `CAPABILITIES` parameter is not supported in Kyo since streaming functionality is not available. An example of using these parameters is shown below:

```scala
import kyo.*
import sttp.tapir.*
import sttp.model.*

// An endpoint with an 'Int' path input and 'StatusCode' error output
val a: Unit < Routes =
  Routes.add(
    _.get.in("test" / path[Int]("id"))
      .errorOut(statusCode)
      .out(stringBody)
  ) { (id: Int) =>
    if(id == 42) "ok"
    else Aborts[StatusCode].fail(StatusCode.NotFound)
    // returns a 'String < Aborts[StatusCode]'
  }
```

For further examples, Kyo's [example ledger service](https://github.com/getkyo/kyo/tree/main/kyo-examples/jvm/src/main/scala/kyo/examples/ledger) provides practical applications of these concepts.

### AIs: LLM Abstractions via OpenAI

Coming soon..

## Restrictions

### Recursive Computations

Kyo evaluates pure computations strictly, without the need for suspensions or extra memory allocations. This approach enhances performance but requires careful handling of recursive computations to maintain stack safety.

```scala
import kyo._

// An example method that accepts
// a computation with arbitrary
// effects
def test[S](v: Int < S) = 
  v.map(_ + 1)

// If the input has no pending effects,
// `S` is inferred  to `Any` and the
// value is evaluated immediatelly 
// to 43
val a: Int < Any =
  test(42)

// Although users need to call `pure`
// to obtain an `Int`, the value is 
// represented internally as `T` without 
// the need for extra boxing
println(a) 
// prints 43

// But if there are pending effects,
// a suspended computation is produced
println(test(IOs(a)))
// prints kyo.ios$IOs$$anon$2@6cd8737
```

Given this characteristic, recursive computations need to introduce an effect suspension, like `IOs`, to ensure the evaluation is stack safe.

```scala
import kyo._

// AVOID! An unsafe recursive computation
def unsafeLoop[S](n: Int < S): Unit < S =
  n.map {
    case 0 => ()
    case n => unsafeLoop(n - 1)
  }

// Introduce an effect suspension to
// ensure stack safety
def safeLoop[S](n: Int < S): Unit < (S & IOs) =
  IOs {
    n.map {
      case 0 => ()
      case n => safeLoop(n - 1)
    }
  }
```

In the `safeLoop` function, the use of `IOs` suspends each recursive call, preventing the stack from overflowing. This technique is essential for safely handling recursive computations in Kyo.

### Nested Effects

In addition recursion, Kyo's unboxed representation of computations in certain scenarios introduces a restriction where it's not possible to handle effects of computations with nested effects like `Int < IOs < IOs`.

```scala
import kyo._

// An example computation with
// nested effects
val a: Int < IOs < Options = 
  Options.get(Some(IOs(1)))

// Can't handle a effects of a
// computation with nested effects

// Options.run(a)
// Compilation failure:
//   Method doesn't accept nested Kyo computations.
//   Detected: 'scala.Int < kyo.ios.IOs < kyo.options.Options'. Consider using 'flatten' to resolve.

// Use `flatten` before handling
Options.run(a.flatten)
```

Kyo performs checks at compilation time to ensure that nested effects are not used. This includes generic methods where the type system cannot confirm whether the computation is nested:

```scala 
import kyo._

// def test[T](v: T < Options) =
//   Options.run(v)
// Compilation failure:
//   Method doesn't accept nested Kyo computations.
//   Cannot prove 'T' isn't nested. Provide an implicit evidence 'kyo.Flat[T]'.

// It's possible to provide an implicit
// evidence of `Flat` to resolve
def test[T](v: T < Options)(implicit f: Flat[T]) =
  Options.run(v)
```

All APIs that trigger effect handling have this restriction, which includes not only methods that handle effects directly but also methods that use effect handling internally. For example, `Fibers.init` handles effects internally and doesn't allow nested effects.

```scala
import kyo._

// An example nested computation
val a: Int < IOs < IOs = 
  IOs(IOs(1))

// Fails to compile:
// Fibers.init(a)
```

The compile-time checking mechanism can also be triggered in scenarios where Scala's type inference artificially introduces nesting due to a mismatch between the effects suported by a method and the provided input. In this scenario, the error message contains an additional observation regarding this possibility. For example, `Fibers.init` only accepts computations with `Fibers` pending.

```scala
import kyo._

// Example computation with a
// mismatching effect (Options)
val a: Int < Options = 
  Options.get(Some(1))

// Fibers.init(a)
// Compilation failure:
//   Method doesn't accept nested Kyo computations.
//   Detected: 'scala.Int < kyo.options.Options < kyo.concurrent.fibers.Fibers'. Consider using 'flatten' to resolve. 
//   Possible pending effects mismatch: Expected 'kyo.concurrent.fibers.Fibers', found 'kyo.options.Options'.
```

## Acknowledgements

Kyo's development was originally inspired by the paper ["Do Be Do Be Do"](https://arxiv.org/pdf/1611.09259.pdf) and its implementation in the [Unison](https://www.unison-lang.org/learn/language-reference/abilities-and-ability-handlers/) programming language. Kyo's design evolved from using interface-based effects to suspending concrete values associated with specific effects, making it more efficient when executed on the JVM.

Additionally, Kyo draws inspiration from [ZIO](https://zio.dev/) in various aspects. The core mechanism for algebraic effects can be seen as a generalization of ZIO's effect rotation, and many of Kyo's effects are directly influenced by ZIO's mature set of primitives. For instance, `Envs` and `Aborts` correspond to ZIO's effect channels, `Resources` function similarly to `Scope`, and `Hubs` was introduced based on ZIO.

Kyo's asynchronous primitives take several aspects from [Twitter's util](https://github.com/twitter/util) and [Finagle](https://github.com/twitter/finagle), including features like async root compression, to provide stack safety, and support for cancellations (interruptions in Kyo).

Lastly, the name "Kyo" is derived from the last character of Nam-myoho-renge-kyo, the mantra practiced in [SGI Buddhism](https://www.sokaglobal.org/). It literally translates to "Sutra," referring to a compiled teaching of Shakyamuni Buddha, and is also interpreted as the "threads" that weave the fundamental fabric of life's reality.

License
-------

See the [LICENSE](https://github.com/getkyo/kyo/blob/master/LICENSE.txt) file for details.
 
