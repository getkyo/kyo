package kyo

import kyo.test.Test
import scala.compiletime.testing.typeCheckErrors

/** Compile-gate tests verifying that renamed `TastyError` variants are accessible under their new names
  * and old abbreviated names do not type-check.
  *
  * Each leaf checks that a renamed variant type-checks under its new name and fails to type-check under the old name.
  */
class TastyErrorRenameTest extends Test[Any]:

    "FullNameCollisionError exists under new name" in {
        val e: TastyError = TastyError.FullNameCollisionError("shop.Dog")
        assert(e.isInstanceOf[TastyError.FullNameCollisionError])
    }

    "FqnCollisionError gone (old name does not compile)" in {
        val errors = typeCheckErrors("val e: kyo.TastyError.FqnCollisionError = ???")
        assert(errors.nonEmpty)
    }

    "InvalidFullName exists under new name" in {
        val e: TastyError = TastyError.InvalidFullName("", "fullName must be non-empty")
        assert(e.isInstanceOf[TastyError.InvalidFullName])
    }

    "InvalidFqn gone (old name does not compile)" in {
        val errors = typeCheckErrors("val e: kyo.TastyError.InvalidFqn = ???")
        assert(errors.nonEmpty)
    }

    "FullNameCollisionError.fullName field accessible" in {
        val e: TastyError.FullNameCollisionError = TastyError.FullNameCollisionError("shop.Dog")
        assert(e.fullName == "shop.Dog")
    }

    "TastyError enum has multiple variants" in {
        assert(TastyError.FileNotFound("x").isInstanceOf[TastyError])
        assert(TastyError.FullNameCollisionError("x").isInstanceOf[TastyError])
        assert(TastyError.InvalidFullName("x", "r").isInstanceOf[TastyError])
    }

end TastyErrorRenameTest
