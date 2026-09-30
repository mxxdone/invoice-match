// Monotonic request/session generation. Long-running async work captures the
// current value when it starts and only applies its result if the value is
// still current, so an outdated login success or list response can never
// overwrite a newer session or query.
export class Generation {
  private value = 0;

  current(): number {
    return this.value;
  }

  next(): number {
    this.value += 1;
    return this.value;
  }

  isCurrent(token: number): boolean {
    return token === this.value;
  }
}
