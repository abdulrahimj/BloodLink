import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

import { problemWithHostedApiUrl } from './src/lib/apiBaseUrl.js';

export default defineConfig(({ mode }) => {
  // Cloudflare Pages sets CF_PAGES=1: there, fail the build rather than ship a site that calls localhost.
  if (process.env.CF_PAGES) {
    const problem = problemWithHostedApiUrl(loadEnv(mode, process.cwd(), 'VITE_').VITE_API_BASE_URL);
    if (problem) throw new Error(problem);
  }

  return {
    // sockjs-client references Node's global, which Vite doesn't define; without this the socket never opens.
    define: {
      global: 'globalThis',
    },
    plugins: [react()],
    server: {
      port: 5173,
    },
  };
});
