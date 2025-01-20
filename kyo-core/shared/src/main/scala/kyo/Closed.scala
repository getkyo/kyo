package kyo

import scala.util.control.NoStackTrace

class Closed(resource: Text, createdAt: Frame, details: Text = "")(using Frame)
    extends KyoException(t"$resource created at ${createdAt.position.show} is closed.", details)
    with NoStackTrace
