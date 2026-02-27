package kyo

class UISession(fiber: Fiber[Unit, Scope], rendered: Signal[UI]):
    def await(using Frame): Unit < (Async & Scope) = fiber.get
    def stop(using Frame): Unit < Sync             = fiber.interrupt.unit
    def awaitRender(using Frame): UI < Async       = rendered.next
    def onRender(f: UI => Unit < Async)(using Frame): Unit < (Async & Scope) =
        rendered.streamChanges.foreach(f)
end UISession
