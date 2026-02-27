package kyo

abstract class UIBackend:
    def render(ui: UI)(using Frame): UISession < (Async & Scope)
end UIBackend
