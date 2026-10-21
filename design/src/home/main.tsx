import { createRoot } from 'react-dom/client'
import './styles.css'
/* The overlay's styles come after the page's own, as the spliced fragment's did: it is scoped under
   `.dialog`, and where a host and the sheet both name something, the sheet is the later word. */
import '../shared/auth/auth.css'
import HomeApp from './HomeApp'
import './runtime.css'

const host = document.getElementById('app')
if (host === null) {
  throw new Error('the home demo has no #app element to mount into')
}

createRoot(host).render(<HomeApp />)
