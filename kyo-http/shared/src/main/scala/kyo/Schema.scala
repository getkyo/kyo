package kyo

import scala.deriving.Mirror

/** JSON encoding/decoding type class that bridges typed Scala values and HTTP wire format.
  *
  * Schema is the mechanism that enables route-based handlers (auto-deserializing inputs, auto-serializing outputs) and typed client calls
  * (auto-serializing requests, auto-deserializing responses). Wraps zio-schema internally without exposing ZIO types. Instances are derived
  * automatically for case classes and sealed traits via `derives Schema`. Built-in instances cover primitives, collections (`Seq`, `List`,
  * `Vector`, `Set`, `Map`), `Option`, `Either`, and `Maybe`.
  *
  * Note: `encode` can throw on encoding failure — it is not effect-tracked. `decode` throws `IllegalArgumentException` on failure. String
  * decoding falls back to raw text if JSON parsing fails, supporting both JSON-encoded strings and plain text bodies. Unit decoding accepts
  * empty bodies and JSON null.
  *
  * @tparam A
  *   The type to encode/decode
  *
  * @see
  *   [[kyo.HttpRoute.input]]
  * @see
  *   [[kyo.HttpRoute.output]]
  * @see
  *   [[kyo.HttpRequest.bodyAs]]
  * @see
  *   [[kyo.HttpResponse.bodyAs]]
  */
abstract class Schema[A]:
    private[kyo] def zpiSchema: zio.schema.Schema[A]

    def encode(value: A): String =
        zio.schema.codec.JsonCodec.jsonEncoder(zpiSchema).encodeJson(value, None).toString

    def decode(json: String): Result[String, A] =
        zio.schema.codec.JsonCodec.jsonDecoder(zpiSchema).decodeJson(json) match
            case Right(a)    => Result.succeed(a)
            case Left(error) => Result.fail(s"JSON decode error: $error")
end Schema

object Schema:

    def apply[A](using schema: Schema[A]): Schema[A] = schema

    private def wrap[A](zs: zio.schema.Schema[A]): Schema[A] =
        new Schema[A]:
            val zpiSchema: zio.schema.Schema[A] = zs

    // Derivation - delegates to zio-schema
    inline given derived[A](using m: Mirror.Of[A]): Schema[A] =
        wrap(zio.schema.DeriveSchema.gen[A])

    // Primitive instances
    given Schema[Int]     = wrap(zio.schema.Schema[Int])
    given Schema[Long]    = wrap(zio.schema.Schema[Long])
    given Schema[Boolean] = wrap(zio.schema.Schema[Boolean])
    given Schema[Double]  = wrap(zio.schema.Schema[Double])
    given Schema[Float]   = wrap(zio.schema.Schema[Float])
    given Schema[Short]   = wrap(zio.schema.Schema[Short])
    given Schema[Byte]    = wrap(zio.schema.Schema[Byte])
    given Schema[Char]    = wrap(zio.schema.Schema[Char])

    // String: if JSON decode fails, return raw text (supports both JSON strings and plain text)
    given Schema[String] = new Schema[String]:
        val zpiSchema: zio.schema.Schema[String] = zio.schema.Schema[String]
        override def decode(json: String): Result[String, String] =
            zio.schema.codec.JsonCodec.jsonDecoder(zpiSchema).decodeJson(json) match
                case Right(a) => Result.succeed(a)
                case Left(_)  => Result.succeed(json) // Return raw text as fallback

    // Unit: accept empty body or JSON null
    given Schema[Unit] = new Schema[Unit]:
        val zpiSchema: zio.schema.Schema[Unit] = zio.schema.Schema[Unit]
        override def decode(json: String): Result[String, Unit] =
            if json.isEmpty || json.trim.isEmpty || json.trim == "null" then Result.succeed(())
            else
                zio.schema.codec.JsonCodec.jsonDecoder(zpiSchema).decodeJson(json) match
                    case Right(a)    => Result.succeed(a)
                    case Left(error) => Result.fail(s"JSON decode error: $error")

    // Collection instances
    given [A](using s: Schema[A]): Schema[Seq[A]] =
        wrap(zio.schema.Schema.list(using s.zpiSchema).transform(_.toSeq, _.toList))
    given [A](using s: Schema[A]): Schema[List[A]] =
        wrap(zio.schema.Schema.list(using s.zpiSchema))
    given [A](using s: Schema[A]): Schema[Vector[A]] =
        wrap(zio.schema.Schema.vector(using s.zpiSchema))
    given [A](using s: Schema[A]): Schema[Set[A]] =
        wrap(zio.schema.Schema.set(using s.zpiSchema))
    given [A, B](using sa: Schema[A], sb: Schema[B]): Schema[Map[A, B]] =
        wrap(zio.schema.Schema.map(using sa.zpiSchema, sb.zpiSchema))

    // Option/Either
    given [A](using s: Schema[A]): Schema[Option[A]] =
        wrap(zio.schema.Schema.option(using s.zpiSchema))
    given [A, B](using sa: Schema[A], sb: Schema[B]): Schema[Either[A, B]] =
        wrap(zio.schema.Schema.either(using sa.zpiSchema, sb.zpiSchema))

    // Maybe - convert to/from Option
    given [A](using s: Schema[A]): Schema[Maybe[A]] =
        wrap(zio.schema.Schema.option(using s.zpiSchema).transform(
            opt => opt.fold(Absent)(Present(_)),
            maybe => maybe.toOption
        ))

end Schema
