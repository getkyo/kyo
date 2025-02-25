package kyo.test

import kyo.*
import kyo.io.*
import kyo.test.render.ConsoleRenderer

/** A `TestExecutor[R, E]` is capable of executing specs that require an environment `R` and may fail with an `E`.
  */
abstract class TestExecutor[+R, E]:
    def run(fullyQualifiedName: String, spec: Spec[R, E], defExec: ExecutionStrategy)(using trace: Trace): Summary < IO

object TestExecutor:

    // Override shutdown timeout to avoid long waits in tests
    private[test] val overrideShutdownTimeout: Local[Option[Duration]] =
        Local.unsafeMake[Option[Duration]](None)

    def default[R, E](
        sharedSpecLayer: Layer[R, E],
        freshLayerPerSpec: Layer[TestEnvironment with Scope, Nothing],
        sinkLayer: Layer[Nothing, ExecutionEventSink],
        eventHandler: TestEventHandler
    ): TestExecutor[R with TestEnvironment with Scope, E] =
        new TestExecutor[R with TestEnvironment with Scope, E]:
            def run(fullyQualifiedName: String, spec: Spec[R with TestEnvironment with Scope, E], defExec: ExecutionStrategy)(using
                trace: Trace
            ): Summary < IO =
                for
                    sink <- Env.service[ExecutionEventSink]
                    topParent    = SuiteId.global
                    processEvent = (event: ExecutionEvent) => sink.process(event) *> eventHandler.handle(event)
                    _ <-
                        def loop(
                            labels: List[String],
                            spec: Spec[Scope, E],
                            exec: ExecutionStrategy,
                            ancestors: List[SuiteId],
                            sectionId: SuiteId
                        ): Unit < IO =
                            spec.caseValue match
                                case Spec.ExecCase(exec0, spec0) =>
                                    loop(labels, spec0, exec0, ancestors, sectionId)
                                case Spec.LabeledCase(label, spec0) =>
                                    loop(label :: labels, spec0, exec, ancestors, sectionId)
                                case Spec.ScopedCase(managed) =>
                                    Scope.make.flatMap { scope =>
                                        scope.extend(managed.flatMap(s => loop(labels, s, exec, ancestors, sectionId)))
                                            .onExit { exit =>
                                                for
                                                    timeout <- overrideShutdownTimeout.get.map(_.getOrElse(Duration.seconds(60)))
                                                    warning <- IO.delay(timeout)
                                                        .flatMap(_ => IO.logWarning("Warning: Kyo Test scope closing delay detected."))
                                                        .fork
                                                    finalizer <- scope.close(exit).ensuring(warning.interrupt).fork
                                                    _         <- warning.await
                                                    _         <- finalizer.join.when(exit.isInterrupted)
                                                yield ()
                                            }
                                    }.catchAll { e =>
                                        val event = ExecutionEvent.RuntimeFailure(sectionId, labels, TestFailure.Runtime(e), ancestors)
                                        processEvent(event)
                                    }
                                case Spec.MultipleCase(specs) =>
                                    uninterruptibleMask { restore =>
                                        for
                                            newSectionId <- SuiteId.newRandom
                                            newAncestors = sectionId :: ancestors
                                            start        = ExecutionEvent.SectionStart(labels, newSectionId, newAncestors)
                                            _ <- processEvent(start)
                                            end = ExecutionEvent.SectionEnd(labels, newSectionId, newAncestors)
                                            _ <- restore(specs.traverseExec(exec)(s => loop(labels, s, exec, newAncestors, newSectionId)))
                                                .ensuring(processEvent(end))
                                        yield ()
                                    }
                                case Spec.TestCase(test, staticAnnotations) =>
                                    val testResult = (for
                                        _ <- processEvent(ExecutionEvent.TestStarted(
                                            labels,
                                            staticAnnotations,
                                            ancestors,
                                            sectionId,
                                            fullyQualifiedName
                                        ))
                                        result <- Live.withLive(test)(_.timed).either
                                        duration = result.map(_._1.toMillis).fold(_ => 1L, identity)
                                        event = ExecutionEvent.Test(
                                            labels,
                                            result.map(_._2),
                                            staticAnnotations ++ extractAnnotations(result.map(_._2)),
                                            ancestors,
                                            duration,
                                            sectionId,
                                            fullyQualifiedName
                                        )
                                    yield event).catchAll { e =>
                                        val event = ExecutionEvent.RuntimeFailure(sectionId, labels, TestFailure.Runtime(e), ancestors)
                                        ConsoleRenderer.render(e, labels).foreach(cr => println("CR: " + cr))
                                        IO.succeed(event)
                                    }
                                    for
                                        testEvent <- testResult
                                        _         <- processEvent(testEvent)
                                    yield ()
                                    end for
                        val scopedSpec = spec.annotated
                            .provideSomeLayer(freshLayerPerSpec)
                            .provideLayerShared(sharedSpecLayer.tapErrorCause { e =>
                                processEvent(
                                    ExecutionEvent.RuntimeFailure(
                                        SuiteId(-1),
                                        List("Top level layer construction failure. No tests will execute."),
                                        TestFailure.Runtime(e),
                                        List.empty
                                    )
                                )
                            })
                        loop(Nil, scopedSpec, defExec, Nil, SuiteId.global)
                yield Summary.empty
end TestExecutor
