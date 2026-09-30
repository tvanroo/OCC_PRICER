import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// In development the API runs on :8080; in production Spring Boot serves this build from the same origin.
export default defineConfig({
  plugins: [react()],
  server: { proxy: { '/api': 'http://localhost:8080' } },
})
