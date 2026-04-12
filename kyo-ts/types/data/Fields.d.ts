export declare class Fields {
  readonly fields: Field<unknown, unknown>[];
  readonly names: Set<string>;

  static derive<A>(): Fields<A>;
  static fields<A>(): Field<unknown, unknown>[];
  static foreach<A, F>(fn: (x1: string, x2: F<unknown>) => void): void;
  static names<A>(): Set<string>;
};