import { defineConfig } from 'vite'

// Rollup, which Vite uses for production builds, does the chunking and scope hoisting that make
// the per-route split affordable; terser does the mangling, being better at it than esbuild's own
// minifier. Both are reached through one invocation instead of two tools and a loop.
//
// `entryFileNames` is pinned because index.html is a static template that names `./main.js`. The
// chunks keep their hash: they are referenced by the entry, not by hand, and the hash is what lets
// them be cached past a deploy that did not touch them.
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
      input: 'src/main.js',
      output: {
        entryFileNames: 'main.js',
        chunkFileNames: '[name]-[hash].js',
      },
    },
    // Matches the linker's ESVersion: BigInt-backed Longs need ES2020 to exist.
    target: 'es2020',
    assetsDir: '.',
    modulePreload: false,
  },
})
