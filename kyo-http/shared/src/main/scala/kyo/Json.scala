package kyo

import kyo.*
import scala.deriving.Mirror

/** JSON encoding/decoding type class that bridges typed Scala values and HTTP wire format.
  *
  * Json is the mechanism that enables route-based handlers (auto-deserializing inputs, auto-serializing outputs) and typed client calls
  * (auto-serializing requests, auto-deserializing responses). Wraps zio-json internally without exposing ZIO types. Instances are derived
  * automatically for case classes and sealed traits via `derives Json`. Built-in instances cover primitives, collections (`Seq`, `List`,
  * `Vector`, `Set`, `Map`), `Option`, `Either`, and `Maybe`.
  *
  * Note: `encode` can throw on encoding failure — it is not effect-tracked. `decode` returns `Result.fail` on failure. Unit decoding
  * accepts empty bodies and JSON null.
  *
  * @tparam A
  *   The type to encode/decode
  *
  * @see
  *   [[kyo.HttpRoute.RequestDef.bodyJson]] Declares a JSON request body on a route
  * @see
  *   [[kyo.HttpRoute.ResponseDef.bodyJson]] Declares a JSON response body on a route
  * @see
  *   [[kyo.HttpClient.getJson]] Convenience for JSON GET requests
  * @see
  *   [[kyo.HttpHandler.getJson]] Convenience for JSON GET handlers
  */
abstract class Json[A]:
    private[kyo] def zioSchema: zio.schema.Schema[A]

    def encode(value: A): String =
        zio.schema.codec.JsonCodec.jsonEncoder(zioSchema).encodeJson(value, None).toString

    def decode(json: String): Result[String, A] =
        zio.schema.codec.JsonCodec.jsonDecoder(zioSchema).decodeJson(json) match
            case Right(a)    => Result.succeed(a)
            case Left(error) => Result.fail(s"JSON decode error: $error")
end Json

object Json:

    def apply[A](using json: Json[A]): Json[A] = json

    def fromZio[A](zs: zio.schema.Schema[A]): Json[A] =
        new Json[A]:
            val zioSchema: zio.schema.Schema[A] = zs

    // Derivation - delegates to zio-json
    // Bridge givens are inlined at the call site so DeriveJson.gen finds existing instances:
    // - Seq[B] bridge: makes Seq visible to zio-json's implicit search
    // - Json[B] bridge: lets DeriveJson.gen reuse already-derived kyo Json instances
    //   instead of re-deriving from scratch (prevents recursion depth exceeded errors)
    inline given derived[A](using m: Mirror.Of[A]): Json[A] =
        given [B](using zs: zio.schema.Schema[B]): zio.schema.Schema[Seq[B]] =
            zio.schema.Schema.list(using zs).transform(_.toSeq, _.toList)
        given [B](using s: Json[B]): zio.schema.Schema[B] = s.zioSchema
        fromZio(zio.schema.DeriveSchema.gen[A])
    end derived

    // Primitive instances
    given Json[Int]     = fromZio(zio.schema.Schema[Int])
    given Json[Long]    = fromZio(zio.schema.Schema[Long])
    given Json[Boolean] = fromZio(zio.schema.Schema[Boolean])
    given Json[Double]  = fromZio(zio.schema.Schema[Double])
    given Json[Float]   = fromZio(zio.schema.Schema[Float])
    given Json[Short]   = fromZio(zio.schema.Schema[Short])
    given Json[Byte]    = fromZio(zio.schema.Schema[Byte])
    given Json[Char]    = fromZio(zio.schema.Schema[Char])

    given Json[String] = fromZio(zio.schema.Schema[String])

    // Unit: accept empty body or JSON null
    given Json[Unit] = new Json[Unit]:
        val zioSchema: zio.schema.Schema[Unit] = zio.schema.Schema[Unit]
        override def decode(json: String): Result[String, Unit] =
            if json.isEmpty || json.trim.isEmpty || json.trim == "null" then Result.unit
            else
                zio.schema.codec.JsonCodec.jsonDecoder(zioSchema).decodeJson(json) match
                    case Right(a)    => Result.succeed(a)
                    case Left(error) => Result.fail(s"JSON decode error: $error")

    // Collection instances
    given [A](using s: Json[A]): Json[Seq[A]] =
        fromZio(zio.schema.Schema.list(using s.zioSchema).transform(_.toSeq, _.toList))
    given [A](using s: Json[A]): Json[List[A]] =
        fromZio(zio.schema.Schema.list(using s.zioSchema))
    given [A](using s: Json[A]): Json[Vector[A]] =
        fromZio(zio.schema.Schema.vector(using s.zioSchema))
    given [A](using s: Json[A]): Json[Set[A]] =
        fromZio(zio.schema.Schema.set(using s.zioSchema))
    given [A, B](using sa: Json[A], sb: Json[B]): Json[Map[A, B]] =
        fromZio(zio.schema.Schema.map(using sa.zioSchema, sb.zioSchema))

    // Option/Either
    given [A](using s: Json[A]): Json[Option[A]] =
        fromZio(zio.schema.Schema.option(using s.zioSchema))
    given [A, B](using sa: Json[A], sb: Json[B]): Json[Either[A, B]] =
        fromZio(zio.schema.Schema.either(using sa.zioSchema, sb.zioSchema))

    // Maybe - convert to/from Option
    given [A](using s: Json[A]): Json[Maybe[A]] =
        fromZio(zio.schema.Schema.option(using s.zioSchema).transform(
            opt => opt.fold(Absent)(Present(_)),
            maybe => maybe.toOption
        ))

end Json
