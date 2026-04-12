export declare class HttpHeaders {
  add(name: string, value: string): HttpHeaders;
  addCookie<A>(name: string, cookie: HttpCookie<A>): HttpHeaders;
  concat(other: HttpHeaders): HttpHeaders;
  contains(name: string): boolean;
  cookie(name: string): Maybe<string>;
  cookie(name: string, strict: boolean): Maybe<string>;
  cookies(strict: boolean): [string, string][];
  cookies(): [string, string][];
  foldLeft<A>(init: A, f: (x1: A, x2: string, x3: string) => A): A;
  foreach(f: (x1: string, x2: string) => void): void;
  get(name: string): Maybe<string>;
  getAll(name: string): string[];
  isEmpty(): boolean;
  nonEmpty(): boolean;
  remove(name: string): HttpHeaders;
  responseCookie(name: string): Maybe<string>;
  set(name: string, value: string): HttpHeaders;
  size(): number;

  readonly empty: HttpHeaders;
  readonly given_CanEqual_HttpHeaders_HttpHeaders: CanEqual<HttpHeaders, HttpHeaders>;
};