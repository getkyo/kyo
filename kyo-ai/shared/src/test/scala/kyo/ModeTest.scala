package kyo

class ModeTest extends kyo.test.Test[Any]:

    "modes apply in registration order" in {
        AtomicRef.init(Chunk.empty[String]).map { recorded =>
            val m1 = new Mode[Any]:
                def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
                    Frame
                ): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
                    recorded.getAndUpdate(_.append("m1")).andThen(gen)

            val m2 = new Mode[Any]:
                def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
                    Frame
                ): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
                    recorded.getAndUpdate(_.append("m2")).andThen(gen)

            val stubGen: Maybe[String] < (LLM & Async & Abort[AIGenException]) = Present("x")

            LLM.run(
                AI.init.map(ai => AI.enable(m1)(AI.enable(m2)(Mode.internal.handle(ai, stubGen))))
            ).andThen(recorded.get)
        }.map(tags => assert(tags == Chunk("m1", "m2")))
    }

    "enable with no mode is a pass-through" in {
        val stubGen: Maybe[String] < (LLM & Async & Abort[AIGenException]) = Present("x")
        LLM.run(AI.init.map(ai => Mode.internal.handle(ai, stubGen)))
            .map(result => assert(result == Present("x")))
    }

    "a mode can transform the generation result" in {
        val upper = new Mode[Any]:
            def apply[A: Schema](ai: AI, gen: Maybe[A] < (LLM & Async & Abort[AIGenException]))(using
                Frame
            ): Maybe[A] < (LLM & Async & Abort[AIGenException]) =
                gen.map {
                    case Present(s: String) => Present(s.toUpperCase.asInstanceOf[A])
                    case other              => other
                }

        val stubGen: Maybe[String] < (LLM & Async & Abort[AIGenException]) = Present("hi")
        LLM.run(
            AI.init.map(ai => AI.enable(upper)(Mode.internal.handle(ai, stubGen)))
        ).map(result => assert(result == Present("HI")))
    }

    "Mode.init builds a mode from a polymorphic transform" in {
        AtomicRef.init(Chunk.empty[String]).map { recorded =>
            val tag: Mode[Any] = Mode.init([A] => (_, gen) => recorded.getAndUpdate(_.append("seen")).andThen(gen))
            val stubGen: Maybe[String] < (LLM & Async & Abort[AIGenException]) = Present("ok")
            LLM.run(AI.init.map(ai => AI.enable(tag)(Mode.internal.handle(ai, stubGen)))).map { result =>
                assert(result == Present("ok"), s"the init-built mode must pass the generation through, got: $result")
                recorded.get.map(tags => assert(tags == Chunk("seen"), s"the init-built mode body must have run, got: $tags"))
            }
        }
    }

    "a mode that aborts the wrapped generation propagates the failure" in {
        val failing: Mode[Any] = Mode.init([A] => (_, _) => Abort.fail(AIEvalExhaustedException(1)))
        val stubGen: Maybe[String] < (LLM & Async & Abort[AIGenException]) = Present("x")
        Abort.run[AIException] {
            LLM.run(AI.init.map(ai => AI.enable(failing)(Mode.internal.handle(ai, stubGen))))
        }.map { result =>
            assert(result.isFailure, s"a failing mode must propagate its abort, got: $result")
        }
    }

    "Mode.apply carries the generation failure row" in {
        // A compile-probe ascribing the gen parameter and result rows: the wrapped generation carries its
        // failures typed at the Mode.apply boundary, so both are Maybe[A] < (LLM & Async & Abort[AIGenException]).
        val passthrough: Mode[Any]                                      = Mode.init([A] => (_, gen) => gen)
        val step: Maybe[String] < (LLM & Async & Abort[AIGenException]) = Present("x")
        // The instance is minted inside the run so it carries the run's owner (a bare new AI would be cross-run).
        val probe: Maybe[String] < (LLM & Async & Abort[AIGenException]) = AI.init.map(ai => passthrough.apply(ai, step))
        LLM.run(probe).map { r =>
            assert(r == Present("x"), s"the stub step must pass through unchanged under a bare apply, got: $r")
        }
    }

end ModeTest
