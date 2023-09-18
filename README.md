![kyo](https://raw.githubusercontent.com/getkyo/kyo/master/kyo.png)
-------------------

![Build Status](https://github.com/getkyo/kyo/workflows/build/badge.svg)
![Chat](https://img.shields.io/discord/1087005439859904574)
<!---![Version](https://img.shields.io/maven-central/v/io.getkyo/kyo-core_3)--->

### This readme is a working in progress. If you'd like to try Kyo, please build from source.

Kyo is a complete toolkit for Scala development, spanning from browser-based apps in ScalaJS to high-performance backends on the JVM. It introduces a novel approach based on algebraic effects to deliver straightforward APIs in the pure Functional Programming paradigm. Unlike similar solutions, Kyo achieves this without inundating developers with esoteric concepts from Category Theory or using cryptic symbolic operators, resulting in a development experience that is both intuitive and robust.

Drawing inspiration from [ZIO](https://zio.dev/)'s [effect rotation](https://degoes.net/articles/rotating-effects), Kyo takes a more generalized approach. While ZIO restricts effects to two channels, dependency injection and short-circuiting, Kyo allows for an arbitrary number of effectful channels. This enhancement gives developers greater flexibility in effect management, while also simplifying Kyo's internal codebase through more principled design patterns.

## The `>` type

In Kyo, computations are expressed via the infix type `>`, which takes two parameters:

1. The type of the expected output.
2. The pending effects that need to be handled, represented as an unordered type-level set via a type intersection.

```scala 
import kyo._

// Expect an 'Int' after handling 
// the 'Options' effect
Int > Options

// Expect a 'String' after handling 
// both 'Options' and 'IOs' effects
String > (Options with IOs)
```

> Note: The naming convention for effect types is the plural form of the functionalities they manage.

In Kyo, any type `T` is automatically considered to be of type `T > Any`, where `Any` denotes an absence of pending effects. In simpler terms, this means that every value in Kyo is automatically a computation, but one without any effects that you need to handle. 

This design choice streamlines your code by removing the necessity to differentiate between pure values and computations that may have effects. So, when you're dealing with a value of type `T > Any`, you can safely extract the `pure` value directly, without worrying about handling any side-effects.

```scala
import kyo._

// An 'Int' is also an 'Int > Any'
val a: Int > Any = 1

// Since there are no pending effects, 
// the computation can produce a pure value
val b: Int = a.pure
```

This unique property removes the need to juggle between `map` and `flatMap`. All values are automatically promoted to a Kyo computation with zero pending effects, enabling you to focus on your application logic rather than the intricacies of effect handling.

```scala
import kyo.options._
import kyo.tries._

// Kyo still supports both `map` and `flatMap`
def example1(a: Int > Options, b: Int > Tries): Int > (Options with Tries) =
  a.flatMap(v => b.map(_ + v))

// but using only `map` is recommended 
def example2(a: Int > Options, b: Int > Tries): Int > (Options with Tries) =
  a.map(v => b.map(_ + v))
```

The `map` method automatically updates the set of pending effects. When you apply `map` to computations that have different pending effects, Kyo reconciles these into a new computation type that combines all the unique pending effects from both operands.

## Effect widening

Kyo's set of pending effects is a contravariant type parameter. This encoding permits computations to be widened to encompass a larger set of effects.

```scala
// An 'Int' with an empty effect set (`Any`)
val a: Int > Any = 
  1

// Widening the effect set from empty (`Any`) 
// to include `Options`
val b: Int > Options = 
  a

// Further widening the effect set to include 
// both `Options` and `Tries`
val c: Int > (Options with Tries) = 
  b

// Directly widening a pure value to have 
// `Options` and `Tries`
val d: Int > (Options with Tries) = 
  42
```

This characteristic enables a fluent API for effectful code. Methods can accept parameters with a specific set of pending effects while also permitting those with fewer or no effects.

```scala
// The function expects a parameter with both 
// 'Options' and 'Tries' effects pending
def example1(v: Int > (Options with Tries)) = 
  v.map(_ + 1)

// A value with only the 'Tries' effect can be 
// automatically widened to include 'Options'
def example2(v: Int > Tries) = 
  example1(v)

// A pure value can also be automatically widened
def example3 = example1(42)
```

Here, `example1` is designed to accept an `Int > (Options with Tries)`. However, thanks to the contravariant encoding of the type-level set of effects, `example2` and `example3` demonstrate that you can also pass in computations with a smaller set of effects—or even a pure value—and they will be automatically widened to fit the expected type.

## Using effects

Kyo offers a modular approach to effect management, accommodating both built-in and user-defined effects organized in `object` modules. Importing the corresponding module into scope brings in the effect and any additional types or implicits it may need. The naming convention uses lowercase module names for each effect type.

```scala
// for 'Options' effect
import kyo.options._
// for 'Tries' effect
import kyo.tries._ 
```

For effects that support it, a `get` method is provided, which permits the "extraction" of the underlying value from a container type.

```scala
// Retrieve an 'Int' tagged with 'Options'
val a: Int > Options = 
  Options.get(Some(1))
```

Effect handling is done using the `run*` methods. Though named `run`, the operation doesn't necessarily execute the computation immediately, as the effect handling can also be suspended if another effect is pending.

```scala
// Handle the 'Options' effect
val b: Option[Int] > Any = 
  Options.run(a)

// Retrieve pure value as there are no more pending effects
val c: Option[Int] = 
  b.pure
```

The order in which you handle effects in Kyo can significantly influence both the type and value of the result. Since effects are unordered at the type level, the runtime behavior depends on the sequence in which effects are processed.

```scala
import scala.util._

def optionsFirst(a: Int > (Options with Tries)): Try[Option[Int]] = {
  val b: Option[Int] > Tries = 
    Options.run(a)
  val c: Try[Option[Int]] > Any = 
    Tries.run(b)
  c.pure
}
def triesFirst(a: Int > (Options with Tries)): Option[Try[Int]] = {
  val b: Try[Int] > Options =
    Tries.run(a)
  val c: Option[Try[Int]] > Any = 
    Options.run(b)
  c.pure
}
```

In this example, the sequence in which effects are handled has a significant impact on the outcome. This is especially true for effects that can short-circuit the computation.

```scala
val ex = new Exception

// If the effects don't short-circuit, only the 
// order of nested types in the result changes
assert(optionsFirst(Options.get(Some(1))) == Success(Some(1)))
assert(optionsFirst(Tries.get(Success(1))) == Success(Some(1)))

// Note how the result type changes from 
// 'Try[Option[T]]' to 'Option[Try[T]]'
assert(triesFirst(Options.get(Some(1))) == Some(Success(1)))
assert(triesFirst(Tries.get(Success(1))) == Some(Success(1)))

// If there's short-circuiting, the 
// resulting value can be different
assert(optionsFirst(Options.get(None)) == Success(None))
assert(optionsFirst(Tries.get(Failure(ex))) == Failure(ex))

assert(triesFirst(Options.get(None)) == None)
assert(triesFirst(Tries.get(Failure(ex))) == Some(Failure(ex)))
```

## Core Effects

Kyo's core effects act as the essential building blocks that power your application's various functionalities. Unlike other libraries that might require heavy boilerplate or specialized knowledge, Kyo's core effects are designed to be straightforward and flexible. These core effects not only simplify the management of side-effects, dependencies, and several other aspects but also allow for a modular approach to building maintainable systems.

### Aborts: Short Circuiting

The `Aborts` effect is a generic implementation for short-circuiting effects. It's equivalent to ZIO's failure channel.

```scala
import kyo.aborts._

// The 'get' method "extracts" the value
// from an 'Either' (right projection)
val a: Int > Aborts[String] = 
  Aborts[String].get(Right(1))

// short-circuiting via 'Left'
val b: Int > Aborts[String] = 
  Aborts[String].get(Left("failed!"))

// short-circuiting via 'Fail'
val c: Int > Aborts[String] = 
  Aborts[String].fail("failed!")

// 'catching' automatically catches exceptions
val d: Int > Aborts[Exception] = 
  Aborts[Exception].catching(throw new Exception)
```

> Note that the `Aborts` effect has a type parameter and its methods can only be accessed if the type parameter is provided.

### IOs: Side Effects

Kyo is unlike traditional effect systems since its base type `>` does not assume that the computation can perform side effects. The `IOs` effect is introduced whenever a side effect needs to be performed.

```scala
import kyo.ios._

// 'apply' is used to suspend side effects
val a: Int > IOs = 
  IOs(Random.nextInt)

// 'value' is a shorthand to widen 
// a pure value to IOs
val b: Int > IOs = 
  IOs.value(42)

// 'fail' returns a computation that 
// will fail once IOs is handled
val c: Int > IOs = 
  IOs.fail(new Exception)
```

> Note: Kyo's effects and public APIs are designed so any side effect is properly suspended via `IOs`, providing safe building blocks for pure computations.

Users shouldn't typically handle the `IOs` effect directly since it triggers the execution of side effects, which breaks referential transparency. Prefer `kyo.App` instead.

In some specific cases where Kyo isn't used as the main effect system of an application, it might make sense for the user to handle the `IOs` effect directly. The `run` method can only be used if `IOs` is the only pending effect.

```scala
val a: Int > IOs = 
  IOs(42)

// ** Avoid 'IOs.run', use 'kyo.App' instead) **
val b: Int = 
  IOs.run(a).pure
// ** Avoid 'IOs.run', use 'kyo.App' instead) **
```

The `runLazy` method accepts computations with other effects but it doesn't guarantee that all side effects are performed before the method returns. If other effects still have to be handled, the side effects can be executed later once the other effects are handled. This a low-level API that must be used with caution.

```scala
// Computation with 'Options' and then 
// 'IOs' suspensions
val a: Int > (Options with IOs) = 
  Options.get(Some(42)).map { v => 
    IOs { 
      println(v)
      v
    }
  }

// ** Avoid 'IOs.runLazy', use 'kyo.App' instead) **
// Handle the 'IOs' effect lazily
val b: Int > Options = 
  IOs.runLazy(a)
// ** Avoid 'IOs.runLazy', use 'kyo.App' instead) **

// Since the computation is suspended with the 
// 'Options' effect first, the lazy 'IOs' execution 
// will be triggered once 'Options' is handled
val c: Option[Int] = 
  Options.run(b).pure
```

> IMPORTANT: Avoid handling the `IOs` effect directly since it breaks referential transparency. Use `kyo.App` instead.

### Envs: Dependency Injection

`Envs` is similar to ZIO's environment feature but offers more granular control. Unlike ZIO, which has built-in layering for dependencies, `Envs` allows you to inject individual services directly. However, it lacks ZIO's structured dependency management; you manage and initialize your services yourself.

```scala
import kyo.envs._

// Given an interface
trait Database {
  def count: Int > IOs 
}

// The 'Envs' effect can be used to summon an instance.
// Note how the computation produces a 'Database' but at the
// same time requires a 'Database' from its environment
val a: Database > Envs[Database] = 
  Envs[Database].get

// Use the 'Database' to obtain the count
val b: Int > (Envs[Database] with IOs) = 
  a.map(_.count)

// A 'Database' mock implementation
val db = new Database { 
  def count = 1
}

// Handle the 'Envs' effect with the mock database
val c: Int > IOs = 
  Envs[Database].run(db)(b)
```

Additionally, a computation can require multiple values from its environment.

```scala
// A second interface to be injected
trait Cache {
  def clear: Unit > IOs
}

// A computation that requires two values
val a: Unit > (Envs[Database] with Envs[Cache] with IOs) = 
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
import kyo.locals._

// Locals need to be initialized with a default value
val myLocal: Local[Int] = 
  Locals.init(42)

// The 'get' method returns the current value of the local
val a: Int > IOs = 
  myLocal.get

// The 'let' method assigns a value to a local within the
// scope of a computation. This code produces 43 (42 + 1)
val b: Int > IOs =
  myLocal.let(42)(a.map(_ + 1))
```

> Note: Kyo's effects are designed so locals are properly propagated. For example, they're automatically inherited by forked computations in `Fibers`.

### Resources: Resource Safety

The `Resources` effect handles the safe use of external resources like network connections, files, and any other resource that needs to be freed once the computation finalizes. It serves as a mechanism similar to ZIO's `Scope`.

```scala
import kyo.resources._
import java.io.Closeable

class Database extends Closeable {
  def count: Int > IOs = 42
  def close() = {}
}

// The `acquire` method accepts any object that 
// implements Java's `Closeable` interface
val db: Database > (Resources with IOs) = 
  Resources.acquire(new Database)

// Use `run` to handle the effect, while also 
// closing the resources utilized by the 
// computationation
val b: Int > IOs = 
  Resources.run(db.map(_.count))
```

The `ensure` method provides a low-level API to handle the finalization of resources directly. The `acquire` method is implemented in terms of `ensure`.

```scala
// Example method to execute a function on a database
def withDb[T](f: Database => T > IOs): T > (IOs with Resources) =
  // Initializes the database ('new Database' is a placeholder)
  IOs(new Database).map { db =>
    // Registers `db.close` to be finalized
    Resources.ensure(db.close).map { _ =>
      // Invokes the function
      f(db)
    }
  }

// Execute a function
val a: Int > (IOs with Resources) =
  withDb(_.count)

// Close resources
val b: Int > IOs = 
  Resources.run(a)
```

### Choices: Exploratory Branching

The `Choices` effect is designed to aid in handling and exploring multiple options, pathways, or outcomes in a computation. This effect is particularly useful in scenarios where you're dealing with decision trees, backtracking algorithms, or any situation that involves dynamically exploring multiple options.

```scala
import kyo.choices._

// Evaluate each of the provided choices.
// Note how 'foreach' takes a 'List[T]'
// and returns a 'T > Choices'
val a: Int > Choices =
  Choices.foreach(List(1, 2, 3, 4))

// 'dropIf' discards the current choice if 
// a condition is not met. Produces a 'List(1, 2)'
// since values greater than 2 are dropped
val b: Int > Choices =
  a.map(v => Choices.dropIf(v > 2).map(_ => v))

// 'drop' unconditionally discards the 
// current choice. Produces a 'List(42)'
// since only the value 1 is transformed
// to 42 and all other values are dropped
val c: Int > Choices = 
  b.map {
    case 1 => 42
    case _ => Choices.drop
  }

// Handle the effect to evaluate all choices 
// and return a 'List' with the results
val d: List[Int] > Any =
  Choices.run(c)
```

The Choices effect becomes exceptionally powerful when combined with other effects. This allows you not just to make decisions or explore options in isolation but also to do so in contexts that may involve factors such as asynchronicity, resource management, or even user interaction.

### Aspects: Aspect-Oriented Programming (AOP)

The `Aspects` effect in Kyo allows for high-level customization of behavior across your application. This is similar to how some frameworks use aspects for centralized control over diverse functionalities like database timeouts, authentication, authorization, and transaction management. You can modify these core operations without altering their individual codebases, streamlining how centralized logic is applied across different parts of an application. This makes `Aspects` ideal for implementing cross-cutting concerns in a clean and efficient manner.

To instantiate an aspect, use the `Aspects.init` method. It takes three type parameters:

1. `T`: The input type of the aspect
2. `U`: The output type of the aspect
3. `S`: The effects the aspect may perform

```scala
import kyo.aspects._

// Initialize an aspect that takes a 'Database' and returns
// an 'Int', potentially performing 'IOs' effects
val countAspect: Aspect[Database, Int, IOs] = 
  Aspects.init[Database, Int, IOs]

// The method 'apply' activates the aspect for a computation
def count(db: Database): Int > (Aspects with IOs) =
  countAspect(db)(_.count)

// To bind an aspect to an implementation, first create a new 'Cut'
val countPlusOne =
  new Cut[Database, Int, IOs] {
    // The first param is the input of the computation and the second is
    // the computation being handled
    def apply[S](v: Database > S)(f: Database => Int > (Aspects with IOs)) =
      v.map(db => f(db).map(_ + 1))
  }

// Bind the 'Cut' to a computation with the 'let' method.
// The first param is the 'Cut' and the second is the computation
// that will run with the custom binding of the aspect
def example(db: Database): Int > (Aspects with IOs) =
  countAspect.let(countPlusOne) {
    count(db)
  }
```

If an aspect is bound to multiple `Cut` implementations, the order of their execution is determined by the sequence in which they are scoped within the computation.

```scala
// Another 'Cut' implementation
val countTimesTen =
  new Cut[Database, Int, IOs] {
    def apply[S](v: Database > S)(f: Database => Int > (Aspects with IOs)) =
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
import kyo.options._

// 'get' is used to 'extract' the value of an 'Option'
val a: Int > Options = 
  Options.get(Some(1))

// 'apply' is the effectful version of 'Option.apply'
val b: Int > Options = 
  Options(1)

// If 'apply' receives a 'null', it becomes equivalent to 'Options.get(None)'
assert(Options.run(Options(null)) == Options.run(Options.get(None)))

// Effectful version of `Option.getOrElse`
val c: Int > Options = 
  Options.getOrElse(None, 42)

// Effectful version of 'Option.orElse
val d: Int > Options = 
  Options.getOrElse(Some(1), c)
```

### Tries: Exception Handling

```scala
import kyo.tries._

// 'get' is used to 'extract' the value of a 'Try'
val a: Int > Tries = 
  Tries.get(Try(1))

// 'fail' to short-circuit the computation
val b: Int > Tries = 
  Tries.fail(new Exception)

// 'fail' has an overload that takes an error message
val c: Int > Tries = 
  Tries.fail("failed")

// 'apply' is the effectful version of 'Try.apply'
val d: Int > Tries = 
  Tries(1)

// The 'apply' method automatically catches exceptions
val e: Int > Tries = 
  Tries(throw new Exception)
```

### Consoles: Console Interaction

```scala
import kyo.consoles._

// Read a line from the console
val a: String > Consoles = 
  Consoles.readln

// Print to stdout
val b: Unit > Consoles = 
  Consoles.print("ok")

// Print to stdout with a new line
val c: Unit > Consoles = 
  Consoles.println("ok")

// Print to stderr
val d: Unit > Consoles = 
  Consoles.printErr("fail")

// Print to stderr with a new line
val e: Unit > Consoles = 
  Consoles.printlnErr("fail")

// Run with the default implicit 'Console' implementation
val f: Unit > IOs = 
  Consoles.run(e)

// Explicitly specifying the 'Console' implementation
val g: Unit > IOs = 
  Consoles.run(Console.default)(e)
```

> Note how `Consoles.run` returns a computation with the `IOs` effect pending, which ensures the implementation of `Consoles` is pure.

### Clocks: Time Management

```scala
import kyo.clocks._
import java.time.Instant

// Obtain the current time
val a: Instant > Clocks = 
  Clocks.now

// Run with default 'Clock'
val b: Instant > IOs = 
  Clocks.run(a)

// Run with an explicit 'Clock'
val c: Instant > IOs = 
  Clocks.run(Clock.default)(a)
```

### Randoms: Random Values

```scala
import kyo.randoms._

// Generate a random 'Int'
val a: Int > Randoms = Randoms.nextInt

// Generate a random 'Int' within a bound
val b: Int > Randoms = Randoms.nextInt(42)

// A few method variants
val c: Long > Randoms = Randoms.nextLong
val d: Double > Randoms = Randoms.nextDouble
val e: Boolean > Randoms = Randoms.nextBoolean
val f: Float > Randoms = Randoms.nextFloat
val g: Double > Randoms = Randoms.nextGaussian

// Obtain a random value from a sequence
val h: Int > Randoms = 
  Randoms.nextValue(List(1, 2, 3))

// Handle the 'Randoms' effect with the
// default implicit `Random` implementation
val j: Int > IOs =
  Randoms.run(h)

// Explicitly specify the `Random` implementation
val k: Int > IOs =
  Randoms.run(Random.default)(h)
```

### Loggers: Logging

```scala
import kyo.loggers._

// Initialize a 'Logger' instance
val a: Logger = 
  Loggers.init("exampleLog")

// It's also possible to specify a class
val b: Logger =
  Loggers.init(this.getClass)

// A 'Logger' provides trace, debug, info, 
// warn, and error method variants. Example:
val c: Unit > IOs = 
  b.error("example")

// Each variant also has a method overload
// that takes a 'Throwable' as a second param
val d: Unit > IOs = 
  b.error("example", new Exception)
```

> Important: The `Loggers` effect chooses to consider the initialization of a Logger instance as pure, even though it may perform side effects. For optimal performance, `Logger` instances should be stored in constant fields, a goal that would be challenging to achieve if `Loggers.init` required an `IOs` suspension.

## Concurrent Effects

The `kyo.concurrent` package provides utilities for dealing with concurrency in Scala applications. It's a powerful set of effects designed for easier asynchronous programming, built on top of other core functionalities provided by the `kyo` package.

### Fibers: Green Threads

The `Fibers` effect allows for the asynchronous execution of computations via a managed thread pool. The core function, `forkFiber`, spawns a new "green thread," also known as a fiber, to handle the given computation. This provides a powerful mechanism for parallel execution and efficient use of system resources. Moreover, fibers maintain proper propagation of `Locals`, ensuring that context information is carried along during the forking process.

```scala
import kyo.concurrent.fibers._

// Fork a computation
val a: Fiber[Int] > (Fibers with IOs) =
  Fibers.forkFiber(Math.cos(42).toInt)

// It's possible to "extract" the value of a 
// 'Fiber' via the 'get' method. This is also
// referred as "joining the fiber"
val b: Int > (Fibers with IOs) =
  Fibers.get(a)

// Alternatively, the 'fork' method is a shorthand 
// to fork the computation and join the fiber
val c: Int > (Fibers with IOs) =
  Fibers.fork(Math.cos(42).toInt)

// The 'value' method provides a 'Fiber' instance
// fulfilled with the provided pure value
val d: Fiber[Int] =
  Fibers.value(42)

// Use 'fail' to create a fiber that will fail
// with the provided exception
val e: Fiber[Int] =
  Fibers.fail(new Exception)
```

The `parallel` methods fork multiple computations in parallel, join the fibers, and return their results.

```scala
// There are method overloadings for up to four
// parallel computations
val a: (Int, String) > (Fibers with IOs) =
  Fibers.parallel(Math.cos(42).toInt, "example")

// Alternatively, it's possible to provide
// a 'Seq' of computations and produce a 'Seq'
// with the results
val b: Seq[Int] > (Fibers with IOs) =
  Fibers.parallel(Seq(Math.cos(42).toInt, Math.sin(42).toInt))

// The 'parallelFiber' method is similar but
// it doesn't automatically join the fibers and
// produces a 'Fiber[Seq[T]]'
val c: Fiber[Seq[Int]] > IOs =
  Fibers.parallelFiber(Seq(Math.cos(42).toInt, Math.sin(42).toInt))
```

The `race` methods are similar to `parallel` but they return the first computation to complete with either a successful result or a failure. Once the first result is produced, the other computations are automatically interrupted.

```scala
// There are method overloadings for up to four
// computations
val a: Int > (Fibers with IOs) =
  Fibers.race(Math.cos(42).toInt, Math.sin(42).toInt)

// It's also possible to to provide a 'Seq' 
// of computations 
val b: Int > (Fibers with IOs) =
  Fibers.race(Seq(Math.cos(42).toInt, Math.sin(42).toInt))

// 'raceFiber' produces a 'Fiber' without
// joining it
val c: Fiber[Int] > IOs =
  Fibers.raceFiber(Seq(Math.cos(42).toInt, Math.sin(42).toInt))
```

`Fibers` also provides `await` to wait for the completion of all computations and discard their results.

```scala
// Also up to four parallel computations
val a: Unit > (Fibers with IOs) =
  Fibers.await(Math.cos(42).toInt, "srt")

// Unit a 'Seq'
val b: Unit > (Fibers with IOs) =
  Fibers.await(Seq(Math.cos(42).toInt, Math.sin(42).toInt))

// Awaiting without joining
val c: Fiber[Unit] > IOs =
  Fibers.awaitFiber(Seq(Math.cos(42).toInt, Math.sin(42).toInt))
```

The `sleep` and `timeout` methods combine the `Timers` effect to pause a computation or time it out after a duration.

```scala
import kyo.concurrent.timers._
import scala.concurrent.duration._

// A computation that sleeps for 1s
val a: Unit > (Fibers with IOs with Timers) =
  Fibers.sleep(1.second)

// Times out and interrupts the provided 
// computation in case it doesn't produce 
// a result within 1s
val b: Int > (Fibers with IOs with Timers) =
  Fibers.timeout(1.second)(Math.cos(42).toInt)
```

The `join` methods provide interoperability with Scala's `Future`.

```scala
import scala.concurrent.Future

// An example 'Future' instance
val a: Future[Int] = Future.successful(42)

// Join the result of a 'Future'
val b: Int > (Fibers with IOs) =
  Fibers.join(a)

// Use 'joinFiber' to produce 'Fiber' 
// instead of joining the computation
val c: Fiber[Int] > IOs =
  Fibers.joinFiber(a)
```

> Important: Keep in mind that Scala's Future lacks built-in support for interruption. As a result, any computations executed through Future will run to completion, even if they're involved in a race operation where another computation finishes first.

A `Fiber` instance also provides a few relevant methods.

```scala
// An example fiber
val a: Fiber[Int] = Fibers.value(42)

// Check if the fiber is done
val b: Boolean > IOs =
  a.isDone

// Instance-level version of 'Fibers.get'
val c: Int > Fibers =
  a.get

// Avoid this low-level API to attach a 
// a callback to a fiber
val d: Unit > IOs =
  a.onComplete(println(_))

// A variant of `get` that returns a `Try`
// with the failed or successful result
val e: Try[Int] > (Fibers with IOs) =
  a.getTry

// Try to interrupt/cancel a fiber
val f: Boolean > IOs =
  a.interrupt

// Try to interrupt a fiber and wait for 
// its finalization
val g: Boolean > (Fibers with IOs) =
  a.interruptAwait

// Transforms a fiber into a Scala 'Future'
val h: Future[Int] > IOs =
  a.toFuture

// The 'transform' method is similar to `flatMap`
// in Scala's 'Future'
val i: Fiber[Int] > IOs =
  a.transform(v => Fibers.value(v + 1))
```

Similarly to `IOs`, users should avoid handling the `Fibers` effect directly and rely on `kyo.App` instead. If strictly necessary, there are two methods to handle the `Fibers` effect:

1. `run` takes a computation that has only the `Fibers` effect pending and returns a `Fiber` instance without blocking threads.
2. `runBlocking` accepts computations with arbitrary pending effects but it handles asynchronous operations by blocking the current thread.

```scala
// An example computation with fibers
val a: Int > (Fibers with IOs) =
  Fibers.fork(Math.cos(42).toInt)

// Avoid handling 'Fibers' directly
// Note how the code has to handle the
// 'IOs' effect and then handle 'Fibers'
val b: Fiber[Int] > IOs =
  Fibers.run(IOs.runLazy(a))

// The 'runBlocking' method accepts
// arbitrary pending effects but relies
// on thread blocking.
val c: Int > IOs =
  Fibers.runBlocking(a)
```

> Note: Handling the `Fibers` effect doesn't break referential transparency as with `IOs` but its usage is not trivial due to the limitations of the pending effects, especially `IOs`. Prefer `kyo.App` instead.

The `Fibers` effect also offers a low-level API to create `Promise`s as way to integrate external async operations with fibers. These APIs should be used only in low-level integration code.

```scala
// Initialize a promise
val a: Promise[Int] > IOs =
  Fibers.promise[Int]

// Try to fulfill a promise
val b: Boolean > IOs =
  a.map(_.complete(42))
```

> A `Promise` is basically a `Fiber` with all the regular functionality plus the `complete` method to manually fulfill the promise.

### Channels: Asynchronous Communication

The `Channels` effect offers a high-level abstraction for concurrent communication. Built atop `IOs` and `Fibers`, it enables safe data transfer between different parts of your application, whether they're running in different fibers or threads.

**Bounded channels**
```scala
import kyo.concurrent.channels._

// A 'bounded' channel rejects new
// elements once full, does not provide
// 'put' and 'take' methods
val a: Channel[Int] > IOs =
  Channels.bounded(capacity = 42)

// Obtain the number of items in the channel
// via the method 'size' in 'Channel'
val b: Int > IOs =
  a.map(_.size)

// Try to offer a new item
val c: Boolean > IOs =
  a.map(_.offer(42))

// Try to poll an item
val d: Option[Int] > IOs =
  a.map(_.poll)

// Check if channel is empty
val e: Boolean > IOs =
  a.map(_.isEmpty)

// Check if channel is full
val f: Boolean > IOs =
  a.map(_.isFull)
```

**Dropping and sliding channels**
```scala
// A 'dropping' channel discards new entries
// when full
val a: Channels.Unbounded[Int] > IOs =
  Channels.dropping(capacity = 42)

// A 'sliding' channel discards the oldest
// entries if necessary to make space for new 
// entries
val b: Channels.Unbounded[Int] > IOs =
  Channels.sliding(capacity = 42)

// Note how 'dropping' and 'sliding' channels
// return 'Channel.Unbounded`. It provides
// an additional method to 'put' new items
// unconditionally
val c: Unit > IOs =
  b.map(_.put(42))
```

**Blocking channels**
```scala
// A 'blocking' channel integrates with the
// 'Fibers' effect to provide 'take' and 'put'
// methods that allow fibers to wait for
// space in the channel or for new elements
val a: Channels.Blocking[Int] > IOs =
  Channels.blocking(capacity = 42)

// The 'put' method suspends the current fiber
// in case the channel has no space. Once space
// is made available, the new item is added to the 
// channel and the fiber is resumed
val b: Unit > (Fibers with IOs) =
  a.map(_.put(42))

// The 'take' method suspends the current fiber
// if the channel is empty. Once a new item arrives
// the fiber is resumed with the new entry
val c: Int > (Fibers with IOs) =
  a.map(_.take)
```

### Queues: Low-level 


License
-------

See the [LICENSE](https://github.com/getkyo/kyo/blob/master/LICENSE.txt) file for details.
 
