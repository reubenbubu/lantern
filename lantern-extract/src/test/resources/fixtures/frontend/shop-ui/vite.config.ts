import { defineConfig } from 'vite'

export default defineConfig({
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/auth': { target: 'http://localhost:9090', changeOrigin: true },
    },
  },
})
