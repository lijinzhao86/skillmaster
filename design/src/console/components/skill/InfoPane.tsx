/**
 * 信息: the smallest complete description of a skill, and the one pane the 调用 rung can open.
 *
 * **左栏是「读」的，右栏是「瞥」的。** Description, when to use it and where it runs are sentences;
 * the version and the two sets of tags are values.
 *
 * **三层词在右栏，长得像但说的是三件事**（顺序：分类 → 公开标签 → 我的标签）。公开分类是平台词表、
 * 所有能看见这个 skill 的人都读得到，所有者与「管理」可以改；公开标签是作者的召回词，同样谁都能看见，
 * 但只有作者（管理 / 所有者）能贴与摘；我的标签是「人 + skill」、只有本人看得到，**而且只有库条目
 * 生效时才有这一区**——条目休眠时整块不画，因为那时候连条目本身都不在列表里（PRD #10/#11）。
 *
 * 右栏仍然是「页面地面上的一行行注释」，不是卡片：分组标题 + 一组胶囊 + 标题右边一颗「+」。
 * **点「+」就地编辑，不开弹窗**（2026-10-22 用户定，按行业的两张面孔分开）：
 * 公开分类是固定词表——「+」打开一个多选菜单（Linear / GitLab 的 label picker），点一下加上或拿掉，
 * 菜单不关；我的标签是自由标签——「+」展开一个标签输入框（GitHub topics / Jira），胶囊带「摘掉」，
 * 打字回车添加或新建，再点那颗变成「✓」的同一颗按钮收工。
 * 只读视图里 0 个分类不画空块；编辑视图里 0 个要说「还没有」，因为那里有一个下一步。
 *
 * The 申请 block is drawn while there is still a rung above the reader: 调用 and 浏览 are not
 * authors, 写入 and 维护 are in the list but want one rung higher, and 管理/所有者 have nothing
 * above them. **And only once the skill is in their library** (2026-11-05 用户定，ADR 0150): a
 * public skill a passer-by has not joined is something to join, not something to ask a rung on —
 * the ask would land in the owner's queue from someone who has not adopted the skill.
 */

import { useState } from 'react'
import MenuSelect from '../MenuSelect'
import MyTagPicker from '../tags/MyTagPicker'
import SkillMenu from './SkillMenu'
import type { AtLeast, Version } from '../../fixtures/skill'
import { FRONTMATTER, RUNG_NOTES, rungLabel, rungsAbove } from '../../fixtures/skill'
import {
  CATEGORIES,
  DISCOVER,
  MAX_PUBLIC_TAGS_PER_SKILL,
  categoryName,
} from '../../fixtures/library'

export type EditGroup = 'categories' | 'public-tags' | 'my-tags'

const STANDING: Record<string, string> = {
  invoke: '当前档位为「调用」：可以调用，但无法查看文件清单。',
  browse: '当前档位为「浏览」：可以读取，但无法提交版本。',
  write: '当前档位为「写入」：可以提交版本，但无法上线。',
  maintain: '当前档位为「维护」：可以上线，但无法修改可见性与协作者。',
}

export interface InfoPaneProps {
  hidden: boolean
  tier: string
  atLeast: AtLeast
  live: Version | null
  myRequest: { role: string } | null
  askedRung: string | null
  onAskedRung: (role: string) => void
  onSubmitRequest: (role: string) => void
  onWithdraw: () => void
  /** 这一个 skill 所在的货架（平台词表里的一个 id）；没有归类的是 null。 */
  category: string | null
  /** 公开标签（id 与名字）：作者的词，谁都能看见。 */
  publicTags: Array<{ id: string; name: string }>
  /** 全部公开标签定义：输入框要拿它做候选，找不到才新建。 */
  publicTagDefs: Array<{ id: string; name: string }>
  /** 只有作者（管理 / 所有者）能贴与摘公开标签。 */
  canEditPublicTags: boolean
  onPublicTagPick: (id: string) => void
  onPublicTagCreate: (name: string) => void
  onPublicTagRemove: (id: string) => void
  /** 我的标签（id 与名字）。只有库条目生效时才会被画出来。 */
  myTags: Array<{ id: string; name: string }>
  /** 全部标签定义：输入框要拿它做候选，找不到才新建。 */
  tagDefs: Array<{ id: string; name: string }>
  /** 我的标签这一区在不在：库条目生效时在，休眠 / 不在库时不在。 */
  inLibrary: boolean
  canEditCategories: boolean
  /** 就地编辑的每一次改动都立即生效（不产生新版本）。 */
  onCategoryChange: (id: string | null) => void
  onTagPick: (id: string) => void
  onTagCreate: (name: string) => void
  onTagRemove: (id: string) => void
  /** 地址里的 `?edit=`：进来就展开那一组。 */
  initialEdit?: EditGroup | null
  /** 可见但当前不可加入 / 暂时不可见时，右栏那一句原因。 */
  membershipNote: string
  /** v49：来源、许可与使用分桶。三者都缺时不画事实区。 */
  origin?: {
    label: string
    kind: string
    locator?: string
    /** 来源网页（GitHub 仓库）；官方没有独立页面，这时 locator 只是一句说明。 */
    url?: string
    revision?: string
    signals?: Array<{ label: string; display: string; url?: string }>
  }
  license?: { spdx: string; status: string; url?: string; allows: string[]; requires: string[]; copyright?: string; declared?: string; source?: string }
  usage?: { bucket: string; lastUsed?: string; joins?: number }
  /** v54：这一版是从哪个 skill 的哪一版复制来的。地址是同站链接；版本写成 `@1.2.3`。 */
  copiedFrom?: { address: string; version: string; url?: string } | null
}

/** A frontmatter field the author may write as one string or a list. */
const asList = (value: string | string[] | undefined): string[] =>
  value === undefined ? [] : Array.isArray(value) ? value : [value]

/** 摘要只写许可名；没有名字就不写。状态词（仅声明/未声明）留给 CLI。 */
function licenseNameOf(license: { spdx: string; status: string }): string {
  if (license.status === 'ok' || license.status === 'declared-only') return license.spdx
  if (license.status === 'other') return '其他许可'
  return ''
}

export default function InfoPane({
  hidden,
  tier,
  atLeast,
  live,
  myRequest,
  askedRung,
  onAskedRung,
  onSubmitRequest,
  onWithdraw,
  category,
  publicTags,
  publicTagDefs,
  canEditPublicTags,
  onPublicTagPick,
  onPublicTagCreate,
  onPublicTagRemove,
  myTags,
  inLibrary,
  tagDefs,
  canEditCategories,
  onCategoryChange,
  onTagPick,
  onTagCreate,
  onTagRemove,
  initialEdit = null,
  membershipNote,
  origin,
  license,
  usage,
  copiedFrom = null,
}: InfoPaneProps) {
  /* 我的标签与公开标签都是自由输入，就地展开；公开分类是固定词表，点「+」开多选菜单。 */
  const [editingTags, setEditingTags] = useState(initialEdit === 'my-tags')
  const [editingPublicTags, setEditingPublicTags] = useState(initialEdit === 'public-tags')
  const options = rungsAbove(tier)
  const show = inLibrary && tier !== 'owner' && options.length > 0
  const effective = options.some((rung) => rung.name === askedRung)
    ? (askedRung as string)
    : options[0]?.name ?? ''
  const standing = STANDING[tier] ?? ''
  const hasCategory = category !== null
  const whenToUse = asList(FRONTMATTER.metadata.when_to_use)[0] ?? ''

  return (
    <div className="tab-panel" data-tab="info" hidden={hidden}>
      <section className="info-pane">
        <div className="info-main">
          <section className="info-block">
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true"><use href="#descMark" /></svg>
              描述
            </span>
            <p className="info-text" id="infoDesc">{FRONTMATTER.description}</p>
          </section>
          <section className="info-block" id="infoWhen" hidden={!whenToUse}>
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true"><use href="#clockMark" /></svg>
              适用场景
            </span>
            <p className="info-text" id="infoWhenText">{whenToUse}</p>
          </section>
          <section className="info-block" id="infoEnv" hidden={!FRONTMATTER.compatibility}>
            <span className="info-label">
              <svg className="info-icon" aria-hidden="true"><use href="#envMark" /></svg>
              运行环境
            </span>
            <p className="info-text" id="infoEnvText">{FRONTMATTER.compatibility || ''}</p>
          </section>
        </div>

        <aside className="info-rail">
          <section className="info-rail-item" id="infoVersion">
            <svg className="info-rail-icon" aria-hidden="true"><use href="#versionMark" /></svg>
            <span className="info-rail-label">版本</span>
            <span className="info-rail-value" id="infoVersionText">{live ? live.name : '未上线'}</span>
          </section>

          {/* **公开分类：一个 skill 只在一个货架上**（2026-10-23 用户定）。所有人可见，
              所有者与「管理」可改；单选菜单，选中即替换，菜单里有一行「清除」。 */}
          <section className="info-group" id="infoCategories" hidden={!hasCategory && !canEditCategories}>
            <div className="info-group-head">
              <span className="info-group-title">分类</span>
              {canEditCategories && (
                <MenuSelect
                  id="editCategories"
                  initialOpen={initialEdit === 'categories'}
                  groups={[{ options: CATEGORIES.map((entry) => ({
                    value: entry.id,
                    label: entry.name,
                    /* 计数是公域里在这个货架上的 skill 数。 */
                    number: String(DISCOVER.filter((skill) => skill.category === entry.id).length),
                    /* 停用的词不可新增；已经贴在它上面的仍是它。 */
                    disabled: entry.stopped === true && category !== entry.id,
                  })) }]}
                  value={category ?? ''}
                  config={{
                    ariaLabel: '添加分类',
                    tool: 'add',
                    menuMinWidth: 200,
                    triggerText: hasCategory ? categoryName(category) : '分类',
                    onPick: (id) => onCategoryChange(id),
                    onClear: () => onCategoryChange(null),
                  }}
                />
              )}
            </div>
            <div className="info-tags" id="infoTags" hidden={!hasCategory}>
              {hasCategory && <span className="chip is-plain">{categoryName(category)}</span>}
            </div>
            {!hasCategory && canEditCategories && <p className="info-empty">还没有分类。</p>}
          </section>

          {/* **公开标签：作者的召回词，谁都能看见；只有作者能贴与摘。** 只读视图里 0 个不画这一块
              （那是一个空块，不是一件事）；作者视图里 0 个要说「还没有」，因为那里有一个下一步。
              显示成 `#名字`：分类是平台的窄词表，公开标签是自由词，井号把两者分开。 */}
          <section
            className="info-group"
            id="infoPublicTags"
            hidden={publicTags.length === 0 && !canEditPublicTags}
            onKeyDown={(event) => {
              if (event.key === 'Escape') setEditingPublicTags(false)
            }}
            onBlur={(event) => {
              const next = event.relatedTarget as Node | null
              if (next !== null && event.currentTarget.contains(next)) return
              setEditingPublicTags(false)
            }}
          >
            <div className="info-group-head">
              <span className="info-group-title">公开标签</span>
              {canEditPublicTags && (
                <button
                  type="button"
                  className="icon-add"
                  id="editPublicTags"
                  aria-label={editingPublicTags ? '完成编辑公开标签' : '添加公开标签'}
                  aria-expanded={editingPublicTags}
                  aria-controls="publicTagPicker"
                  title={editingPublicTags ? '完成' : '添加公开标签'}
                  onClick={() => setEditingPublicTags(!editingPublicTags)}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    {editingPublicTags
                      ? <path d="M5 12.4l4.4 4.4L19 7.6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      : <path d="M12 6v12M6 12h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />}
                  </svg>
                </button>
              )}
            </div>
            <div className="info-tags" id="infoPublicTagsList" hidden={publicTags.length === 0 && !editingPublicTags}>
              {publicTags.map((tag) => (
                <span className="chip is-public" key={tag.id}>
                  {'#' + tag.name}
                  {editingPublicTags && (
                    <button
                      type="button"
                      className="filter-x"
                      aria-label={'摘掉「' + tag.name + '」'}
                      onMouseDown={(event) => event.preventDefault()}
                      onClick={() => onPublicTagRemove(tag.id)}
                    >
                      ×
                    </button>
                  )}
                </span>
              ))}
              {editingPublicTags && (
                <MyTagPicker
                  chosen={publicTags.map((tag) => tag.id)}
                  definitions={publicTagDefs}
                  onPick={onPublicTagPick}
                  onCreate={onPublicTagCreate}
                  idPrefix="publicTag"
                  max={MAX_PUBLIC_TAGS_PER_SKILL}
                  label="添加公开标签"
                  noun="公开标签"
                  menuTitle="已有的公开标签"
                  hash
                />
              )}
            </div>
            {publicTags.length === 0 && !editingPublicTags && canEditPublicTags && (
              <p className="info-empty">还没有公开标签。</p>
            )}
          </section>

          {/* **我的标签：只有库条目生效时才有这一区。** 休眠时整块不画。 */}
          {inLibrary && (
            <section
              className="info-group"
              id="infoMyTags"
              onKeyDown={(event) => {
                if (event.key === 'Escape') setEditingTags(false)
              }}
              onBlur={(event) => {
                /* **焦点只要离开这一组就回到普通状态**：不管是离开输入框、还是离开某颗「×」。
                   组内互跳（点「×」、点「✓」）不算——`mousedown` 也拦了，焦点根本不动。 */
                const next = event.relatedTarget as Node | null
                if (next !== null && event.currentTarget.contains(next)) return
                setEditingTags(false)
              }}
            >
              <div className="info-group-head">
                <span className="info-group-title">我的标签</span>
                <button
                  type="button"
                  className="icon-add"
                  id="editMyTags"
                  aria-label={editingTags ? '完成编辑标签' : '添加标签'}
                  aria-expanded={editingTags}
                  aria-controls="myTagPicker"
                  title={editingTags ? '完成' : '添加标签'}
                  onClick={() => setEditingTags(!editingTags)}
                >
                  <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
                    {editingTags
                      ? <path d="M5 12.4l4.4 4.4L19 7.6" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                      : <path d="M12 6v12M6 12h12" stroke="currentColor" strokeWidth="2" strokeLinecap="round" />}
                  </svg>
                </button>
              </div>
              {/* **输入框是这一行里的最后一颗胶囊**：点「+」后它出现在最后一颗标签右边，
                  打完回车就变成一颗新标签，输入框留在原处继续。 */}
              <div className="info-tags" id="infoMyTagsList" hidden={myTags.length === 0 && !editingTags}>
                {myTags.map((tag) => (
                  <span className="chip is-mine" key={tag.id}>
                    {tag.name}
                    {editingTags && (
                      <button
                        type="button"
                        className="filter-x"
                        aria-label={'摘掉「' + tag.name + '」'}
                        /* 拦下 mousedown：焦点留在输入框里，删一颗不会把编辑关掉。 */
                        onMouseDown={(event) => event.preventDefault()}
                        onClick={() => onTagRemove(tag.id)}
                      >
                        ×
                      </button>
                    )}
                  </span>
                ))}
                {editingTags && (
                  <MyTagPicker
                    chosen={myTags.map((tag) => tag.id)}
                    definitions={tagDefs}
                    onPick={onTagPick}
                    onCreate={onTagCreate}
                  />
                )}
              </div>
              {myTags.length === 0 && !editingTags && <p className="info-empty">还没有标签。</p>}
            </section>
          )}

          {membershipNote !== '' && (
            <p className="info-note" id="infoMembership">{membershipNote}</p>
          )}

          {/* **来源与许可：右栏、标签下面的一次展开**（2026-11-06 用户定）：它们是次要信息，
              和分类、标签一样是「查一下」的那一类，所以跟着右栏走。默认收起，只露一行
              「GitHub · MIT」，点开才是仓库与许可全文。 */}
          {(origin || license || copiedFrom) && (
            <details className="info-source" id="infoSource">
              <summary className="info-source-head">
                <span className="info-source-title">来源与许可</span>
                <span className="info-source-hint">
                  {[copiedFrom ? '复制自 ' + copiedFrom.address : origin?.label,
                    license === undefined || license === null ? '' : licenseNameOf(license)]
                    .filter((part) => part !== undefined && part !== '')
                    .join(' · ')}
                </span>
                <svg className="info-source-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                  <path d="M7 10l5 5 5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              </summary>
              <div className="info-source-body">
                {copiedFrom && (
                  /* **复制自：源 skill 的地址与版本**（v54）。它是这一版的身世，不是作者的来源；
                     所以放在「来源」之前、也仍走这一次展开——低频，不该占首屏。地址是同站链接，
                     点回源 skill；版本写 `@1.2.3`，与 CLI 的寻址同一个写法。 */
                  <div className="info-fact" id="infoCopiedFrom">
                    <span className="info-fact-label">复制自</span>
                    <span className="info-fact-value">
                      {copiedFrom.url === undefined ? copiedFrom.address : (
                        <a href={copiedFrom.url}>{copiedFrom.address}</a>
                      )}
                      <span className="info-fact-note"> · @{copiedFrom.version}</span>
                    </span>
                  </div>
                )}
                {origin && (
                  /* **来源只写平台与仓库**（2026-11-06 用户定）：`GitHub · coreyhaines31/marketingskills`。
                     commit、指标与最近校验不上这一页——右栏是「查一下」，不是审计；它们留在
                     `skill show` 的输出里，需要精确值时去那里看。 */
                  <div className="info-fact" id="infoOrigin">
                    <span className="info-fact-label">来源</span>
                    <span className="info-fact-value">
                      {origin.label}
                      {origin.locator && (
                        <span className="info-fact-note">
                          {' · '}
                          {/* 仓库是一个地址，就做成链接：点开是那个仓库本身，比让读者自己抄一遍好。 */}
                          {origin.url === undefined ? origin.locator : (
                            <a href={origin.url} target="_blank" rel="noreferrer">{origin.locator}</a>
                          )}
                        </span>
                      )}
                    </span>
                  </div>
                )}
                {license && license.status !== 'missing' && (
                  <div className="info-fact" id="infoLicense">
                    <span className="info-fact-label">许可证</span>
                    <span className="info-fact-value">
                      {license.status === 'declared-only' ? (
                        <>{license.spdx}</>
                      ) : license.status === 'other' ? (
                        <>
                          其他许可
                          {license.url && <> · <a href={license.url} target="_blank" rel="noreferrer">查看许可全文</a></>}
                        </>
                      ) : (
                        <>
                          {/* **只写许可名、版权行与全文链接**（2026-11-06 用户定）：可做/要做什么
                              不再铺在页面上——它是给需要按条款办事的人看的，全文里就有；这一页只
                              回答「它是什么许可」。CLI 的「许可要点」仍保留那两句。 */}
                          {license.spdx}
                          {license.copyright && <span className="info-fact-note"> · {license.copyright}</span>}
                          {license.url && <> · <a href={license.url} target="_blank" rel="noreferrer">查看许可全文</a></>}
                        </>
                      )}
                    </span>
                  </div>
                )}
              </div>
            </details>
      )}
        </aside>
      </section>

      <details className="ask" id="askBlock" hidden={!show}>
        <summary className="ask-summary">
          <span className="ask-summary-title">需要更高权限？</span>
          <span className="ask-summary-hint">申请更高的档位</span>
          <svg className="ask-summary-caret" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M7 10l5 5 5-5" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
        </summary>
        <p className="hint" id="askWhy">
          {show ? (myRequest ? standing : standing + '如需更高的档位，请在下方申请。') : ''}
        </p>
        <div className="ask-form" id="askForm" hidden={!!myRequest}>
          <span className="ask-label" id="askRoleLabel">申请档位</span>
          <span className="menu-slot" id="askRole">
            {show && (
              <SkillMenu
                groups={[{ options: options.map((rung) => ({ value: rung.name, label: rung.label })) }]}
                value={effective}
                config={{ ariaLabel: '申请档位', ariaLabelledBy: 'askRoleLabel', onPick: onAskedRung }}
              />
            )}
          </span>
          <button
            type="button"
            className="button"
            id="askSubmit"
            onClick={() => onSubmitRequest(effective)}
          >
            申请
          </button>
        </div>
        <p className="ask-note" id="askNote" hidden={!!myRequest}>
          {show ? (RUNG_NOTES[effective] ?? '') : ''}
        </p>
        <div className="ask-pending" id="askPending" hidden={!myRequest}>
          <span className="chip is-plain"><span className="dot"></span><span className="chip-text">等待处理</span></span>
          <span id="askPendingText">
            {myRequest ? '已提交「' + rungLabel(myRequest.role) + '」的申请，由 dev 或「管理」审批。' : ''}
          </span>
          <button type="button" className="link-button" id="askWithdraw" onClick={onWithdraw}>撤回申请</button>
        </div>
      </details>
    </div>
  )
}
