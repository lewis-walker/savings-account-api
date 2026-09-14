/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    // See API_BASE_URL in src/api/api.ts: there is no origin outside a browser, so a
    // relative base cannot be resolved into a Request.
    env: { VITE_API_BASE_URL: 'http://localhost/api' },
  },
  server: {
    port: 3000,
    // For running the frontend outside Docker against a local backend. In the
    // container, nginx does this instead - same path, same one-origin arrangement, so
    // there is no CORS configuration anywhere in this project and nothing that only
    // works in one of the two setups.
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api/, ''),
      },
    },
  },
});
