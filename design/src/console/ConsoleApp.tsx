/**
 * The console demo, as a page: which screen the address names, and what a session means to it.
 *
 * Two kinds of page here, and what separates them is a session. The six that are signed in are the
 * console; the three that are not are the ways to get one. They share an address space because that
 * is what the real app does — `/login`, `/register` and `/reset` are pages there, not a dialog over
 * one — and because the address is the only state this demo has.
 *
 * **The sign-in flows themselves are not here.** They live once, in `src/shared/auth/`, shared with
 * the marketing demo, and this file mounts `<AuthOverlay>` and tells it which view to open. The
 * console owns its pages and the strip; the overlay owns its own dialog.
 */

import { useEffect, useState } from 'react'
import { go, pageOf, replace, useAddress } from './address'
import { isAuthPage, isConsolePage, stateOf } from './demo'
import type { AuthPage, ConsolePage } from './demo'
import { keysState } from './pages/keysModel'
import Topbar from './shell/Topbar'
import Sidenav from './shell/Sidenav'
import DemoBar from './shell/DemoBar'
import AppDialogs from './components/AppDialogs'
import { dialogs } from './components/dialogs/store'
import type { ImportAsked, NewSkillMode } from './components/dialogs/store'
import AuthOverlay from '../shared/auth/AuthOverlay'
import type { AuthView } from '../shared/auth/AuthOverlay'
import SkillsPage from './pages/SkillsPage'
import SkillPage from './pages/SkillPage'
import EditorPage from './pages/EditorPage'
import AccountPage from './pages/AccountPage'
import KeysPage from './pages/KeysPage'
import SessionsPage from './pages/SessionsPage'

/** The dialog's screens are its own business, but three of them are addresses here — the real app
 *  has `/login`, `/register` and `/reset` as pages — so the address follows the dialog, and a reload
 *  lands on the screen that was actually up. */
const ADDRESS_OF: Record<string, AuthPage> = {
  login: 'login',
  register: 'register',
  registerCode: 'register',
  registerDone: 'register',
  reset: 'reset',
  resetCode: 'reset',
  resetPassword: 'reset',
  resetDone: 'reset',
}

const PAGES = [
  { name: 'skills', Component: SkillsPage },
  { name: 'skill', Component: SkillPage },
  { name: 'editor', Component: EditorPage },
  { name: 'account', Component: AccountPage },
  { name: 'keys', Component: KeysPage },
  { name: 'sessions', Component: SessionsPage },
] as const

export default function ConsoleApp() {
  const params = useAddress()
  const page = pageOf(params)
  const signedOut = isAuthPage(page)
  const [authView, setAuthView] = useState<AuthPage>(signedOut ? page : 'login')

  /* **Signed out, the dialog is the page.** The address follows the dialog (a reload lands on the
     screen that was up), and nothing stands behind it, so the frame comes out and the card stands on
     the ground by itself. */
  const onAuthView = (view: AuthView) => {
    const address = ADDRESS_OF[view]
    if (address === undefined) return
    /* Announced, so the snapshot every page builds addresses from follows the dialog. */
    replace('?page=' + encodeURIComponent(address))
    setAuthView(address)
  }

  /* **The sheets a state can be read into.** With no server behind the demo, the refused and
     idempotent states of the two forms cannot be clicked into — so they are addresses, and this is
     where the address is turned back into an open sheet. The order is the old demo's: import, then
     the new-skill refusal, so the later one is on top. */
  useEffect(() => {
    const search = new URLSearchParams(window.location.search)
    const at = pageOf(search)
    /* Signed out, the dialog is the whole page and nothing opens on top of it — the old console
       returned before its forms were read from the address, and this is the same rule. */
    if (isAuthPage(at)) return
    const listMode = at === 'skills'

    const askedImport = search.get('import')
    const imports = ['open', 'error', 'exists', 'exists-draft', 'exists-hidden', 'deleted', 'mismatch']
    if (askedImport && imports.includes(askedImport)) {
      /* **两个入口各自只出现它真会遇到的拒绝**（v9 PRD 做 #6）：地址名不一致只可能在版本列表这一路
         发生；名字属于已删除的 skill 只可能在「我的 skill」这一路发生。 */
      if (!(askedImport === 'deleted' && !listMode) && !(askedImport === 'mismatch' && listMode)) {
        dialogs.openImport(listMode ? 'list' : 'skill', askedImport as ImportAsked)
      }
    }

    const askedNew = search.get('newskill')
    if (askedNew === 'open' || askedNew === 'error' || askedNew === 'deleted') {
      dialogs.openNewSkill(askedNew as NewSkillMode)
    }
  }, [])

  /** The state a page is in, including the fallbacks a page owns (a moment with no subject is not
   *  that moment). */
  const pageState = (name: ConsolePage) => name === 'keys' ? keysState(params) : stateOf(name, params)

  /* The state the strip should mark. Only a console page has states; the strip's own auth view is
     the dialog's, and it says so. */
  const state = isConsolePage(page) ? pageState(page) : ''

  return (
    <>
      {/* **Nothing stands behind it.** A console with no session has no content, so the frame comes
          out and the dialog stands on the ground by itself. It is still *rendered* — and hidden —
          because the frame is a fact about the page, and a `.app` that is absent is a different
          one. */}
      <div className="app" hidden={signedOut}>
        {!signedOut && (
          <>
            <Topbar />
            <Sidenav page={page} />
            <main className="content">
              {PAGES.map(({ name, Component }) => (
                <Component
                  key={name}
                  active={name === page}
                  state={pageState(name)}
                  params={params}
                />
              ))}
            </main>
          </>
        )}
      </div>

      <AppDialogs />

      <AuthOverlay
        open={signedOut}
        view={authView}
        standalone
        dismissible={false}
        returnFocus={null}
        onView={onAuthView}
        onClose={() => { /* a console with no session has nowhere to go back to */ }}
        onSignedIn={() => go('?page=skills')}
      />

      <DemoBar page={page} state={state} params={params} authView={authView} />
    </>
  )
}
