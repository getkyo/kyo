export declare class HttpUrl {
  readonly baseUrl: string;
  readonly full: string;
  readonly host: string;
  readonly path: string;
  readonly port: number;
  query(name: string): Maybe<string>;
  queryAll(name: string): string[];
  readonly rawQuery: Maybe<string>;
  readonly scheme: Maybe<string>;
  readonly ssl: boolean;
};