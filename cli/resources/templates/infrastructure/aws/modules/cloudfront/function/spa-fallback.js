function handler(event) {
  var uri = event.request.uri;
  var lastSegment = uri.split('/').pop();

  // File request (has extension): pass through unchanged.
  if (lastSegment && lastSegment.indexOf('.') !== -1) {
    return event.request;
  }

  // /<slug> with no trailing slash: 301 redirect to /<slug>/
  // so the browser updates its URL bar and relative URLs in the SPA resolve under /<slug>/.
  if (uri !== '/' && !uri.endsWith('/')) {
    return {
      statusCode: 301,
      statusDescription: 'Moved Permanently',
      headers: { location: { value: uri + '/' } }
    };
  }

  // /<slug>/... with no extension: rewrite to /<slug>/index.html for SPA fallback.
  var parts = uri.split('/');
  if (parts.length >= 2 && parts[1]) {
    event.request.uri = '/' + parts[1] + '/index.html';
  }
  return event.request;
}
