package kyo

import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

private[kyo] object PathPermissionTestPlatform:

    def setPermissions(path: Path, permissions: String): Unit =
        Files.setPosixFilePermissions(Paths.get(path.unsafe.hostPath.get), PosixFilePermissions.fromString(permissions))
        ()

    def permissions(path: Path): String =
        PosixFilePermissions.toString(Files.getPosixFilePermissions(Paths.get(path.unsafe.hostPath.get)))

end PathPermissionTestPlatform
