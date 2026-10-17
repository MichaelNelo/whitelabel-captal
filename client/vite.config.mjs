import { defineConfig } from 'vite'

// Rollup, which Vite uses for production builds, does the chunking and scope hoisting that make
// the per-route split affordable; terser does the mangling, being better at it than esbuild's own
// minifier. Both are reached through one invocation instead of two tools and a loop.
//
// `entryFileNames` is pinned for every entry, not just the portal's: `index.html` and
// `maintenance.html` are static templates naming `./main.js` and `./maintenance.js`, and the two
// MCP App pages carry a `<script src>` the server renders as a constant from a base URL, so it
// cannot discover a hash at render time either. The chunks keep theirs: they are referenced by an
// entry and never by hand, and the hash is what lets them be cached past a deploy that did not
// touch them.
//
// Four entries out of one linker run. `main` is the linker's own main module and starts itself;
// the other three are the stubs in `client/entries/`, which exist because one run yields one
// module initializer and the portal has it.
export default defineConfig({
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    minify: 'terser',
    terserOptions: {
      compress: { passes: 3, unsafe: true },
      mangle: { toplevel: true },
    },
    rollupOptions: {
      input: {
        main: 'src/main.js',
        upload: 'src/upload.js',
        unifi: 'src/unifi.js',
        maintenance: 'src/maintenance.js',
      },
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: '[name]-[hash].js',
      },
    },
    // Matches the linker's ESVersion: BigInt-backed Longs need ES2020 to exist.
    target: 'es2020',
    assetsDir: '.',
    modulePreload: false,
  },
})
