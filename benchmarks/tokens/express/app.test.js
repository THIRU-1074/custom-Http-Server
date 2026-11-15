const test = require('node:test');
const assert = require('node:assert');
const { create } = require('./app');

test('crud, auth and errors', async () => {
  const server = create().listen(0);
  const base = `http://localhost:${server.address().port}`;
  const call = async (method, path, body, auth = 'Bearer secret') =>
    (await fetch(base + path, {
      method,
      headers: { 'Content-Type': 'application/json', ...(auth && { Authorization: auth }) },
      body,
    })).status;

  assert.strictEqual(await call('GET', '/todos', undefined, null), 401);
  assert.strictEqual(await call('POST', '/todos', '{}'), 400);
  assert.strictEqual(await call('POST', '/todos', '{"title":"a"}'), 201);
  assert.strictEqual(await call('GET', '/todos/1'), 200);
  assert.strictEqual(await call('GET', '/todos/9'), 404);
  assert.strictEqual(await call('DELETE', '/todos/1'), 204);
  server.close();
});
