Decision 63: CdpBackendTest.getTargets malformed-response fix
Rationale: GetTargetsResult(targetInfos: Seq[TargetInfo] = Seq.empty) accepts {} as valid. Test now returns BadGetTargetsResult(targetInfos: Int = 42) so the schema rejects the integer-where-sequence-expected type mismatch and the typed decode raises BrowserProtocolErrorException.
Time: 2026-05-29T00:00Z
