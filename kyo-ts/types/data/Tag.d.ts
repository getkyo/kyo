export declare class Tag {

  static apply<A>(): Tag<A>;
  static derive<A>(): Tag<A>;
  static erased<A>(): Tag<unknown>;
  static hash<A>(): number;
  static show<A>(): string;
  static tpe<A>(): Type<A>;
};