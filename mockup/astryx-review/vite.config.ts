import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'
import { fileURLToPath } from 'node:url'

// 저장소 루트(core/src/main/resources의 실제 프롬프트를 ?raw로 불러오기 위해 허용)
const repoRoot = fileURLToPath(new URL('../..', import.meta.url))

export default defineConfig({
  plugins: [react()],
  server: { fs: { allow: [repoRoot] } },
})
