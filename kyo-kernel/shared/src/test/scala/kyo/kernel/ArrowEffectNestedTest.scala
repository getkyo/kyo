package kyo.kernel

import kyo.*
import kyo.kernel.*
import kyo.kernel.internal.*

class ArrowEffectNestedTest extends Test:

    given [A, B]: CanEqual[A, B] = CanEqual.derived

    sealed trait TestEffect extends ArrowEffect[Const[Int], Const[Int]]

    def suspend(i: Int): Int < TestEffect =
        ArrowEffect.suspend[Any](Tag[TestEffect], i)

    val tag: Tag[TestEffect] = Tag[TestEffect]

    def flatten[A, B, C](v: A < B < C): A < (B & C) = v.map(a => a)

    "not handle Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < S =
            ArrowEffect.handle(tag, v):
                [C] => (input, cont) => cont(input * 10)

        "unwraps Nested and returns inner suspension" in {
            val comp: Int < TestEffect         = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)
            val result: Int < TestEffect < Any = handle(nested)

            assert(result == nested, "handleSimple should return the nested computation")

            val flattened   = flatten(result)
            val finalResult = handle(flattened)

            assert(finalResult.eval == 50)
        }
    }

    "handleFirst on Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < (S & TestEffect) =
            ArrowEffect.handleFirst(tag, v)(
                [C] => (input, cont) => cont(input * 10),
                identity
            )

        "done callback receives unwrapped value" in {
            val comp                           = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)

            val result = handle(nested)

            assert(result == nested, "handleFirst should return the nested computation")

            val flattened                     = flatten(result)
            val finalResult: Int < TestEffect = handle(flattened)
            assert(finalResult.evalNow == Maybe(50))
        }
    }

    "handleLoop (stateless) on Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < S =
            ArrowEffect.handleLoop(Tag[TestEffect], v):
                [C] => (input, cont) => Loop.continue(cont(input * 10))

        "unwraps Nested and handles inner suspension" in {
            val comp: Int < TestEffect         = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)

            val result = handle(nested)
            assert(result == nested, "handleLoop should return the nested computation")

            val flattened              = flatten(result)
            val finalResult: Int < Any = handle(flattened)

            assert(finalResult.eval == 50)
        }
    }

    "handleLoop (stateful) on Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < S =
            ArrowEffect.handleLoop(tag, 0, v)(
                [C] => (input, state, cont) => Loop.continue(state + 1, cont((input + state) * 10))
            )

        "unwraps Nested and handles inner suspension" in {
            val comp: Int < TestEffect         = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)

            val result = handle(nested)
            assert(result == nested, "handleLoop should return the nested computation")

            val flattened              = flatten(result)
            val finalResult: Int < Any = handle(flattened)

            assert(finalResult.eval == 50)
        }

    }

    "handleLoop (stateful + done) on Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < S =
            ArrowEffect.handleLoop(tag, 0, v)(
                [C] => (input, state, cont) => Loop.continue(state + 1, cont(input * 10)),
                (state, v) => v
            )

        "unwraps Nested and handles inner suspension" in {
            val comp: Int < TestEffect         = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)

            val result = handle(nested)
            assert(result == nested, "handleLoop should return the nested computation")

            val flattened              = flatten(result)
            val finalResult: Int < Any = handle(flattened)

            assert(finalResult.eval == 50)
        }
    }

    "handleCatching on Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < S =
            ArrowEffect.handleCatching(tag, v)(
                [C] => (input, cont) => cont(input * 10),
                recover = e => throw e
            )

        "unwraps Nested and handles inner suspension" in {
            val comp: Int < TestEffect         = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)

            val result = handle(nested)
            assert(result == nested, "handleLoop should return the nested computation")

            val flattened              = flatten(result)
            val finalResult: Int < Any = handle(flattened)

            assert(finalResult.eval == 50)
        }
    }

    "handlePartial on Nested" - {

        def handle[A, S](v: A < (S & TestEffect)): A < (S & TestEffect) =
            ArrowEffect.handlePartial(tag, tag, v, Context.empty)(
                stop =
                    false,
                [C] => (input, cont) => cont(input * 10),
                [C] => (input, cont) => cont(input * 10)
            )

        "unwraps Nested and handles inner suspension" in {
            val comp: Int < TestEffect         = suspend(5)
            val nested: Int < TestEffect < Any = Nested(comp)

            val result = handle(nested)
            assert(result == nested, "handlePartial should return the nested computation")

            val flattened   = flatten(result)
            val finalResult = handle(flattened)
            assert(finalResult.evalNow == Maybe(50))
        }
    }

end ArrowEffectNestedTest
