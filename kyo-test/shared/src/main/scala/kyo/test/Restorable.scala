package kyo.test

import kyo.*

trait Restorable extends Serializable:
    // Converted: def save(implicit trace: Trace): UIO[Unit < Any] becomes a nested Kyo effect type.
    def save(implicit trace: Trace): (Unit < Env[Any] & Abort[Nothing]) < (Env[Any] & Abort[Nothing])
