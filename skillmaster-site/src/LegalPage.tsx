/**
 * The 《隐私政策》 and 《服务条款》 reading pages (v56).
 *
 * **Moved from `design/src/home/LegalPage.tsx`**: same layout, same classes. Three product
 * differences: the addresses are `/privacy` and `/terms` (nginx falls back to this SPA), the
 * wordmark and the back link go to `/`, and the account in the bar comes from the session.
 *
 * **The home deck's lock is given back while this is mounted.** `html,body` are
 * `height:100%;overflow:hidden` because the home page is a two-screen deck; a legal page is an
 * ordinary document, so the class goes on while it is mounted and off when it leaves. `document.title`
 * follows the document too.
 */

import { useEffect } from 'react'
import SiteFooter from './SiteFooter'
import SiteNav from './SiteNav'
import { PRIVACY, TERMS } from './legal/legalText'
import type { LegalDoc } from './legal/legalText'

export type LegalKind = 'privacy' | 'terms'

const DOCS: Record<LegalKind, LegalDoc> = { privacy: PRIVACY, terms: TERMS }

export interface LegalPageProps {
  kind: LegalKind
  accountName: string | null
}

export default function LegalPage({ kind, accountName }: LegalPageProps) {
  const doc = DOCS[kind]

  useEffect(() => {
    const previous = document.title
    document.title = `${doc.title} · SkillMaster`
    document.documentElement.classList.add('legal-open')
    document.body.classList.add('legal-open')
    return () => {
      document.title = previous
      document.documentElement.classList.remove('legal-open')
      document.body.classList.remove('legal-open')
    }
  }, [doc.title])

  return (
    <>
      <SiteNav accountName={accountName} brandHref="/" solid />
      <main className="legal">
        <div className="wrap legal-wrap">
          <a className="legal-back" id="legalBack" href="/">← 返回首页</a>
          <h1>{doc.title}</h1>
          <p className="legal-meta">生效日期：{doc.effective}　·　版本 {doc.version}</p>

          <article className="legal-body">
            {doc.sections.map((section) => (
              <section key={section.heading}>
                <h2>{section.heading}</h2>
                {section.paragraphs.map((paragraph) => (
                  <p key={paragraph}>{paragraph}</p>
                ))}
                {section.list && (
                  <ul>
                    {section.list.map((item) => (
                      <li key={item}>{item}</li>
                    ))}
                  </ul>
                )}
              </section>
            ))}
          </article>
        </div>
      </main>
      <SiteFooter staticFooter />
    </>
  )
}
