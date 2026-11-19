import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// Builds popup, offscreen document and MV3 service worker into ./dist (Load unpacked).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  build: {
    outDir: 'dist',
    emptyOutDir: true,
    target: 'chrome110',
    modulePreload: false,
    rollupOptions: {
      input: {
        popup: 'popup.html',
        offscreen: 'offscreen.html',
        background: 'src/background.ts',
      },
      output: {
        entryFileNames: '[name].js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: 'assets/[name]-[hash][extname]',
      },
    },
  },
});
