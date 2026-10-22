/**
 * 公域里的一行结果，画成一张卡片。
 *
 * **「发现」的两处用它**：第一次进入的「为你推荐」，和搜索 / 分类之后的结果清单。两处是同一张
 * 卡片、同一套加入状态——如果各写一份，某一处的「已在库」迟早会漏掉来源。
 *
 * 卡片说六件事：它是谁、它做什么、什么时候该想起它、作者给它贴了哪些公开标签（搜索靠它们召回）、
 * 它现在是什么版本与什么时候更新的、它在哪个公开分类下。
 *
 * **动作只有一颗图标，在右上角。** 参考 B 站「一键三连」那排图标：控件就是图标本身，没有盒子；
 * 加入是一枚**书签**：未加入是描边，加入后是实心、转成主色，再点一下就退出——它自己就是开关，
 * 所以卡片底部不再重复一次「加入 / 移除」，只留状态与来源。为什么不是加号：加号表示「新建 /
 * 追加」，而「加进我的库」是一次**可切换的存**，描边 ↔ 实心正是这类动作的通用写法（星号留给
 * 以后的「收藏」，两者并排）。
 * **星号留给以后的「收藏」**，会并排出现在这颗图标的右边。
 *
 * 已在我的库里的条目（我创建的 / 共享给我的 / 之前加入的）也画这颗对勾，但它是静的：移除自己的
 * 库条目在 skill 页上，那里带确认（ADR 0056）。
 */

import { SOURCE_LABEL, categoryName, publicTagName } from '../fixtures/library'
import type { DiscoverSkill, Source } from '../fixtures/library'

export type JoinState = 'idle' | 'busy' | 'failed'


export default function ResultCard({
  skill,
  source,
  joined,
  onJoin,
  onRemove,
}: {
  skill: DiscoverSkill
  /** 它现在在不在我的库里；在的话只说来源。 */
  source: Source | null
  joined: JoinState
  onJoin: () => void
  /** 刚在这一屏加入的那张：再点一次那枚实心书签就撤销。库里原有的条目不给这个动作。 */
  onRemove?: () => void
}) {
  const corner =
    source === 'joined' && onRemove ? (
      /* 刚加入的那张：图标是开关，再点一下撤销。 */
      <button type="button" className="result-join" data-state="joined" onClick={onRemove} title="移除">
        <svg key="joined" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
        </svg>
        <span className="visually-hidden">移除</span>
      </button>
    ) : source !== null ? (
      /* 库里已有的条目：同一颗对勾，但是静的——移除在 skill 页上。 */
      <span className="result-inlibrary" aria-hidden="true" title={'已在库 · ' + SOURCE_LABEL[source]}>
        <svg viewBox="0 0 24 24" fill="none">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" fill="currentColor" />
        </svg>
      </span>
    ) : joined === 'busy' ? (
      /* 加入中：同一个位置换成一段转动的弧。 */
      <button type="button" className="result-join" data-state="busy" disabled title="加入中">
        <svg className="spin" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <circle cx="12" cy="12" r="8" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeDasharray="30 20" />
        </svg>
        <span className="visually-hidden">加入中…</span>
      </button>
    ) : (
      <button type="button" className="result-join" data-state="idle" onClick={onJoin} title="加入">
        <svg key="idle" viewBox="0 0 24 24" fill="none" aria-hidden="true">
          <path d="M6.5 5.2a1 1 0 0 1 1-1h9a1 1 0 0 1 1 1v14.4l-5.5-4.1-5.5 4.1Z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
        </svg>
        <span className="visually-hidden">加入</span>
      </button>
    )

  return (
    <li className="result" data-name={skill.name}>
      <div className="result-head">
        <a className="result-name" href="?page=skill">
          <span className="ns">{skill.namespace} /</span> {skill.name}
        </a>
        {corner}
      </div>
      <p className="result-title">{skill.title}</p>
      <p className="result-when">{skill.whenToUse}</p>
      {/* **公开标签紧跟在「什么时候该想起它」后面**：它们是作者写给搜索的词，读的人一眼就知道
          这一条为什么会被搜到；分类仍在底部，那是平台的归类，不是作者的召回词。 */}
      {skill.publicTags.length > 0 && (
        <p className="result-public">
          {skill.publicTags.map((id) => (
            <span key={id}>{'#' + publicTagName(id)}</span>
          ))}
        </p>
      )}
      {/* 版本与更新日期是卡片上唯一两个「它现在怎么样」的事实；没有用量信号，所以不画调用次数、
          也不画星级（那是别的市场的字段，我们产不出来）。 */}
      <p className="result-meta">{skill.version + '　·　更新于 ' + skill.updated}</p>
      <div className="result-foot">
        {/* **卡片上只有一枚分类**：一个 skill 只在一个货架上（2026-10-23）。 */}
        <div className="result-tags">
          {skill.category !== null && (
            <span className="chip is-plain">{categoryName(skill.category)}</span>
          )}
        </div>
        <div className="result-act">
          {source !== null ? (
            <span className="result-in" data-source={source}>
              {'已在库　·　' + SOURCE_LABEL[source]}
            </span>
          ) : joined === 'failed' ? (
            <>
              <span className="result-error" role="alert">没有加入：服务器没有响应。</span>
              <button type="button" className="button is-quiet" onClick={onJoin}>重试</button>
            </>
          ) : null}
        </div>
      </div>
    </li>
  )
}
