export declare class Tick {
  value(): number;

  readonly given_CanEqual_Tick_Tick: CanEqual<Tick, Tick>;
  static next(): Tick;
  static withCurrent<A, S>(f: (x1: Tick) => Kyo<A, S>): Kyo<A, S & Sync>;
  static withCurrentOrNext<A, S>(f: (x1: AllowUnsafe) => (x1: Tick) => Kyo<A, S>): Kyo<A, S & Sync>;
  static withNext<A, S>(f: (x1: Tick) => Kyo<A, S>): Kyo<A, S & Sync>;
};