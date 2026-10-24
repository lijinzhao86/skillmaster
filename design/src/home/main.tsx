import { createRoot } from 'react-dom/client'
import './styles.css'
/* **No `auth.css` here.** v15 moved every sign-in flow to the console demo; this page mounts no
   overlay, so importing the sheet would only ship `.dialog` / `.overlay` rules nothing uses. */
import HomeApp from './HomeApp'
import './runtime.css'

const host = document.getElementById('app')
if (host === null) {
  throw new Error('the home demo has no #app element to mount into')
}

createRoot(host).render(<HomeApp />)
