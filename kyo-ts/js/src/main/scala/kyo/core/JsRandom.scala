package kyo

import scala.scalajs.js
import scala.scalajs.js.annotation.*

@JSExportTopLevel("Random")
class JsRandom(@JSName("$rand") val underlying: Random) extends js.Object:
    import kyo.JsFacadeGivens.given
    def let_[A, S](v: JsKyo[A, S]) =
        new JsKyo(Random.let(underlying)(v.underlying))

    def nextBoolean() =
        new JsKyo(underlying.nextBoolean)

    def nextBytes(length: Int) =
        new JsKyo(underlying.nextBytes(length))

    def nextDouble() =
        new JsKyo(underlying.nextDouble)

    def nextFloat() =
        new JsKyo(underlying.nextFloat)

    def nextGaussian() =
        new JsKyo(underlying.nextGaussian)

    def nextInt(exclusiveBound: Int) =
        new JsKyo(underlying.nextInt(exclusiveBound))

    def nextInt() =
        new JsKyo(underlying.nextInt)

    def nextLong() =
        new JsKyo(underlying.nextLong)

    def nextString(length: Int, chars: Seq[Char]) =
        new JsKyo(underlying.nextString(length, chars))

    def nextStringAlphanumeric(length: Int) =
        new JsKyo(underlying.nextStringAlphanumeric(length))

    def nextValue[A](seq: Seq[A]) =
        new JsKyo(underlying.nextValue(seq))

    def nextValues[A](length: Int, seq: Seq[A]) =
        new JsKyo(underlying.nextValues(length, seq))

    def shuffle[A](seq: Seq[A]) =
        new JsKyo(underlying.shuffle(seq))

    def unsafe() =
        underlying.unsafe


end JsRandom

object JsRandom:
    import kyo.JsFacadeGivens.given

    @JSExportStatic
    def apply(u: Random.Unsafe) =
        new JsRandom(Random.apply(u))

    @JSExportStatic
    def get() =
        new JsKyo(Random.get)

    @JSExportStatic
    def live() =
        new JsRandom(Random.live)

    @JSExportStatic
    def nextBoolean() =
        new JsKyo(Random.nextBoolean)

    @JSExportStatic
    def nextDouble() =
        new JsKyo(Random.nextDouble)

    @JSExportStatic
    def nextFloat() =
        new JsKyo(Random.nextFloat)

    @JSExportStatic
    def nextGaussian() =
        new JsKyo(Random.nextGaussian)

    @JSExportStatic
    def nextInt() =
        new JsKyo(Random.nextInt)

    @JSExportStatic
    def nextLong() =
        new JsKyo(Random.nextLong)

    @JSExportStatic
    def nextValue[A](seq: Seq[A]) =
        new JsKyo(Random.nextValue(seq))

    @JSExportStatic
    def shuffle[A](seq: Seq[A]) =
        new JsKyo(Random.shuffle(seq))

    @JSExportStatic
    def use[A, S](f: Function1[Random, `<`[A, S]]) =
        new JsKyo(Random.use(f))


end JsRandom