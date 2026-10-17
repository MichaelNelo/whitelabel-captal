// Rollup's entry for the upload page, and the only reason it exists: the linker's public module
// exports `start` but nothing calls it. A Scala.js module with a `main` runs on load, but `main`
// yields one module initializer and these pages need two public modules out of one linker run.
//
// A stub rather than an inline <script> in the HTML, because an inline script needs
// 'unsafe-inline' in the CSP and the page gets no such grant. This way the markup carries a plain
// <script type="module" src="…"> pointing at the CDN.
import { start } from './upload-app.js'

start()
