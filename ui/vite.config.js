import { defineConfig } from 'vite';
import { svelte } from '@sveltejs/vite-plugin-svelte';

// En dev (npm run dev), l'API est servie par le serveur Node sur le port 7070
export default defineConfig({
  plugins: [svelte()],
  server: {
    port: 5173,
    proxy: { '/api': 'http://localhost:7070' },
  },
});
