// Test proxy for the emulator (see CLAUDE.md, Testing): everything goes to a local backend
// (127.0.0.1:3999, a copy of the real DB) so playing doesn't write real watch state, except the
// read-only streams (GET/HEAD all/play/<id>), which come from the real server: the local one has no
// media files. Usage: node tools/stream-proxy.js <real server host>; in the app use http://10.0.2.2:3998
const http = require('http');
const https = require('https');
const REMOTE = process.argv[2];
if (!REMOTE) {
  console.error('usage: node tools/stream-proxy.js <real server host>');
  process.exit(1);
}
http.createServer((req, res) => {
  const stream = /all\/play\/\d+/.test(decodeURIComponent(req.url));
  if (stream && req.method !== 'GET' && req.method !== 'HEAD') {
    res.writeHead(405);
    return res.end();
  }
  const headers = { ...req.headers, host: stream ? REMOTE : '127.0.0.1:3999' };
  const options = { path: req.url, method: req.method, headers };
  const upstream = stream
    ? https.request({ ...options, host: REMOTE }, pipe)
    : http.request({ ...options, host: '127.0.0.1', port: 3999 }, pipe);
  function pipe(up) {
    res.writeHead(up.statusCode, up.headers);
    up.pipe(res);
  }
  upstream.on('error', (e) => { if (!res.headersSent) res.writeHead(502); res.end(String(e)); });
  res.on('close', () => upstream.destroy());
  req.pipe(upstream);
  console.log(new Date().toISOString().slice(11, 19), stream ? 'REMOTE' : 'local ', req.method, req.url, req.headers.range || '');
}).listen(3998, '127.0.0.1');
