/**
 * The footer, shared by the home deck and the legal reading pages.
 *
 * **两条链接从占位变成了真的**（v56）：隐私 → `?legal=privacy`，条款 → `?legal=terms`。演示件
 * 用查询参数，产品用 `/privacy` 与 `/terms`；原来「文档」列里的「协议」占位随之删掉，其余四条
 * 仍是占位链接，点了不跳走。页脚底栏署运营主体，不再只写品牌名。
 *
 * On the home page the footer is the deck's second screen and keeps its reveal; on a legal page
 * it is an ordinary footer at the end of the document. `staticFooter` is that difference, and it
 * is the only one.
 */

const CONSOLE_DEMO = 'skillmaster-控制台演示.html'

const noop = (event: { preventDefault: () => void }) => event.preventDefault()

export interface SiteFooterProps {
  /** The deck's second screen is visible; the legal pages' footer always is. */
  visible?: boolean
  /** A legal page's footer sits at the end of the document instead of filling a screen. */
  staticFooter?: boolean
  onFocus?: () => void
}

export default function SiteFooter({ visible = true, staticFooter = false, onFocus }: SiteFooterProps) {
  return (
    <footer
      className={'foot' + (visible ? ' is-visible' : '') + (staticFooter ? ' foot-static' : '')}
      id="foot"
      onFocus={onFocus}
    >
      <div className="wrap">
        <div className="foot-top">
          <div className="foot-brand">
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
            <div className="foot-col">
              <h3>产品</h3>
              <ul>
                <li><a id="footConsole" href={CONSOLE_DEMO}>控制台</a></li>
                <li><a id="footStart" href={CONSOLE_DEMO + '?page=register'}>开始使用</a></li>
                <li><a id="footLogin" href={CONSOLE_DEMO + '?page=login'}>登录</a></li>
              </ul>
            </div>
            <div className="foot-col">
              <h3>文档</h3>
              <ul>
                <li><a href="#" data-noop onClick={noop}>文档</a></li>
                <li><a href="#" data-noop onClick={noop}>CLI</a></li>
              </ul>
            </div>
            <div className="foot-col">
              <h3>关于</h3>
              <ul>
                <li><a href="#" data-noop onClick={noop}>关于</a></li>
                <li><a href="#" data-noop onClick={noop}>更新日志</a></li>
                <li><a id="footPrivacy" href="?legal=privacy">隐私</a></li>
                <li><a id="footTerms" href="?legal=terms">条款</a></li>
              </ul>
            </div>
          </nav>
        </div>

        <div className="foot-bottom">
          <span>© 2026 深圳市山屿杉贸易有限公司 · <a id="footIcp" href="https://beian.miit.gov.cn/">粤ICP备2026172880号</a></span>
        </div>
      </div>
    </footer>
  )
}
