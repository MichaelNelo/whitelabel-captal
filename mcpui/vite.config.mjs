import { defineConfig } from 'vite'

// Same pipeline as `client/vite.config.mjs` and for the same reason: Scala.js runs the Closure
// Compiler only over a single non-ESModule output, so an MCP App page that wants its script
// cached from CloudFront -- which needs ESModule -- loses Closure and gets Rollup plus terser
// instead. Rollup scope-hoists, which is what makes the names manglable again; terser mangles.
//
// `entryFileNames` is pinned because the page's <script src> is an absolute URL built by the
// server, and a hashed entry name would have to be discovered at render time. The chunks keep
// their hash: the entry references them, and the hash is what lets a chunk stay cached across a
// deploy that did not touch it.
export default defineConfig({
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    minify: 'terser',
    terserOptions: {
      compress: { passes: 3, unsafe: true },
      mangle: { toplevel: true },
    },
    // Two inputs, one per page, which is what lets Rollup hoist the `Bridge` protocol and the
    // Scala.js runtime into a chunk both import instead of duplicating them.
    //
    // Entry names are pinned and chunk names are hashed, and the split is deliberate: the server
    // renders `<script src=".../ui/upload.js">` as a constant and must not know anything about the
    // content, so the entry needs a predictable name and gets `must-revalidate` — the browser asks
    // and takes a 304 when nothing changed. The chunks are referenced by the entry, never by hand,
    // so they can carry a content hash and be cached immutably. That is also why no separate
    // manifest is needed: the entry *is* the mapping from a stable name to the hashed chunks, and
    // it costs one request instead of two.
    rollupOptions: {
      input: {
        upload: 'src/upload.js',
        unifi: 'src/unifi.js',
      },
      output: {
        entryFileNames: '[name].js',
        // `chunk-` rather than `[name]-`: Rollup names a chunk after whichever module it grew
        // from, which here produced `whitelabel.captal.mcpui.-Bridge_-<hash>.js` — the package
        // layout in a public CDN URL, for no gain.
        chunkFileNames: 'chunk-[hash].js',
      },
    },
    target: 'es2020',
    assetsDir: '.',
    modulePreload: false,
  },
})
