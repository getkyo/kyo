package zio.test

import zio._
import zio.stream._
import zio.test.Assertion._
import zio.test.Gen._
import zio.test.ZIOSpecDefault

/**
 * A Command for stateful property testing. Parameterized by:
 *  - R:     The ZIO environment
 *  - E:     Error type for execution
 *  - State: Your "model" or "actual" system state
 *  - Input: The input type that you generate
 *  - Output:The output type you get from `execute`
 */
trait Command[-R, +E, State, Input, Output] {

  /** Generate an input, possibly dependent on the current state. */
  def gen(state: State): Gen[R, Input]

  /** Execute the command against real code (or a real system). */
  def execute(input: Input): ZIO[R, E, Output]

  /** Whether this command is allowed given the current state. */
  def isPossible(state: State, input: Input): Boolean = true

  /** Update your “model” state (or actual system state) after execution. */
  def update(state: State, input: Input, output: Output): State

  /**
   * Check invariants between the old/new model states and the real output.
   * Return a TestResult that passes/fails as appropriate.
   */
  def ensure(initial: State, next: State, input: Input, output: Output): TestResult
}

final case class Action[R, E, S, I, O](
  command: Command[R, E, S, I, O],
  input: I,
  output: O
)

object Stateful {

  /**
   * Generate a scenario that runs up to `n` steps.  Each step:
   *   1) Pick a random command from `commands`
   *   2) Generate an input
   *   3) Possibly skip if `require == false`
   *   4) Execute, check the result, update the model
   */
  def checkN[R, E, S, I, O](n: Int)(
    initial: S,
    commands: Gen[R, Command[R, E, S, I, O]]
  ): Gen[R, TestResult] =
    Gen.fromZIO(runScenario(n)(initial, commands))

  /**
   * Runs a scenario in a ZIO effect (not a Gen). We embed the final
   * result in a Gen via `Gen.fromZIO(...)`.
   */
  private def runScenario[R, E, S, I, O](
    maxSteps: Int
  )(state0: S, commands: Gen[R, Command[R, E, S, I, O]]): ZIO[R, Nothing, TestResult] = {

    def step(count: Int, currentState: S, soFar: TestResult): ZIO[R, Nothing, TestResult] =
      if (count >= maxSteps) {
        // done
        ZIO.succeed(soFar)
      } else {
        // pick one random command
        pickOneCommand(commands).flatMap { cmd =>
          // generate one random input
          pickOneInput(cmd.gen(currentState)).flatMap { input =>
            if (!cmd.isPossible(currentState, input)) {
              // skip and continue
              step(count + 1, currentState, soFar)
            } else {
              // execute
              cmd.execute(input).either.flatMap {
                case Left(_) =>
                  // If it failed, produce a failing TestResult
                  val failed = assertion[Unit](s"Command execution failed for input $input")(_ => false).run(())
                  step(count + 1, currentState, soFar && failed)

                case Right(output) =>
                  val next = cmd.update(currentState, input, output)
                  val check = cmd.ensure(currentState, next, input, output)
                  step(count + 1, next, soFar && check)
              }
            }
          }
        }
      }

    step(0, state0, assertion[Unit]("Initial state")(_ => true).run(()))
  }

  /**
   * Pick one random command from a Gen. The default `commands.sample`
   * is a ZStream of Sample. We just want **one** random value:
   */
  private def pickOneCommand[R, A](gen: Gen[R, A]): ZIO[R, Nothing, A] =
    gen.sample.runHead.map(_.map(_.value)).someOrElseZIO {
      // If runHead found nothing (strange case if Gen is empty),
      // fall back to a default failure or a default command
      ZIO.dieMessage("No command available from the Gen")
    }

  /**
   * Similarly for picking one random input from a Gen.
   */
  private def pickOneInput[R, A](gen: Gen[R, A]): ZIO[R, Nothing, A] =
    gen.sample.runHead.map(_.map(_.value)).someOrElseZIO {
      ZIO.dieMessage("No input could be generated")
    }

  /**
   * Convenience for 100-step scenario.
   */
  def check[R, E, S, I, O](
    initial: S,
    commands: Gen[R, Command[R, E, S, I, O]]
  ): Gen[R, TestResult] = checkN(100)(initial, commands)

  /**
   * Example: run up to 10 commands in parallel.
   * We first generate a random list of (Command, Input) pairs,
   * then run them in parallel. If any fails, we return a failing result.
   */
  def checkParallel[R, E, S, I, O](
    initial: S,
    commands: Gen[R, Command[R, E, S, I, O]]
  ): Gen[R, TestResult] = {

    // Generate a list of up to 10 random (cmd, input) pairs
    val actionsGen: Gen[R, List[(Command[R, E, S, I, O], I)]] =
      Gen.listOfBounded(1, 10) {
        commands.flatMap { cmd =>
          cmd.gen(initial).map { input =>
            (cmd, input)
          }
        }
      }

    // Then embed a parallel run in Gen
    actionsGen.mapZIO { actions =>
      ZIO
        .foreachPar(actions) { case (cmd, in) =>
          cmd.execute(in).either.map {
            case Left(_) =>
              assertion[Unit](s"Parallel command failed: $cmd")(_ => false).run(())
            case Right(out) =>
              // For concurrency tests, you might do cmd.update in a shared ref,
              // but here we just do a simplistic check
              cmd.ensure(initial, cmd.update(initial, in, out), in, out)
          }
        }
        .map(_.reduce(_ && _)) // Combine all results
    }
  }
}

/** A real, mutable class for demonstration. */
final case class RealCounter(var value: Int) {
  def increment(amount: Int): Unit = {
    value += amount % 100
  }
  def decrement(amount: Int): Unit = {
    value -= amount % 100
  }
}

/**
 * Example command that increments a `RealCounter` by `input`.
 * Input and output are both `Int`.
 */
class CounterIncrementCommand extends Command[Any, Nothing, RealCounter, Int, Int] {

  override def gen(state: RealCounter): Gen[Any, Int] =
    Gen.int(1, 10)

  override def execute(input: Int): ZIO[Any, Nothing, Int] =
    ZIO.succeed(input)

  override def isPossible(state: RealCounter, input: Int): Boolean = true

  override def update(state: RealCounter, input: Int, output: Int): RealCounter = {
    state.increment(output)
    state
  }

  override def ensure(initial: RealCounter, next: RealCounter, input: Int, output: Int): TestResult =
    (assertion[Unit](s"Counter increment failed for input $input")(_ => next.value == (initial.value + input)) &&
      assertion[Unit](s"Counter increment output failed for input $input")(_ => output == input)).run(())
}

class CounterDecrementCommand extends Command[Any, Nothing, RealCounter, Int, Int] {

  override def gen(state: RealCounter): Gen[Any, Int] =
    Gen.int(1, 10)

  override def execute(input: Int): ZIO[Any, Nothing, Int] =
    ZIO.succeed(input)

  override def isPossible(state: RealCounter, input: Int): Boolean = true

  override def update(state: RealCounter, input: Int, output: Int): RealCounter = {
    state.decrement(output)
    state
  }

  override def ensure(initial: RealCounter, next: RealCounter, input: Int, output: Int): TestResult =
    (assertion[Unit](s"Counter decrement failed for input $input")(_ => next.value == (initial.value - input)) &&
      assertion[Unit](s"Counter decrement output failed for input $input")(_ => output == input)).run(())
}

/**
 * Example spec that tests the RealCounter using the `CounterCommand`.
 */
object ExampleSpec extends ZIOSpecDefault {

  def spec = suite("RealCounter spec")(
    test("Incrementing and decrementing the counter works") {
      val initialCounter = RealCounter(0)
      val commandGen     = Gen.oneOf(Gen.const(new CounterIncrementCommand), Gen.const(new CounterDecrementCommand))

      // We get a Gen[Any, TestResult] that runs 100 steps:
      val testResultGen = Stateful.check(initialCounter, commandGen)

      // Now we can run `checkAll`, sampling that generator multiple times.
      // The signature is `checkAll(n: Int)(gen: Gen[R,A])(assertion: A => TestResult)`.
      // We want to do `checkAll(100)(testResultGen)(identity _)`.
      checkAll(testResultGen)(identity _)
    }
  )
}
