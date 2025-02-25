package kyo.test

import kyo.*
import kyo.Abort.*
import kyo.test.render.ConsoleRenderer
import scala.reflect.annotation.EnableReflectiveInstantiation

// Assuming KyoApp is the entry point for Kyo-based applications
@EnableReflectiveInstantiation
abstract class KyoSpecAbstract extends KyoApp with KyoSpecAbstractVersionSpecific:
    self =>
    type Environment

    def spec: Spec[Environment & TestEnvironment & Scope, Any]

    def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
        Chunk(TestAspect.fibers, TestAspect.timeoutWarning(60.seconds))

    // Converted from ZLayer[Any, Any, Environment] to Layer[Environment, Any]
    def bootstrap: Layer[Environment, Any]

    final def run: Summary < (Env[Environment with KyoAppArgs with Scope] & Abort[Throwable]) =
        implicit val trace = Trace.empty
        runSpec.provideSomeLayer[Environment with KyoAppArgs with Scope](
            Layer.environment[Environment with KyoAppArgs with Scope] and
                (liveEnvironment >>> TestEnvironment.live and TestLogger.fromConsole(Console.ConsoleLive))
        )
    end run

    final def <>(that: KyoSpecAbstract)(using trace: Frame): KyoSpecAbstract =
        new KyoSpecAbstract:
            type Environment = self.Environment with that.Environment

            def bootstrap: Layer[Environment, Any] =
                self.bootstrap and that.bootstrap

            def spec: Spec[Environment with TestEnvironment with Scope, Any] =
                self.aspects.foldLeft(self.spec)(_ @@ _) + that.aspects.foldLeft(that.spec)(_ @@ _)

            def Tag: Tag[Environment] =
                given Tag[self.Environment] = self.Tag
                given Tag[that.Environment] = that.Tag
                Tag[Environment]
            end Tag

            override def aspects: Chunk[TestAspectAtLeastR[Environment with TestEnvironment]] =
                Chunk.empty

    final protected def runSpec(using
        trace: Trace
    ): Summary < (Env[Environment with TestEnvironment with KyoAppArgs with Scope] & Abort[Throwable]) =
        for
            args    <- service[KyoAppArgs]
            console <- Console.service
            testArgs = TestArgs.parse(args.getArgs.toArray)
            summary <- runSpecAsApp(spec, testArgs, console)
            _       <- if testArgs.printSummary then console.printLine(testArgs.testRenderer.renderSummary(summary)).orDie else Kyo.unit
            _ <- if summary.status == Summary.Failure && !testArgs.ignoreFailures then Abort.fail(new RuntimeException("Tests failed."))
            else Kyo.unit
        yield summary

    private[test] def runSpecAsApp(
        spec: Spec[Environment with TestEnvironment with Scope, Any],
        testArgs: TestArgs,
        console: Console,
        testEventHandler: ZTestEventHandler = ZTestEventHandler.silent
    )(using trace: Trace): Summary < Env[Environment with TestEnvironment with Scope] =
        val filteredSpec: Spec[Environment with TestEnvironment with Scope, Any] = FilteredSpec(spec, testArgs)
        for
            runtime <- Kyo.runtime[TestEnvironment with Scope]
            scopeEnv     = runtime.environment
            perTestLayer = (Layer.succeedEnvironment(scopeEnv) and liveEnvironment) >>> (TestEnvironment.live and Layer.environment[Scope])
            executionEventSinkLayer = ExecutionEventSink.live(console, testArgs.testEventRenderer)
            environment <- service[Environment]
            runner = TestRunner(TestExecutor.default[Environment, Any](
                Layer.succeedEnvironment(environment),
                perTestLayer,
                executionEventSinkLayer,
                testEventHandler
            ))
            randomId <- withRandom(Random.RandomLive)(Random.nextInt).map(n => "test_case_" + n)
            summary  <- runner.run(randomId, aspects.foldLeft(filteredSpec)(_ @@ _) @@ TestAspect.fibers)
        yield summary
        end for
    end runSpecAsApp

    private[test] def runSpecWithSharedRuntimeLayer(
        fullyQualifiedName: String,
        spec: Spec[Environment with TestEnvironment with Scope, Any],
        testArgs: TestArgs,
        runtime: Runtime[?],
        testEventHandler: ZTestEventHandler,
        console: Console
    )(using trace: Trace): Summary < Env[?] =
        val filteredSpec  = FilteredSpec(spec, testArgs)
        val castedRuntime = runtime.asInstanceOf[Runtime[Environment with TestOutput]]
        TestRunner(TestExecutor.default[Environment, Any](
            Layer.succeedEnvironment(castedRuntime.environment),
            testEnvironment and Scope.default,
            (Layer.succeedEnvironment(castedRuntime.environment)) >>> ExecutionEventSink.live,
            testEventHandler
        )).run(fullyQualifiedName, aspects.foldLeft(filteredSpec)(_ @@ _) @@ TestAspect.fibers)
    end runSpecWithSharedRuntimeLayer

end KyoSpecAbstract
