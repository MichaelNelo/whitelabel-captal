function handler(event) {
  var uri = event.request.uri;
  var lastSegment = uri.split('/').pop();

  // File request (has extension): pass through unchanged.
  if (lastSegment && lastSegment.indexOf('.') !== -1) {
    return event.request;
  }

  // /<slug> with no trailing slash: 301 redirect to /<slug>/
  // so the browser updates its URL bar and relative URLs in the SPA resolve under /<slug>/.
  // The query string MUST be preserved here — UniFi captive-portal lands users on
  // /<slug>?id=...&ap=...&click_id=...&ssid=..., and the SPA reads those params from
  // window.location.search to build the X-Client-Mac/X-Ap-Mac/X-Click-Id/X-Ssid headers
  // for /api/status. Dropping them on the redirect leaves /api/status with no portal
  // context and the session resolver returns ApiError.SessionMissing.
  if (uri !== '/' && !uri.endsWith('/')) {
    return {
      statusCode: 301,
      statusDescription: 'Moved Permanently',
      headers: { location: { value: uri + '/' + buildQueryString(event.request.querystring) } }
    };
  }

  // /<slug>/... with no extension: rewrite to /<slug>/index.html for SPA fallback.
  var parts = uri.split('/');
  if (parts.length >= 2 && parts[1]) {
    event.request.uri = '/' + parts[1] + '/index.html';
  }
  return event.request;
}

function buildQueryString(qs) {
  if (!qs) return '';
  var keys = Object.keys(qs);
  if (keys.length === 0) return '';
  var pairs = [];
  for (var i = 0; i < keys.length; i++) {
    var k = keys[i];
    var v = qs[k];
    if (v.multiValue) {
      for (var j = 0; j < v.multiValue.length; j++) {
        pairs.push(k + '=' + v.multiValue[j].value);
      }
    } else {
      pairs.push(k + '=' + v.value);
    }
  }
  return '?' + pairs.join('&');
}
