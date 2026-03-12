package kyo

abstract class UIBackend:
    def render(ui: UI, theme: Theme = Theme.Default)(using Frame): UISession < (Async & Scope)
end UIBackend
