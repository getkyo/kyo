package io.getkyo.compat

import sbt.*

/** sbt 2 side of what differs between the sbt 1 and sbt 2 builds. */
private[compat] object SbtCompat:

    type ProjectMatrix = sbt.ProjectMatrix

    val requiredPlugins: Plugins = sbt.plugins.JvmPlugin

    /** Binary, as with `%%`: sbt 2 adds the platform suffix from the row's `platform` setting. */
    val platformCrossVersion: Def.Initialize[CrossVersion] = Def.setting(CrossVersion.binary)
end SbtCompat
