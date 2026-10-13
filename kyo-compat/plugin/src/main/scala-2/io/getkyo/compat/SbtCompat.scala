package io.getkyo.compat

import org.portablescala.sbtplatformdeps.PlatformDepsPlugin
import sbt.*

/** sbt 1 side of what differs between the sbt 1 and sbt 2 builds. */
private[compat] object SbtCompat {

    type ProjectMatrix = sbt.internal.ProjectMatrix

    val requiredPlugins: Plugins = sbt.plugins.JvmPlugin && sbtprojectmatrix.ProjectMatrixPlugin

    /** The cross version `%%%` uses, set per row by the Scala.js and Scala Native plugins. */
    val platformCrossVersion: Def.Initialize[CrossVersion] = PlatformDepsPlugin.autoImport.platformDepsCrossVersion
}
