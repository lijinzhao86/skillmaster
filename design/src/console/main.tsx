import { createRoot } from 'react-dom/client'
import './styles.css'
/* The sign-in sheet's styles come after the console's own, as the spliced fragment's did. */
import '../shared/auth/auth.css'
import ConsoleApp from './ConsoleApp'
import './runtime.css'

// `#app` is the source template's mount point. Failing loudly beats mounting into nothing: a typo
// here would otherwise show a blank page with no error and nothing to grep for.
const host = document.getElementById('app')
if (host === null) {
  throw new Error('the console demo has no #app element to mount into')
}

createRoot(host).render(<ConsoleApp />)
