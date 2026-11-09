/**
 * 《隐私政策》与《服务条款》的阅读页。
 *
 * **这一页是新的**（v56）。首页是一副两屏的牌，滑一下就换屏；法务页不是牌：它是一篇要往下读
 * 的长文，所以它按文档流排——固定导航、一条窄阅读栏、页脚落在文末。两种页面共用同一副导航与
 * 页脚（`SiteNav` / `SiteFooter`），差别只有阅读栏与页脚不必占满一屏。
 *
 * **正文在 `legalText.ts`**：结构参照腾讯 SkillHub、扣子与 WorkBuddy 的公开协议，句子按本产品
 * 的真实数据流重写；运营主体信息已填入。文本与它承诺的行为同一版上线；执业律师审阅排在
 * 内测版上线之后，公司名称变更后还要重刷。
 *
 * **演示件的链接用查询参数**（`?legal=privacy`、品牌与「返回首页」用 `?`）；产品里换成
 * `/privacy`、`/terms` 与 `/`，本组件通过 `brandHref` 接收品牌地址，实现时传 `/`。
 *
 * Reading measure: the site's container is 1180px, which is a landing-page width. A legal document
 * is read line by line, so the column narrows to `--read` (see `styles.css`). This is a new token,
 * and it exists for exactly this page.
 */

import { useEffect } from 'react'
import SiteFooter from './SiteFooter'
import SiteNav from './SiteNav'
import { PRIVACY, TERMS } from './legalText'
import type { LegalDoc } from './legalText'

export type LegalKind = 'privacy' | 'terms'

const DOCS: Record<LegalKind, LegalDoc> = { privacy: PRIVACY, terms: TERMS }

export interface LegalPageProps {
  kind: LegalKind
  signedIn: boolean
}

export default function LegalPage({ kind, signedIn }: LegalPageProps) {
  const doc = DOCS[kind]

  /* **The home sheet locks the document; a legal page has to give it back.** `html,body` are
     `height:100%;overflow:hidden` because the home page is a two-screen deck. These pages are
     ordinary documents, so the lock comes off while one is mounted and goes back on when it
     leaves. The product site carries the same lock and needs the same switch. */
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
      <SiteNav signedIn={signedIn} brandHref="?" solid />
      <main className="legal">
        <div className="wrap legal-wrap">
          <a className="legal-back" id="legalBack" href="?">← 返回首页</a>
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
