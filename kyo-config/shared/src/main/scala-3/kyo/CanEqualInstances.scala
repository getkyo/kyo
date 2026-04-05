package kyo

// CanEqual instances for sealed hierarchies, needed for Scala 3 strict equality.
// These are in scala-3/ because CanEqual is not available in Scala 2.13.

given CanEqual[Flag.Source, Flag.Source]                           = CanEqual.derived
given CanEqual[Flag.ReloadResult, Flag.ReloadResult]               = CanEqual.derived
given CanEqual[Rollout.Selector, Rollout.Selector]                 = CanEqual.derived
given CanEqual[DynamicFlag.HistoryEntry, DynamicFlag.HistoryEntry] = CanEqual.derived
