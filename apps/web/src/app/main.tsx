// First, on purpose: see the module (Zod must not probe for eval under our CSP).
import '@/shared/config/zod'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'

import '@/styles/index.css'
import { App } from './App'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
