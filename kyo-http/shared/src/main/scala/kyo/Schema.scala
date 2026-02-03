package kyo

import scala.deriving.Mirror

trait Schema[A]:
    def encode(value: A): String
    def decode(json: String): A
    def jsonSchema: String
end Schema

object Schema:
    def apply[A](using schema: Schema[A]): Schema[A] = schema

    inline def derived[A](using m: Mirror.Of[A]): Schema[A] =
        new Schema[A]:
            def encode(value: A): String = ???
            def decode(json: String): A  = ???
            def jsonSchema: String       = ???

    given Schema[Int] with
        def encode(value: Int): String = ???
        def decode(json: String): Int  = ???
        def jsonSchema: String         = ???
    end given

    given Schema[Long] with
        def encode(value: Long): String = ???
        def decode(json: String): Long  = ???
        def jsonSchema: String          = ???
    end given

    given Schema[String] with
        def encode(value: String): String = ???
        def decode(json: String): String  = ???
        def jsonSchema: String            = ???
    end given

    given Schema[Boolean] with
        def encode(value: Boolean): String = ???
        def decode(json: String): Boolean  = ???
        def jsonSchema: String             = ???
    end given

    given Schema[Unit] with
        def encode(value: Unit): String = ???
        def decode(json: String): Unit  = ???
        def jsonSchema: String          = ???
    end given

    given [A: Schema]: Schema[Seq[A]] with
        def encode(value: Seq[A]): String = ???
        def decode(json: String): Seq[A]  = ???
        def jsonSchema: String            = ???
    end given

    given [A: Schema]: Schema[Maybe[A]] with
        def encode(value: Maybe[A]): String = ???
        def decode(json: String): Maybe[A]  = ???
        def jsonSchema: String              = ???
    end given

end Schema
