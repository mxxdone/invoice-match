const assert = require("node:assert/strict");
const http = require("node:http");
const test = require("node:test");
const { handler } = require("./server");

test("health is deterministic and other routes are absent", async () => {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  try {
    const port = server.address().port;
    const health = await fetch(`http://127.0.0.1:${port}/health`);
    assert.equal(health.status, 200);
    assert.deepEqual(await health.json(), { status: "UP" });
    const missing = await fetch(`http://127.0.0.1:${port}/payments`);
    assert.equal(missing.status, 404);
  } finally {
    server.close();
  }
});
