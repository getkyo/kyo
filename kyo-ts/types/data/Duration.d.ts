export declare class Duration {
  max(that: Duration): Duration;
  min(that: Duration): Duration;
  minus(that: Duration): Duration;
  plus(that: Duration): Duration;
  show(): string;
  times(factor: number): Duration;
  to(unit: Units): number;
  toDays(): number;
  toHours(): number;
  toJava(): Duration;
  toMicros(): number;
  toMillis(): number;
  toMinutes(): number;
  toMonths(): number;
  toNanos(): number;
  toScala(): Duration;
  toSeconds(): number;
  toWeeks(): number;
  toYears(): number;

  readonly given_CanEqual_Duration_Duration: CanEqual<Duration, Duration>;
};