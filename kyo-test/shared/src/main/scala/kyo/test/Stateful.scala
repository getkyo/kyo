package kyo.test

import kyo.*
import kyo.test.Assertion.*
import kyo.test.Gen.*
// Assuming KyoTest provides a default spec base similar to KyoSpecDefault
// Here we use KyoSpecDefault as the base class for test specifications
import kyo.test.KyoSpecDefault

/** A Command for stateful property testing. Parameterized by:
  *   - R: The Kyo environment
  *   - E: Error type for execution
  *   - State: Your "model" or "actual" system state
  *   - Input: The input type that you generate
  *   - Output: The output type you get from execute
  */
trait Command[-R, +E, State, Input, Output]:
    /** Generate an input, possibly dependent on the current state. */
    def gen(state: State): Gen[R, Input]

    /** Execute the command against real code (or a real system). */
    def execute(input: Input): Output < (Env[R] & Abort[E])

    /** Whether this command is allowed given the current state. */
    def isPossible(state: State, input: Input): Boolean = true

    /** Update your model state (or actual system state) after execution. */
    def update(state: State, input: Input, output: Output): State

    /** Check invariants between the old/new model states and the real output. Return a TestResult that passes/fails as appropriate. */
    def ensure(initial: State, next: State, input: Input, output: Output): TestResult
end Command

final case class Action[R, E, S, I, O](
    command: Command[R, E, S, I, O],
    input: I,
    output: O
)

object Stateful:

    /** Generate a scenario that runs up to n steps. Each step: 1) Pick a random command from commands 2) Generate a random input from the
      * command 3) If the command isn't possible, skip the step 4) Otherwise, execute the command, check the result, and update the model
      * state
      */
    def checkN[R, E, S, I, O](n: Int)(
        initial: S,
        commands: Gen[R, Command[R, E, S, I, O]]
    ): Gen[R, TestResult] =
        Gen.fromKyo(runScenario(n)(initial, commands))

    private def runScenario[R, E, S, I, O](maxSteps: Int)(
        state0: S,
        commands: Gen[R, Command[R, E, S, I, O]]
    ): TestResult < (Env[R] & IO) =
        def step(count: Int, currentState: S, soFar: TestResult): TestResult < (Env[R] & IO) =
            if count >= maxSteps then
                Kyo.pure(soFar)
            else
                pickOneCommand(commands).flatMap { cmd =>
                    pickOneInput(cmd.gen(currentState)).flatMap { input =>
                        if !cmd.isPossible(currentState, input) then
                            step(count + 1, currentState, soFar)
                        else
                            cmd.execute(input).either.flatMap {
                                case Left(_) =>
                                    // If execution failed, produce a failing TestResult
                                    val failed = assertion[Unit](s"Command execution failed for input $input")(_ => false).run(())
                                    step(count + 1, currentState, soFar && failed)
                                case Right(output) =>
                                    val nextState = cmd.update(currentState, input, output)
                                    val check     = cmd.ensure(currentState, nextState, input, output)
                                    step(count + 1, nextState, soFar && check)
                            }
                    }
                }
        step(0, state0, assertion[Unit]("Initial state")(_ => true).run(()))
    end runScenario

    private def pickOneCommand[R, A](gen: Gen[R, A]): A < (Env[R] & IO) =
        gen.sample.runHead.map(_.map(_.value)).someOrElseKyo {
            Kyo.panicMessage("No command available from the Gen")
        }

    private def pickOneInput[R, A](gen: Gen[R, A]): A < (Env[R] & IO) =
        gen.sample.runHead.map(_.map(_.value)).someOrElseKyo {
            Kyo.panicMessage("No input could be generated")
        }

    def check[R, E, S, I, O](
        initial: S,
        commands: Gen[R, Command[R, E, S, I, O]]
    ): Gen[R, TestResult] = checkN(100)(initial, commands)
end Stateful

/** A simple mutable counter for demonstration purposes. */
final case class RealCounter(var value: Int):
    def increment(amount: Int): Unit = value += amount % 100
    def decrement(amount: Int): Unit = value -= amount % 100
end RealCounter

/** Command that increments a RealCounter. Input and output are both Int. */
class CounterIncrementCommand extends Command[Any, Nothing, RealCounter, Int, Int]:
    override def gen(state: RealCounter): Gen[Any, Int]                 = Gen.int(1, 10)
    override def execute(input: Int): Int < (Env[Any] & Abort[Nothing]) = Kyo.pure(input)
    override def isPossible(state: RealCounter, input: Int): Boolean    = true
    override def update(state: RealCounter, input: Int, output: Int): RealCounter =
        state.increment(output)
        state
    override def ensure(initial: RealCounter, next: RealCounter, input: Int, output: Int): TestResult =
        (assertion[Unit](s"Counter increment failed for input $input")(_ => next.value == (initial.value + input)) &&
            assertion[Unit](s"Counter increment output failed for input $input")(_ => output == input)).run(())
end CounterIncrementCommand

/** Command that decrements a RealCounter. Input and output are both Int. */
class CounterDecrementCommand extends Command[Any, Nothing, RealCounter, Int, Int]:
    override def gen(state: RealCounter): Gen[Any, Int]                 = Gen.int(1, 10)
    override def execute(input: Int): Int < (Env[Any] & Abort[Nothing]) = Kyo.pure(input)
    override def isPossible(state: RealCounter, input: Int): Boolean    = true
    override def update(state: RealCounter, input: Int, output: Int): RealCounter =
        state.decrement(output)
        state
    override def ensure(initial: RealCounter, next: RealCounter, input: Int, output: Int): TestResult =
        (assertion[Unit](s"Counter decrement failed for input $input")(_ => next.value == (initial.value - input)) &&
            assertion[Unit](s"Counter decrement output failed for input $input")(_ => output == input)).run(())
end CounterDecrementCommand

object ExampleSpec extends KyoSpecDefault:
    def spec = suite("RealCounter spec")(
        test("Incrementing and decrementing the counter works") {
            val initialCounter = RealCounter(0)
            val commandGen     = Gen.oneOf(Gen.const(new CounterIncrementCommand), Gen.const(new CounterDecrementCommand))
            val testResultGen  = Stateful.check(initialCounter, commandGen)
            checkAll(testResultGen)(identity _)
        }
    )
end ExampleSpec
