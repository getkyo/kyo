package kyo

import scala.util.control.NoStackTrace

class Closed(resource: String, createdAt: Frame, details: String = "")(using Frame)
    extends KyoException(render"$resource created at ${createdAt.position.show} is closed.", details)
    with NoStackTrace
