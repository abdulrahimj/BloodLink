import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  // sockjs-client references Node's global, which Vite doesn't define; without this the socket never opens.
  define: {
    global: 'globalThis',
  },
  plugins: [react()],
  server: {
    port: 5173,
  },
});
