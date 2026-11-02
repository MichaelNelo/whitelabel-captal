import { defineConfig } from 'vite'

// The portal again, in one file, for `preview.html` to carry inline. The backoffice's preview is an
// MCP App page, which a host reads as one document and nothing else, so the routes the portal's own
// build splits for a guest are put back together here (`inlineDynamicImports`, which takes a single
// entry). Minified as the portal is.
export default defineConfig({
  build: {
    outDir: 'dist-preview',
    emptyOutDir: true,
    minify: 'terser',
    terserOptions: {
      compress: { passes: 3, unsafe: true },
      mangle: { toplevel: true },
    },
    rollupOptions: {
      input: 'src/preview-portal.js',
      output: {
        entryFileNames: 'preview-portal.js',
        inlineDynamicImports: true,
      },
    },
    // Matches the linker's ESVersion: BigInt-backed Longs need ES2020 to exist.
    target: 'es2020',
    modulePreload: false,
  },
})
