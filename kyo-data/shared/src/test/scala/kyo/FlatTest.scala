package kyo

import kyo.*
import kyo.Tag

class FlatTest extends Test:

    "ok" - {
        "concrete" in {
            implicitly[Flat[Int]]
            implicitly[Flat[String]]
            implicitly[Flat[Thread]]
            succeed
        }
        "derived from Tag" in {
            def test[A: Tag] =
                implicitly[Flat[A]]
                succeed
            test[Int]
        }
        "derived from Flat" - {
            "simple" in {
                def test[A: Flat] =
                    implicitly[Flat[A]]
                    succeed
                test[Int]
            }
            "union item" in {
                def test[A: Flat] =
                    implicitly[Flat[Int | A]]
                    succeed
                test[Int]
            }
            "intersection item" in {
                def test[A: Flat] =
                    implicitly[Flat[Thread & A]]
                    succeed
                test[Int]
            }
        }
        "kyo data" in {
            implicitly[Flat[Maybe[Int]]]
            implicitly[Flat[Result[String, Int]]]
            implicitly[Flat[TypeMap[String & Int]]]
            implicitly[Flat[Duration]]
            implicitly[Flat[Text]]
            succeed
        }
    }

    "nok" - {

        "pending type" in pendingUntilFixed {
            typeCheckFailure("implicitly[Flat[Int < Any]]")(_ => false)
            typeCheckFailure("implicitly[Flat[Int < Options]]")(_ => false)
            typeCheckFailure("implicitly[Flat[Int < Nothing]]")(_ => false)
            ()
        }

        "nested" in pendingUntilFixed {
            typeCheckFailure("implicitly[Flat[Int < IOs < IOs]]")(_ => false)
            typeCheckFailure("implicitly[Flat[Any < IOs < IOs]]")(_ => false)
            ()
        }

        "nested w/ mismatch" in pendingUntilFixed {
            typeCheckFailure("implicitly[Flat[Int < Options < IOs]]")(_ => false)
            typeCheckFailure("implicitly[Flat[Int < IOs < Options]]")(_ => false)
            ()
        }

        "generic" in {
            typeCheckFailure("def f[A] = implicitly[Flat[A]]")(_.contains("No given instance of type kyo.Flat[A]"))
            typeCheckFailure("def f[A] = implicitly[Flat[A | Int]]")(_.contains("No given instance of type kyo.Flat[A | Int]"))
        }

        "generic pending" in pendingUntilFixed {
            typeCheckFailure("def f[A] = implicitly[Flat[A < Options]]")(_ => false)
            typeCheckFailure("def f[A] = implicitly[Flat[A < Any]]")(_ => false)
            ()
        }

        "any" in pendingUntilFixed {
            typeCheckFailure("implicitly[Flat[Any]]")(_.contains("No given instance of type kyo.Flat[Any]"))
            typeCheckFailure("implicitly[Flat[Any < IOs]]")(_ => false)
            ()
        }
    }

end FlatTest
