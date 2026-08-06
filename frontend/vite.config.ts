/*----------------------
 | vite.config.ts
 | Description: Build, dev-server proxy, and vitest configuration for the terminal frontend.
 | Author: suinevere
 | Dependencies: vite, vitest, @vitejs/plugin-react
 | Globals: N/A
 ----------------------*/
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  base: '/zork/',
  define: {
    global: 'globalThis',
  },
  resolve: {
    alias: {
      buffer: 'buffer/',
    },
  },
  optimizeDeps: {
    include: ['buffer'],
  },
  server: {
    port: 5173,
    proxy: {
      '/zork/rsocket': {
        target: 'ws://localhost:8080',
        ws: true,
        rewrite: (path) => path.replace(/^\/zork/, ''),
      },
    },
  },
  test: {
    globals: true,
    environment: 'node',
  },
})
