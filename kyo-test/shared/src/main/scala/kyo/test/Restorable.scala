package kyo.test

import kyo.Abort
import kyo.Env
import kyo.Frame

trait Restorable extends Serializable:
    // Converted: def save(implicit trace: Frame): UIO[UIO[Unit]] becomes a nested Kyo effect type.
    def save(implicit trace: Frame): (Unit < Env[Any] & Abort[Nothing]) < (Env[Any] & Abort[Nothing])
