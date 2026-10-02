import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // The gateway's CORS policy allows exactly http://localhost:5173. Without strictPort,
    // Vite moves to 5174 when 5173 is busy and says so in one easily missed line - and
    // then every API call fails CORS for a reason that has nothing to do with the code.
    // Failing to start is the better outcome.
    strictPort: true,
    //
    // NO PROXY, DELIBERATELY. The browser calls the gateway on 8080 directly, as a
    // cross-origin request. A proxy here would make every call same-origin, and CORS would
    // go untested until the first deployment - where there is no Vite to proxy anything.
  },
  preview: {
    port: 5173,
    strictPort: true,
  },
  test: {
    // Plain Node, deliberately. The tests cover the modules under src/lib and
    // src/api/errors.ts, which are pure functions: no DOM, no React, no browser API. A
    // jsdom environment would be a dependency added to test nothing, and would let a test
    // quietly start depending on a fake browser.
    environment: 'node',
    include: ['src/**/*.test.ts'],
  },
})
