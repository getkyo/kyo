export declare class TRef {
  get(): Kyo<A, STM>;
  set(v: A): Kyo<void, STM>;
  update<S>(f: (x1: A) => Kyo<A, S>): Kyo<void, STM & S>;
  use<B, S>(f: (x1: A) => Kyo<B, S>): Kyo<B, STM & S>;

  static init<A>(value: A): Kyo<TRef<A>, Sync>;
  static initWith<A, B, S>(value: A, f: (x1: TRef<A>) => Kyo<B, S>): Kyo<B, Sync & S>;
};