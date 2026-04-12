export declare class Clock {
  deadline(duration: Duration): Kyo<Deadline, Sync>;
  let_<A, S>(f: () => Kyo<A, S>): Kyo<A, S>;
  now(): Kyo<Instant, Sync>;
  nowMonotonic(): Kyo<Duration, Sync>;
  stopwatch(): Kyo<Stopwatch, Sync>;
  readonly unsafe: Unsafe;

  static apply(unsafe: Unsafe): Clock;
  static get(): Kyo<Clock, unknown>;
  readonly live: Clock;
  static now(): Kyo<Instant, Sync>;
  static nowMonotonic(): Kyo<Duration, Sync>;
  static stopwatch(): Kyo<Stopwatch, Sync>;
  static use<A, S>(f: (x1: Clock) => Kyo<A, S>): Kyo<A, S>;
  static withTimeControl<A, S>(f: (x1: TimeControl) => Kyo<A, S>): Kyo<A, Sync & S>;
};