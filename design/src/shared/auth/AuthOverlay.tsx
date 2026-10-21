/**
 * The shared sign-in overlay: the same card both demos open, and the marketing page's login sheet.
 *
 * The host owns whether it is open and which top-level view it asks for. The overlay owns the steps
 * in between — register's code step, reset's code and password steps, consent's three states — and
 * reports each one through `onView` with the value a host with addresses can use. Register's and
 * reset's inner steps have no address of their own: a visitor arriving at them directly would have
 * no code and no number to explain it.
 *
 * Everything lives in the dialog at once and only one view is shown, exactly as the fragment had
 * it, so the values typed on one step are still there when a refused send puts somebody back on it.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import './auth.css'
import { VIEW_TITLE, addressViewOf, readConsent } from './authModel'
import type { AuthView, ConsentEntry } from './authModel'
import { BrandMark, CloseIcon } from './fields/icons'
import { useRegisterFlow } from './flows/useRegisterFlow'
import type { RegisterStep } from './flows/useRegisterFlow'
import { useResetFlow } from './flows/useResetFlow'
import type { ResetStep } from './flows/useResetFlow'
import ConsentDoneView from './views/ConsentDoneView'
import ConsentIncompleteView from './views/ConsentIncompleteView'
import ConsentView from './views/ConsentView'
import LoginView from './views/LoginView'
import RegisterCodeView from './views/RegisterCodeView'
import RegisterDoneView from './views/RegisterDoneView'
import RegisterView from './views/RegisterView'
import ResetCodeView from './views/ResetCodeView'
import ResetDoneView from './views/ResetDoneView'
import ResetPasswordView from './views/ResetPasswordView'
import ResetView from './views/ResetView'
import { useLoginForm } from './views/useLoginForm'

export type { AuthView } from './authModel'

export interface AuthOverlayProps {
  open: boolean
  view: AuthView
  standalone?: boolean
  dismissible?: boolean
  /** client_id / state / scope / name, for the consent screen. */
  consent?: URLSearchParams
  returnFocus?: HTMLElement | null
  onView: (view: AuthView) => void
  onClose: () => void
  onSignedIn: () => void
}

const FOCUS_AFTER_MS = 70
const CLOSE_AFTER_MS = 3000

/* The control each screen begins on. Consent's three states begin on the card itself: landing on
   「同意并继续」 would make the next Return press consent to something nobody has read yet — the one
   place in this file where the default of focusing the primary action is actively wrong. */
const FOCUS_ID: Partial<Record<AuthView, string>> = {
  login: 'phone',
  register: 'regUsername',
  registerCode: 'regCode',
  registerDone: 'doneStartBtn',
  reset: 'resetPhone',
  resetCode: 'resetCode',
  resetPassword: 'resetPassword',
  resetDone: 'resetDoneToLogin',
}

const EMPTY_CONSENT: ConsentEntry = {
  complete: true,
  knownClient: false,
  who: '',
  name: '',
  scopes: [],
}

export default function AuthOverlay({
  open,
  view,
  standalone,
  dismissible,
  consent,
  returnFocus,
  onView,
  onClose,
  onSignedIn,
}: AuthOverlayProps) {
  /* A page with something under the dialog can always be left; one where the dialog is the only
     thing on it cannot, because closing it would leave nowhere to be. */
  const dismissable = dismissible !== false
  const consentKey = consent?.toString() ?? ''

  const [current, setCurrent] = useState<AuthView>('login')
  const [consentEntry, setConsentEntry] = useState<ConsentEntry>(EMPTY_CONSENT)
  const [consentName, setConsentName] = useState('')
  const [consentAgreed, setConsentAgreed] = useState(true)

  const dialogRef = useRef<HTMLDivElement>(null)
  const currentRef = useRef<AuthView>(current)
  const openRef = useRef(open)
  const wasOpen = useRef(false)
  const appliedView = useRef<AuthView | null>(null)
  const appliedConsent = useRef('')
  const doneOnClose = useRef(false)
  const focusTimer = useRef<number | null>(null)
  const closeTimer = useRef<number | null>(null)
  const locked = useRef(false)

  const onViewRef = useRef(onView)
  const onCloseRef = useRef(onClose)
  onViewRef.current = onView
  onCloseRef.current = onClose
  currentRef.current = current
  openRef.current = open

  const login = useLoginForm({
    onSignedIn,
    onCloseDone: () => requestClose(true),
  })
  const register = useRegisterFlow(advanceRegister)
  const resetFlow = useResetFlow(advanceReset)

  /* ------------------------------------------------------------------ scroll
     Locking the page must not move it. Taking the scrollbar away widens the viewport by its width,
     and everything centred then shifts right by half of that — 7px on a 15px scrollbar. Reserving
     the same width on the body keeps every measurement identical, sticky nav included. */
  function lockScroll(): void {
    const gap = window.innerWidth - document.documentElement.clientWidth
    document.body.style.overflow = 'hidden'
    if (gap > 0) document.body.style.paddingRight = gap + 'px'
    locked.current = true
  }

  function unlockScroll(): void {
    document.body.style.overflow = ''
    document.body.style.paddingRight = ''
    locked.current = false
  }

  /* ------------------------------------------------------------------- views
     The dialog is the container; only what stands in it changes. The title is the view's own, and
     focus follows it — so somebody on a keyboard is put where the screen begins rather than left on
     the control they pressed, which is by then gone. */
  function scheduleFocus(next: AuthView): void {
    if (focusTimer.current !== null) {
      window.clearTimeout(focusTimer.current)
      focusTimer.current = null
    }
    focusTimer.current = window.setTimeout(() => {
      focusTimer.current = null
      if (!openRef.current || currentRef.current !== next) return
      const dialog = dialogRef.current
      if (dialog === null) return
      const id = FOCUS_ID[next]
      if (id === undefined) {
        dialog.focus()
        return
      }
      dialog.querySelector<HTMLElement>('#' + id)?.focus()
    }, FOCUS_AFTER_MS)
  }

  function show(next: AuthView): void {
    /* Cancelled if the view has moved on. A close arriving after that would take the tab somewhere
       the person is no longer looking. */
    if (next !== 'consentDone' && closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current)
      closeTimer.current = null
    }
    setCurrent(next)
    currentRef.current = next
    appliedView.current = addressViewOf(next)
    scheduleFocus(next)
    onViewRef.current(addressViewOf(next))
  }

  /* Arriving at one of the three flows, with whatever that flow needs to be ready to be answered —
     whether it was reached by a link in the dialog or asked for by the host up front. The code steps
     are not entry points: a person reaches them by answering the screen before them. */
  function enter(next: AuthView): void {
    if (next === 'register') {
      register.reset()
    } else if (next === 'reset') {
      resetFlow.reset()
      /* Recovery's sends always need a captcha — it is the flow that can take an account away from
         whoever holds it — so one is on screen from the start rather than waited to be told about. */
      resetFlow.spendCaptcha()
    } else {
      register.reset()
      resetFlow.reset()
    }
    show(next)
  }

  function enterConsent(): void {
    const entry = readConsent(consent)
    setConsentEntry(entry)
    setConsentName(entry.name)
    setConsentAgreed(true)
    appliedConsent.current = consentKey
    /* Either half missing is a link that cannot work. The source's `openConsent` answered "not a
       consent entry" when the address carried neither parameter; a host that asks for this view
       anyway gets the card that says so rather than an empty request. */
    show(entry.complete ? 'consent' : 'consentIncomplete')
  }

  function applyEntry(next: AuthView): void {
    if (addressViewOf(next) === 'consent') {
      enterConsent()
      return
    }
    enter(addressViewOf(next))
  }

  function advanceRegister(step: RegisterStep): void {
    show(step)
  }

  function advanceReset(step: ResetStep): void {
    show(step)
  }

  function requestClose(done: boolean): void {
    if (!openRef.current) return
    doneOnClose.current = done
    onCloseRef.current()
  }

  /* ---------------------------------------------------------------- consent */
  function consentAnswer(agreed: boolean): void {
    setConsentAgreed(agreed)
    show('consentDone')
    /* One attempt, silent, and nothing on the card mentions it. `window.close()` can never be
       granted here — the tab was opened by the operating system and has a history of two — so the
       attempt costs nothing and the card claims nothing it cannot back up. */
    closeTimer.current = window.setTimeout(() => {
      closeTimer.current = null
      if (currentRef.current !== 'consentDone') return
      window.close()
    }, CLOSE_AFTER_MS)
  }

  /* ------------------------------------------------------------ open / close
     A layout effect, because the fragment opened synchronously: a host that lands on
     `?page=register` must never paint the login card for a frame first. */
  useLayoutEffect(() => {
    if (open) {
      if (!wasOpen.current) {
        wasOpen.current = true
        doneOnClose.current = false
        login.resetErrors()
        login.resetReveal()
        lockScroll()
        applyEntry(view)
        return
      }
      /* Consent's parameters only matter while its own view is up; a host that passes the whole
         address must not have a change to an unrelated parameter re-enter (and reset) a flow. */
      const consentRelevant = addressViewOf(view) === 'consent'
      if (
        addressViewOf(view) !== appliedView.current ||
        (consentRelevant && consentKey !== appliedConsent.current)
      ) {
        applyEntry(view)
      }
      return
    }

    if (!wasOpen.current) return
    wasOpen.current = false
    unlockScroll()

    const done = doneOnClose.current
    doneOnClose.current = false
    /* Every way out leaves the dialog as it was found. A countdown still running, or a walk back
       into the first step of a register nobody finished, would be a screen remembering a question
       the person has already left. */
    login.cancelPending()
    login.resetErrors()
    login.resetReveal()
    if (done) login.resetAll()
    register.reset()
    resetFlow.reset()
    setCurrent('login')
    currentRef.current = 'login'
    appliedView.current = 'login'
    if (focusTimer.current !== null) {
      window.clearTimeout(focusTimer.current)
      focusTimer.current = null
    }
    if (closeTimer.current !== null) {
      window.clearTimeout(closeTimer.current)
      closeTimer.current = null
    }

    if (returnFocus !== null && returnFocus !== undefined && document.contains(returnFocus) && returnFocus.offsetParent !== null) {
      returnFocus.focus()
    }
  }, [open, view, consentKey])

  /* Esc and the focus trap. The overlay is the scrim: it fills the viewport, so a click that
     reaches it (rather than the dialog inside it) is a click on the mask. */
  useEffect(() => {
    if (!open) return

    function onKeyDown(event: KeyboardEvent): void {
      if (event.key === 'Escape') {
        if (!dismissable) return
        event.preventDefault()
        requestClose(false)
        return
      }
      if (event.key !== 'Tab') return
      const dialog = dialogRef.current
      if (dialog === null) return
      const nodes = dialog.querySelectorAll<HTMLElement>('button, input, [href]')
      const list = Array.from(nodes).filter((node) => {
        const disabled =
          node instanceof HTMLButtonElement || node instanceof HTMLInputElement ? node.disabled : false
        return !disabled && node.offsetParent !== null
      })
      if (list.length === 0) return
      const first = list[0]
      const last = list[list.length - 1]
      const active = document.activeElement
      if (event.shiftKey && (active === first || active === dialog)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && active === last) {
        event.preventDefault()
        first.focus()
      }
    }

    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open, dismissable])

  /* An unmount while open must not leave the page locked. */
  useEffect(() => () => {
    if (locked.current) unlockScroll()
    if (focusTimer.current !== null) window.clearTimeout(focusTimer.current)
    if (closeTimer.current !== null) window.clearTimeout(closeTimer.current)
  }, [])

  /* ------------------------------------------------------------------- view */
  const isStandalone = standalone === true || current.startsWith('consent')
  const overlayClass = 'overlay' + (open ? ' is-open' : '') + (isStandalone ? ' is-standalone' : '')
  const title = current === 'consentDone'
    ? (consentAgreed ? '授权成功' : '授权未完成')
    : VIEW_TITLE[current]
  const consentDoneNote = consentAgreed ? '请关闭此页面回到终端继续。' : '你拒绝了这次授权请求。'

  return (
    <div
      className={overlayClass}
      id="overlay"
      onClick={(event) => {
        if (!dismissable) return
        if (event.target === event.currentTarget) requestClose(false)
      }}
    >
      <div
        className="dialog"
        id="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby="dialogTitle"
        tabIndex={-1}
        ref={dialogRef}
      >
        <p className="dialog-brand">
          <BrandMark />SkillMaster
        </p>
        <h2 className="dialog-title" id="dialogTitle">{title}</h2>
        <button
          type="button"
          className="dialog-x"
          id="closeBtn"
          aria-label="关闭"
          hidden={!dismissable}
          onClick={() => {
            if (dismissable) requestClose(false)
          }}
        >
          <CloseIcon />
        </button>

        <LoginView
          active={current === 'login'}
          form={login}
          onRegister={() => enter('register')}
          onReset={() => enter('reset')}
        />

        <RegisterView
          active={current === 'register'}
          flow={register}
          onLogin={() => enter('login')}
        />

        <RegisterCodeView
          active={current === 'registerCode'}
          flow={register}
          onResend={() => show('register')}
          onLogin={() => enter('login')}
        />

        <RegisterDoneView
          active={current === 'registerDone'}
          username={register.username.state.value}
          onStart={() => {
            onSignedIn()
            requestClose(true)
          }}
        />

        <ResetView
          active={current === 'reset'}
          flow={resetFlow}
          onLogin={() => enter('login')}
        />

        <ResetCodeView
          active={current === 'resetCode'}
          flow={resetFlow}
          onResend={() => {
            resetFlow.abandonCode()
            show('reset')
          }}
          onLogin={() => enter('login')}
        />

        <ResetPasswordView
          active={current === 'resetPassword'}
          flow={resetFlow}
          onLogin={() => enter('login')}
        />

        <ResetDoneView active={current === 'resetDone'} onLogin={() => enter('login')} />

        <ConsentView
          active={current === 'consent'}
          entry={consentEntry}
          name={consentName}
          onName={setConsentName}
          onAgree={() => consentAnswer(true)}
          onRefuse={() => consentAnswer(false)}
        />

        <ConsentIncompleteView active={current === 'consentIncomplete'} />

        <ConsentDoneView active={current === 'consentDone'} note={consentDoneNote} />
      </div>
    </div>
  )
}
