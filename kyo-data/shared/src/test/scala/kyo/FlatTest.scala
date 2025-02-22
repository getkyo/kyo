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

end FlatTest
