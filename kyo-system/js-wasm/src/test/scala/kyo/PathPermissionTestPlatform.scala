package kyo

import kyo.internal.NodeFs
import scala.scalajs.js

private[kyo] object PathPermissionTestPlatform:

    def setPermissions(path: Path, permissions: String): Unit =
        val mode = permissions.zipWithIndex.foldLeft(0) { case (mode, (permission, index)) =>
            if permission == '-' then mode else mode | (1 << (8 - index))
        }
        NodeFs.chmodSync(path.unsafe.hostPath.get, mode)
    end setPermissions

    def permissions(path: Path): String =
        val mode = NodeFs.statSync(path.unsafe.hostPath.get).asInstanceOf[js.Dynamic].mode.asInstanceOf[Int]
        "rwxrwxrwx".zipWithIndex.map { (permission, index) =>
            if (mode & (1 << (8 - index))) == 0 then '-' else permission
        }.mkString
    end permissions

end PathPermissionTestPlatform
