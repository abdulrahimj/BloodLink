import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  // sockjs-client is a browser build that still references Node's `global`.
  // Vite does not define it, so without this the SockJS import throws
  // "global is not defined" the moment a portal mounts and the socket never opens.
  define: {
    global: 'globalThis',
  },
  plugins: [react()],
  server: {
    port: 5173,
  },
});
