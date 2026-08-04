/*----------------------
 | App.tsx
 | Description: Page shell hosting a single terminal.
 | Author: suinevere
 | Dependencies: Terminal
 | Globals: N/A
 ----------------------*/
import Terminal from './components/Terminal'

/*----------------------
 | App
 | Description: Renders the page around the terminal.
 | Author: suinevere
 | Dependencies: Terminal
 | Globals: N/A
 | Params: N/A
 | Returns: React element for the whole page
 ----------------------*/
export default function App() {
  return <Terminal />
}
