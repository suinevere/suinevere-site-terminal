/*----------------------
 | main.tsx
 | Description: Mounts the React application.
 | Author: suinevere
 | Dependencies: react-dom, App
 | Globals: N/A
 ----------------------*/
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
