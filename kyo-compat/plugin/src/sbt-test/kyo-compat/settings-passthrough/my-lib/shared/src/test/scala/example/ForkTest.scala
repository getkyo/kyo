package example

import org.scalatest.funsuite.AnyFunSuite

// Asserts that Test/javaOptions += "-Dfoo=bar" actually flowed to the
// forked test JVM. If fork or javaOptions did not propagate to the
// generated cell, System.getProperty("foo") will be null and this test
// fails — which makes the scripted-test's `checkForkTest` task fail.
class ForkTest extends AnyFunSuite:
    test("javaOptions flow through fork"):
        assert(System.getProperty("foo") == "bar")
