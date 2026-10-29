import { useEffect, useRef, useState } from 'react'
import { logout } from '../api/account'
import { SITE_URL } from '../siteUrl'

/**
 * `/logout` — the page the marketing site's 「退出登录」 points at (ADR 0086 decision 2).
 *
 * **It exists so the site never has to write.** The site cannot POST (it has no CSRF token, by
 * design), so ending a session has to happen on this origin; the site's menu is an ordinary link
 * here, and this page does the one write and sends the person back.
 *
 * **There is no screen while it works.** The visit lasts as long as one request, and anything drawn
 * would be a flash of console chrome for a person who is leaving. It renders nothing until the
 * answer is a failure — the one state a person can be stranded in.
 *
 * The server answers 204 even when there was no session, so "already signed out" lands here as a
 * success and still returns to the site.
 */

/**
 * Where to send the person back to — the same value the top bar's brand links to.
 * See `../siteUrl.ts` for why the trailing slash is added rather than assumed.
 */

export default function LogoutPage() {
  const [failed, setFailed] = useState(false)

  /**
   * The write, started at most once per visit.
   *
   * **Under `StrictMode` React runs an effect twice** (mount, unmount, mount), so an effect that
   * posts directly would send two logouts; nothing in this app mounts that way today, and this page
   * does not want to be the one that breaks the day something does. A ref outlives that remount
   * while the page does not, and holding the *promise* rather than a flag lets the second run still
   * receive the answer — which a plain "already started, do nothing" guard would swallow, leaving
   * the page blank forever.
   */
  const pending = useRef<ReturnType<typeof logout> | null>(null)

  useEffect(() => {
    let cancelled = false
    pending.current ??= logout()
    void pending.current.then((result) => {
      if (cancelled) return
      if (result.ok) {
        // `replace`, not `assign`: Back should not return to a page that only redirects.
        window.location.replace(SITE_URL)
      } else {
        setFailed(true)
      }
    })
    return () => {
      cancelled = true
    }
  }, [])

  if (!failed) return null

  return (
    <main className="logout-fallback">
      <p className="hint">没能退出登录。可以回去重试，或直接关闭这个页面。</p>
      <a className="link-button" href={SITE_URL}>回到首页</a>
    </main>
  )
}
