export declare class TRefImpl {
  set(v: A): Kyo<void, STM>;
  use<B, S>(f: (x1: A) => Kyo<B, S>): Kyo<B, STM & S>;
};