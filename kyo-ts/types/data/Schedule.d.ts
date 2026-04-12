export declare class Schedule {
  andThen(that: Schedule): Schedule;
  delay(duration: Duration): Schedule;
  readonly forever: Schedule;
  jitter(factor: number): Schedule;
  max(that: Schedule): Schedule;
  maxDuration(maxDuration: Duration): Schedule;
  min(that: Schedule): Schedule;
  next(now: Instant): Maybe<[Duration, Schedule]>;
  repeat(n: number): Schedule;
  readonly show: string;
  take(n: number): Schedule;

  readonly done: Schedule;
  readonly forever: Schedule;
  readonly immediate: Schedule;
  readonly never: Schedule;
};