// Unit tests for the viewer-request function, run with `node spa-fallback.test.js`.
//
// Here and not in the application repo's end-to-end suite, because this is where the function lives
// and a test over there would assert against a copy. LocalStack does execute viewer-request
// functions, so an integration test is possible too -- but it would exercise a distribution the
// Mill fixture builds rather than this configuration, which is a weaker thing to know.
//
// No framework: the function is pure, `handler(event)` returns a request or a response, and a
// dependency to assert that would be the largest thing in this directory.

const assert = require('node:assert');
const fs = require('node:fs');
const path = require('node:path');

// CloudFront evaluates the file as a module exposing `handler`; here it is loaded as a script and
// the function picked out of the resulting scope, which is the same code either way.
const source = fs.readFileSync(path.join(__dirname, 'spa-fallback.js'), 'utf8');
const handler = new Function(`${source}; return handler;`)();

const request = (uri, querystring = {}) => ({ request: { uri, querystring } });

let failures = 0;
const test = (name, fn) => {
  try {
    fn();
    console.log(`  ok  ${name}`);
  } catch (e) {
    failures += 1;
    console.log(`  FAIL ${name}\n       ${e.message}`);
  }
};

console.log('spa-fallback: internal prefixes');

// The reason this function has a test at all. Both prefixes were reachable: the bucket policy
// grants CloudFront the whole bucket, and the extension check used to pass any path with a dot in
// its last segment straight to the origin.
test('refuses a paused location\'s saved portal', () => {
  const out = handler(request('/_paused/cafe-centro/site.html'));
  assert.strictEqual(out.statusCode, 403);
});

test('refuses the shared bundle', () => {
  assert.strictEqual(handler(request('/bundle/main.js')).statusCode, 403);
  assert.strictEqual(handler(request('/bundle/index.html')).statusCode, 403);
});

test('refuses them before the extension check, which is what let them through', () => {
  // A path with no extension would have been rewritten rather than passed on, so a test that only
  // used extensionless paths would pass against the broken version too.
  assert.strictEqual(handler(request('/_paused/x/site.html')).statusCode, 403);
  assert.strictEqual(handler(request('/bundle/assets/app.css')).statusCode, 403);
});

test('matches the prefix, not the substring', () => {
  // A slug may legitimately contain either word. Anchoring at position 0 is what keeps
  // `/bundle-shop/index.html` a real location rather than a refused one.
  assert.ok(!handler(request('/bundle-shop/')).statusCode);
  assert.ok(!handler(request('/cafe/_paused/')).statusCode);
});

console.log('spa-fallback: the behaviour that was already there');

test('passes a real asset through untouched', () => {
  const out = handler(request('/cafe-centro/main.js'));
  assert.strictEqual(out.uri, '/cafe-centro/main.js');
  assert.strictEqual(out.statusCode, undefined);
});

test('rewrites an extensionless path under a slug to its index', () => {
  // With the trailing slash. Without one the redirect below takes it first, which is the ordering
  // this got wrong on the first attempt: `/cafe-centro/video` is a 301, not a rewrite.
  assert.strictEqual(handler(request('/cafe-centro/video/')).uri, '/cafe-centro/index.html');
});

test('redirects a slug with no trailing slash, keeping the query string', () => {
  // The captive portal lands guests on `/<slug>?id=…&ap=…`, and the SPA reads those to build the
  // headers `/api/status` resolves a session from. Dropping them is a session that never resolves.
  const out = handler(request('/cafe-centro', { id: { value: 'abc' }, ap: { value: '11:22' } }));
  assert.strictEqual(out.statusCode, 301);
  assert.ok(out.headers.location.value.startsWith('/cafe-centro/?'));
  assert.ok(out.headers.location.value.includes('id=abc'));
  assert.ok(out.headers.location.value.includes('ap=11:22'));
});

console.log(failures === 0 ? '\nall passed' : `\n${failures} failed`);
process.exit(failures === 0 ? 0 : 1);
