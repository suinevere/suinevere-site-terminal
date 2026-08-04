/*----------------------
 | main.tsx
 | Description: Mounts the React application; the polyfill import must stay first so Buffer exists before RSocket loads.
 | Author: suinevere
 | Dependencies: polyfills, react-dom, App
 | Globals: N/A
 ----------------------*/
import './polyfills'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'
import './index.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
