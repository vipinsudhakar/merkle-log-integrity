import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The Spring API runs on :8080. In development, /api is proxied there, so the browser sees one
// origin and no CORS setup is needed. In production the API URL comes from VITE_API_BASE.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
