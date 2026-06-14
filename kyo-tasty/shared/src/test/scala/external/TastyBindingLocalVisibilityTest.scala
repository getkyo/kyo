package external

/** Negative-compilation test: Tasty.bindingLocal and Tasty.global are not accessible
  * outside the kyo package.
  *
  * This file lives in package external (outside kyo) so that typeCheckErrors verifies
  * the private[kyo] restriction on bindingLocal and global. typeCheckErrors called from
  * within package kyo cannot detect this restriction because private[kyo] is accessible
  * from that package.
  */
class TastyBindingLocalVisibilityTest extends kyo.test.Test[Any]:

    "Tasty.bindingLocal is inaccessible from outside the kyo package" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.Tasty.bindingLocal")
        assert(errors.nonEmpty, "Tasty.bindingLocal must not be accessible from outside package kyo (private[kyo])")
        succeed
    }

    "Tasty.global is inaccessible from outside the kyo package" in {
        val errors = compiletime.testing.typeCheckErrors("kyo.Tasty.global")
        assert(errors.nonEmpty, "Tasty.global must not be accessible from outside package kyo (private[kyo])")
        succeed
    }

end TastyBindingLocalVisibilityTest
