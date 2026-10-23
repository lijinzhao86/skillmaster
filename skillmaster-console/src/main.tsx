import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'

// `#app` is index.html's mount point. Failing loudly beats mounting into nothing: a typo here would
// otherwise show a blank page with no error and nothing to grep for.
const host = document.getElementById('app')
if (host === null) {
  throw new Error('index.html has no #app element to mount into')
}

createRoot(host).render(<App />)
