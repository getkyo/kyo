export declare class Instant {
  between(start: Instant, end: Instant): boolean;
  clamp(min: Instant, max: Instant): Instant;
  max(other: Instant): Instant;
  min(other: Instant): Instant;
  minus(other: Instant): Duration;
  plus(duration: Duration): Instant;
  show(): string;
  toDuration(): Duration;
  toJava(): Instant;
  truncatedTo(unit: Units & Truncatable): Instant;

  readonly given_CanEqual_Instant_Instant: CanEqual<Instant, Instant>;
  readonly given_Ordering_Instant: given_Ordering_Instant;
  static parse(text: CharSequence): Result<DateTimeParseException, Instant>;
};