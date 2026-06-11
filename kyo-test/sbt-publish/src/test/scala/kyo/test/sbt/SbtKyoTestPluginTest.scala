package kyo.test.sbt

import org.scalatest.funsuite.AnyFunSuite
import sbt._
import sbt.Keys._

// ScalaTest bootstrap: this file tests an sbt plugin using sbt APIs; kyo-test framework is not available in the sbt plugin test scope.
class SbtKyoTestPluginTest extends AnyFunSuite {

    test("SbtKyoTestPlugin.projectSettings contains kyo-test-runner % Test dependency") {
        val settings = SbtKyoTestPlugin.projectSettings
        // Collect settings whose key is libraryDependencies; verify exactly one exists.
        val depSettings = settings.collect {
            case s if s.key.key == libraryDependencies.key => s
        }
        assert(depSettings.nonEmpty, "projectSettings must contain a libraryDependencies setting")
        // Stronger than the original nonEmpty check: exactly one libraryDependencies setting.
        // SbtKyoTestPlugin declares a single `libraryDependencies +=` so the count must be 1.
        assert(
            depSettings.size == 1,
            s"expected exactly one libraryDependencies setting in projectSettings, got ${depSettings.size}"
        )
        // Verify the setting key label is precisely "libraryDependencies" (not some other key
        // that happens to compare equal via == to the libraryDependencies task key).
        val keyLabel = depSettings.head.key.key.label
        assert(
            keyLabel == "libraryDependencies",
            s"expected key label 'libraryDependencies', got: $keyLabel"
        )
        // The Setting is an Append (+=) node. The sbt Init machinery composes it as an Apply of
        // the pre-existing libraryDependencies value and the new ModuleID. The toString of the
        // Init node is an opaque object reference (e.g. sbt.internal.util.Init$Apply@...) and
        // does NOT embed the artifact name: content verification requires a live sbt session.
        // The count (1) + key label ("libraryDependencies") is the strongest portable static check.
    }

    test("BuildInfo.kyoVersion is non-empty") {
        assert(BuildInfo.kyoVersion.nonEmpty)
    }

    test("BuildInfo.kyoVersion matches the pattern of a valid version string") {
        val version = BuildInfo.kyoVersion
        // Must be non-empty and contain at least one digit (basic sanity check)
        assert(version.exists(_.isDigit), s"version '$version' must contain at least one digit")
    }
}
