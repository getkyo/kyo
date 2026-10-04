import kyo.test.Test

/** Run by both forks of the build; `fixture.mode` decides whether this fork fails or cancels the leaf. */
class SharedSuite extends Test[Any]:
    "leaf" in {
        if java.lang.System.getProperty("fixture.mode") == "fail" then assert(1 + 1 == 3)
        else cancel("this fork's runtime is absent")
    }
end SharedSuite
