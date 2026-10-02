import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

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
})
