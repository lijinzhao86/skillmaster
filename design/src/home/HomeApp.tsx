/**
 * The marketing page: what the product is, and the three ways into the sign-in overlay.
 *
 * It is one page with one piece of state — whether a session exists — plus the overlay's own. The
 * overlay is the same React component the console uses; this page only decides when it opens and
 * what a session means here (the navigation swaps over).
 */

import { useEffect, useRef, useState } from 'react'
import AuthOverlay from '../shared/auth/AuthOverlay'
import type { AuthView } from '../shared/auth/AuthOverlay'
import consoleLight from '../../assets/console-light.png'

interface OverlayState {
  open: boolean
  view: AuthView
  dismissible: boolean
  standalone: boolean
  returnFocus: HTMLElement | null
  consent?: URLSearchParams
}

const CLOSED: OverlayState = {
  open: false,
  view: 'login',
  dismissible: true,
  standalone: false,
  returnFocus: null,
}

const noop = (event: { preventDefault: () => void }) => event.preventDefault()

export default function HomeApp() {
  const [authed, setAuthed] = useState(false)
  const [scrolled, setScrolled] = useState(false)
  const [overlay, setOverlay] = useState<OverlayState>(CLOSED)
  const guest = useRef<HTMLDivElement>(null)
  const user = useRef<HTMLDivElement>(null)
  const first = useRef(true)

  const open = (view: AuthView, trigger: HTMLElement | null) => {
    setOverlay({ open: true, view, dismissible: true, standalone: false, returnFocus: trigger })
  }

  /* **What a session means to this page.** The overlay reports that one started; the marketing
     page's answer is that the navigation swaps over. The class is removed and re-added around a
     forced reflow so the entrance plays again, exactly as the old script did. */
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    const shown = authed ? user.current : guest.current
    if (!shown) return
    shown.classList.remove('nav-in')
    void shown.offsetWidth
    shown.classList.add('nav-in')
  }, [authed])

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 6)
    window.addEventListener('scroll', onScroll, { passive: true })
    onScroll()
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  /* The homepage, unless the address says the CLI is asking. The overlay decides whether the link
     is complete; this only passes it on. */
  useEffect(() => {
    const params = new URLSearchParams(window.location.search)
    if (params.get('client_id') === null && params.get('state') === null) return
    setOverlay({
      open: true,
      view: 'consent',
      dismissible: false,
      standalone: false,
      returnFocus: null,
      consent: params,
    })
  }, [])

  return (
    <>
      <header className={'nav' + (scrolled ? ' is-scrolled' : '')} id="nav">
        <div className="wrap nav-inner">
          <a className="brand" href="#" data-noop onClick={noop}>
            <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
              <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
              <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
              <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
            </svg>
            <span className="brand-name">SkillMaster</span>
          </a>

          <nav className="nav-links" aria-label="站点">
            <a href="#" data-noop onClick={noop}>文档</a>
            <a href="#" data-noop onClick={noop}>CLI</a>
            <a href="#" data-noop onClick={noop}>协议</a>
            <a href="#" data-noop onClick={noop}>更新日志</a>
          </nav>

          <div className="nav-right">
            <div className="nav-guest" id="navGuest" ref={guest} hidden={authed}>
              <button type="button" className="link-btn" id="loginBtn"
                onClick={(event) => open('login', event.currentTarget)}>登录</button>
              <button type="button" className="btn btn-sm" id="startBtnNav"
                onClick={(event) => open('login', event.currentTarget)}>开始使用</button>
            </div>
            <div className="nav-user" id="navUser" ref={user} hidden={!authed}>
              <span className="avatar" aria-hidden="true">d</span>
              <span className="who">dev@skillmaster.io</span>
              <a href="#" data-noop onClick={noop}>控制台</a>
              <button type="button" className="link-btn" id="logoutBtn"
                onClick={() => setAuthed(false)}>退出</button>
            </div>
          </div>
        </div>
      </header>

      <main>
        <section className="hero">
          <div className="wrap">
            <p className="eyebrow">面向 AI 技能的托管服务</p>
            <h1>把你的 skill 托管起来，<br />任何 agent 一行加载。</h1>
            <p className="lede">带版本、带权限。发布一次，你团队里每个人的 agent 拿到的是同一个。</p>
            <div className="hero-cta">
              <button type="button" className="btn" id="startBtnHero"
                onClick={(event) => open('login', event.currentTarget)}>开始使用</button>
              <button type="button" className="btn-quiet" data-noop onClick={noop}>读文档 →</button>
            </div>
          </div>
        </section>

        <section className="horizon">
          <div className="wrap horizon-line">
            <span className="rule" aria-hidden="true"></span>
            <p className="address">demo/pdf-tools<span className="ver">@1.2.3</span></p>
            <span className="rule" aria-hidden="true"></span>
          </div>
        </section>

        <section className="demo">
          <div className="wrap">
            <figure className="term">
              <figcaption className="term-bar">zsh — skillmaster</figcaption>
              <pre className="term-body"><span className="ln"><span className="sig">$</span> skillmaster skill invoke demo/pdf-tools</span><span className="ln dim">（按 demo/pdf-tools@1.2.3 取。）</span><span className="ln out"><span className="sig">#</span> pdf-tools</span><span className="ln dim">把 PDF 拆页、合并与压缩。加载后可用 split 与 merge 两个动作。</span></pre>
            </figure>
          </div>
        </section>

        <section className="wrap split">
          <div>
            <h2>现在共享 skill，靠的是拷文件夹</h2>
            <p>拷过去的那份，改了、旧了、谁改的，没人知道。</p>
          </div>
          <figure className="shot">
            <img src={consoleLight} alt="SkillMaster 控制台：侧栏外壳与「我的 skill」列表。" />
          </figure>
        </section>

        <section className="wrap points">
          <article className="point">
            <p className="num">1</p>
            <h3>版本不会漂</h3>
            <p className="body">地址里钉版本（<code>@1.2.3</code>）或取当前。今天加载的，明天还是同一个——不会在你不知道的时候被换掉。</p>
          </article>
          <article className="point">
            <p className="num">2</p>
            <h3>谁能读，你说了算</h3>
            <p className="body">把 skill 共享给谁、给 <code>viewer</code> 还是 <code>editor</code>，一个面板管完。不给的人，连它存在都看不到。</p>
          </article>
          <article className="point">
            <p className="num">3</p>
            <h3>一行加载</h3>
            <p className="body">不用拷文件夹、不用配路径。<code>invoke</code> 之后，agent 手里就有它了。</p>
          </article>
        </section>

        <section className="wrap steps">
          <h2>三步，把你的 skill 交出去</h2>

          <div className="step-list">
            <article className="step">
              <p className="step-label">STEP 01</p>
              <div>
                <h3>写一个 <code>SKILL.md</code></h3>
                <p className="body">正文加需要的文件，放在一个目录里。它是什么、怎么用，写在正文里。</p>
              </div>
            </article>

            <article className="step">
              <p className="step-label">STEP 02</p>
              <div>
                <h3>提交</h3>
                <p className="body">一条命令推上去，它会开网页让你确认这次改动，再决定要不要上线。</p>
                <p className="cmd">skillmaster skill submit</p>
              </div>
            </article>

            <article className="step">
              <p className="step-label">STEP 03</p>
              <div>
                <h3>在任何 agent 里加载</h3>
                <p className="body">把地址给 agent，一行就加载进来。团队里谁都能用同一个。</p>
                <p className="cmd">skillmaster skill invoke &lt;命名空间&gt;/&lt;名字&gt;</p>
              </div>
            </article>
          </div>
        </section>
      </main>

      <footer className="foot">
        <div className="wrap foot-inner">
          <div>
            <div className="foot-name">
              <svg className="mark" viewBox="0 0 24 24" aria-hidden="true">
                <rect x="3" y="4" width="18" height="4.5" rx="2.25" fill="currentColor" />
                <rect x="3" y="10.5" width="13" height="4.5" rx="2.25" fill="currentColor" opacity="0.6" />
                <rect x="3" y="17" width="8" height="4.5" rx="2.25" fill="currentColor" opacity="0.32" />
              </svg>
              <span>SkillMaster</span>
            </div>
            <p className="foot-tag">把你的 skill 托管起来，任何 agent 一行加载。</p>
          </div>

          <nav className="foot-cols" aria-label="页脚">
            <ul>
              <li><a href="#" data-noop onClick={noop}>产品</a></li>
              <li><a href="#" data-noop onClick={noop}>文档</a></li>
              <li><a href="#" data-noop onClick={noop}>CLI</a></li>
              <li><a href="#" data-noop onClick={noop}>协议</a></li>
            </ul>
            <ul>
              <li><a href="#" data-noop onClick={noop}>关于</a></li>
              <li><a href="#" data-noop onClick={noop}>更新日志</a></li>
              <li><a href="#" data-noop onClick={noop}>隐私</a></li>
              <li><a href="#" data-noop onClick={noop}>条款</a></li>
            </ul>
          </nav>
        </div>
      </footer>

      <AuthOverlay
        open={overlay.open}
        view={overlay.view}
        standalone={overlay.standalone}
        dismissible={overlay.dismissible}
        consent={overlay.consent}
        returnFocus={overlay.returnFocus}
        onView={(view) => setOverlay((state) => ({ ...state, view }))}
        onClose={() => setOverlay((state) => ({ ...state, open: false }))}
        onSignedIn={() => {
          setAuthed(true)
          setOverlay((state) => ({ ...state, open: false }))
        }}
      />
    </>
  )
}
