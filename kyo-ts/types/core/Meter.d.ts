export declare class Meter {
  availablePermits(): Kyo<number, Async & Abort<Closed>>;
  close(): Kyo<boolean, Sync>;
  closed(): Kyo<boolean, Sync>;
  pendingWaiters(): Kyo<number, Async & Abort<Closed>>;
  run<A, S>(v: () => Kyo<A, S>): Kyo<A, S & Async & Abort<Closed>>;
  tryRun<A, S>(v: () => Kyo<A, S>): Kyo<Maybe<A>, S & Async & Abort<Closed>>;

  static initMutex(): Kyo<Meter, Sync & Scope>;
  static initMutexUnscoped(): Kyo<Meter, Sync>;
  static pipeline<S>(meters: Kyo<Meter, Sync & S>[]): Kyo<Meter, Sync & S>;
  static useMutex<A, S>(f: (x1: Meter) => Kyo<A, S>): Kyo<A, Sync & S>;
};