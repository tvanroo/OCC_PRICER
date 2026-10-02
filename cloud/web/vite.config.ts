import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the API runs on :8080; in production Spring Boot serves this build from the same origin.
// Every build gets its own id. Cached API responses are keyed on it, so a new release never shows old results.
export default defineConfig({
  plugins: [react()],
  define: { __BUILD_ID__: JSON.stringify(Date.now().toString(36)) },
  server: { proxy: { '/api': 'http://localhost:8080' } },
})
