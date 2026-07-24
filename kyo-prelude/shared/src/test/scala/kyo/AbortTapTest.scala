package kyo

import kyo.*

/** Tests for the non-consuming `Abort.tap`/`Abort.tapError` pair: the side effect fires on the matching error, the error is
  * re-raised unchanged (still observable by an enclosing handler), and the success path never evaluates the observer. The
  * observed value is threaded through a `Var` so the side effect stays inside the effect row.
  */
class AbortTapTest extends kyo.test.Test[Any]:

    private val boom = new RuntimeException("boom")

    "Abort.tap" - {
        "fires on failure and re-raises the same failure" in {
            val (seen, result) =
                Var.runTuple(Maybe.empty[Throwable]) {
                    Abort.run[Throwable](
                        Abort.tap[Throwable](e => Var.set(Present(e): Maybe[Throwable]))(Abort.fail(boom): Int < Abort[Throwable])
                    )
                }.eval
            assert(result == Result.Failure(boom))
            assert(seen == Present(boom))
        }

        "does not fire on success" in {
            val (seen, result) =
                Var.runTuple(false) {
                    Abort.run[Throwable](Abort.tap[Throwable](_ => Var.set(true))(42: Int < Abort[Throwable]))
                }.eval
            assert(result == Result.Success(42))
            assert(!seen)
        }

        "an enclosing recover still sees the tapped failure (tap does not consume)" in {
            val (order, result) =
                Var.runTuple(List.empty[String]) {
                    Abort.run[Nothing] {
                        Abort.recover[Throwable](_ => Var.update[List[String]]("recover" :: _).andThen(-1)) {
                            Abort.tap[Throwable](_ => Var.update[List[String]]("tap" :: _).unit)(Abort.fail(boom): Int < Abort[Throwable])
                        }
                    }
                }.eval
            assert(result == Result.Success(-1))
            assert(order.reverse == List("tap", "recover"))
        }
    }

    "Abort.tapError" - {
        "fires on a panic too, and re-raises it" in {
            val (seen, result) =
                Var.runTuple(Maybe.empty[Result.Error[Throwable]]) {
                    Abort.run[Throwable](Abort.tapError[Throwable](err => Var.set(Present(err): Maybe[Result.Error[Throwable]]))(
                        Abort.panic(boom): Int < Abort[Throwable]
                    ))
                }.eval
            assert(result == Result.Panic(boom))
            assert(seen == Present(Result.Panic(boom)))
        }

        "fires on a typed failure with the full Error" in {
            val (seen, result) =
                Var.runTuple(Maybe.empty[Result.Error[String]]) {
                    Abort.run[String](Abort.tapError[String](err => Var.set(Present(err): Maybe[Result.Error[String]]))(Abort.fail(
                        "domain"
                    ): Int < Abort[String]))
                }.eval
            assert(result == Result.Failure("domain"))
            assert(seen == Present(Result.Failure("domain")))
        }
    }
end AbortTapTest
