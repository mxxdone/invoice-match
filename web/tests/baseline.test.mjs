import assert from "node:assert/strict";
import test from "node:test";
import { readFile } from "node:fs/promises";

test("web baseline exposes a health route", async () => {
  const route = await readFile(new URL("../src/app/api/health/route.ts", import.meta.url), "utf8");
  assert.match(route, /export function GET\(/);
});
