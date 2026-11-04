import { createRoot } from 'react-dom/client'
import App from './App'
import './styles.css'
/* The design's own two sheets, in its own order (`design/src/home/main.tsx`): the page, then the
   mount-point rule. `styles.css` is the design's sheet minus the demo strip and its `--demo-bar`;
   in the shared rules the one deliberate difference is that the footer link's hover/focus
   selectors are merged with `.foot-col`'s to keep the file at its 649-line baseline — the
   declarations and computed styles are identical. Anything the demo keeps outside `styles.css`
   has to come along too — `runtime.css` is one rule, and without it the mount div sits in the
   body's flow. */
import './runtime.css'

// `#app` is index.html's mount point. Failing loudly beats mounting into nothing: a typo here would
// otherwise show a blank page with no error and nothing to grep for.
const host = document.getElementById('app')
if (host === null) {
  throw new Error('index.html has no #app element to mount into')
}

createRoot(host).render(<App />)
