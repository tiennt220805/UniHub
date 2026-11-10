// Kept as a belt: the files that build schemas import `z` from this module themselves (see it), which is what orders things.
import '@/shared/config/zod'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import { startThemeSync } from '@/shared/store/theme-store'
import '@/styles/index.css'
import { App } from './App'

startThemeSync()

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
