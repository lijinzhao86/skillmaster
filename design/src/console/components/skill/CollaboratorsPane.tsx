/**
 * 设置 › 协作者: the three blocks in the order the page wants them.
 *
 * ① 共享给 at the top — most people opening this page came to add somebody; ② the member list,
 * with its search and pager; ③ pending requests last, because their long-term home is a
 * notification surface. The number on the second-level item points at the same fact as ③.
 *
 * **名字列按名单里最长的那个名字留宽.** Each row is its own grid, and CSS cannot see "the widest
 * name across all rows" — so a hidden probe measures the text once and writes `--member-name`.
 */

import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import SkillMenu from './SkillMenu'
import type { Grant } from '../../fixtures/skill'
import {
  MEMBER_PAGE_SIZE,
  SKILL_OWNER,
  rungChoices,
  rungLabel,
  rungsAbove,
  rungsUpTo,
} from '../../fixtures/skill'
import { replace } from '../../address'
import { membersAddress } from '../../fixtures/skill'
import { Avatar, TypeNode } from './parts'

export interface CollaboratorsPaneProps {
  hidden: boolean
  enabled: boolean
  params: URLSearchParams
  grants: Grant[]
  requests: Grant[]
  publicRung: string | null
  onAdd: (who: string, role: string) => void
  onRole: (who: string, role: string) => void
  onWithdraw: (who: string) => void
  onApprove: (index: number, role: string) => void
  onDeny: (index: number) => void
}

export default function CollaboratorsPane({
  hidden,
  enabled,
  params,
  grants,
  requests,
  publicRung,
  onAdd,
  onRole,
  onWithdraw,
  onApprove,
  onDeny,
}: CollaboratorsPaneProps) {
  const [sharedRole, setSharedRole] = useState('invoke')
  const [handle, setHandle] = useState('')
  const pane = useRef<HTMLElement>(null)
  const list = useRef<HTMLUListElement>(null)

  const q = (params.get('q') ?? '').trim()
  const askedPage = parseInt(params.get('p') ?? '', 10)
  const rows: Array<Grant & { owner?: boolean }> = enabled
    ? [{ who: SKILL_OWNER, role: 'owner', owner: true }, ...grants]
    : []
  const matched = rows.filter((row) => q === '' || row.who.toLowerCase().includes(q.toLowerCase()))
  const pages = Math.max(1, Math.ceil(matched.length / MEMBER_PAGE_SIZE))
  let page = Number.isNaN(askedPage) || askedPage < 1 ? 1 : askedPage
  if (page > pages) page = pages
  const start = (page - 1) * MEMBER_PAGE_SIZE
  const shown = matched.slice(start, start + MEMBER_PAGE_SIZE)

  const offer = rungsAbove(publicRung)
  const effectiveShared = offer.some((rung) => rung.name === sharedRole) ? sharedRole : (offer[0]?.name ?? 'invoke')
  useEffect(() => {
    if (!offer.some((rung) => rung.name === sharedRole)) setSharedRole(offer[0]?.name ?? 'invoke')
  }, [publicRung])

  const hint = !enabled ? '' : publicRung === null
    ? (grants.length === 0
      ? '这个 skill 是私有的，目前只有你可以访问。在「共享给」中填写用户名并选择档位，即可添加协作者。'
      : '这个 skill 是私有的，只有你和下列协作者可以访问。')
    : '公开之后' + rungsUpTo(publicRung) + '对所有人开放。' +
      (grants.length === 0
        ? '目前没有单独授予个人的档位，可以通过「共享给」添加。'
        : '以下为单独授予个人的档位；个人的实际权限取两者中较高的一档。')

  const shortPage = matched.length > MEMBER_PAGE_SIZE && shown.length < MEMBER_PAGE_SIZE

  /* **名字列按名单里最长的那个名字留宽。** */
  useLayoutEffect(() => {
    if (!enabled) return
    const probe = document.createElement('span')
    probe.className = 'grant-who'
    probe.style.cssText = 'position:absolute;left:-9999px;top:0;width:auto;display:inline-block;visibility:hidden;white-space:pre;'
    document.body.appendChild(probe)
    let widest = 0
    const names = [SKILL_OWNER].concat(grants.map((grant) => grant.who), requests.map((request) => request.who))
    names.forEach((name) => {
      probe.textContent = name
      widest = Math.max(widest, probe.offsetWidth)
    })
    probe.remove()
    /* 上下的界：最窄 96（两个字的名字也不至于挤），最宽 320（用户名上限 30 个字符，量出来约 276）。 */
    pane.current?.style.setProperty('--member-name', Math.min(Math.max(widest + 2, 96), 320) + 'px')
  }, [enabled, grants, requests])

  /* **翻页只换框里的行，不动页面的形状。** A short second page reserves the height of ten rows;
     `-1` is the missing bottom border of a full page's last row. */
  useLayoutEffect(() => {
    const node = list.current
    if (!node) return
    if (!shortPage) {
      node.style.minHeight = ''
      return
    }
    let tallest = 0
    Array.from(node.children).forEach((row) => {
      tallest = Math.max(tallest, row.getBoundingClientRect().height)
    })
    node.style.minHeight = Math.round(tallest * MEMBER_PAGE_SIZE) - 1 + 'px'
  }, [shortPage, grants, requests, q, page])

  const step = (label: string, target: number, off: boolean) => {
    if (off) return <span className="is-off">{label}</span>
    return (
      <a
        className="pager-step"
        href={membersAddress(params, q, target)}
        onClick={(event) => { event.preventDefault(); replace(membersAddress(params, q, target)) }}
      >
        {label}
      </a>
    )
  }

  return (
    <section className="settings-pane" data-sub="collaborators" hidden={hidden} ref={pane}>
      <h2>协作者</h2>

      <section className="settings-block">
        <h3>共享给</h3>
        <div className="share-row">
          <input
            id="sharing-handle"
            className="control"
            type="text"
            autoComplete="off"
            spellCheck={false}
            placeholder="用户名"
            value={handle}
            onInput={(event) => setHandle(event.currentTarget.value)}
            onChange={(event) => setHandle(event.currentTarget.value)}
          />
          <span className="menu-slot" id="sharing-role">
            {enabled && <SkillMenu
              groups={[{ options: offer.map((rung) => ({ value: rung.name, label: rung.label })) }]}
              value={effectiveShared}
              config={{ ariaLabel: '权限', onPick: setSharedRole }}
            />}
          </span>
          <button
            type="button"
            className="button"
            id="sharing-add"
            onClick={() => {
              if (!enabled) return
              const who = handle.trim()
              if (who === '') return
              onAdd(who, effectiveShared)
              setHandle('')
            }}
          >
            共享
          </button>
        </div>
        <p className="hint">仅所有者与「管理」可以修改他人的档位。</p>
      </section>

      <section className="settings-block">
        <div className="member-head">
          <h3>协作者名单 <span className="count" id="memberCount">{enabled ? String(matched.length) : ''}</span></h3>
          <label className="search member-search">
            <span className="visually-hidden">搜索协作者</span>
            <svg viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="10.5" cy="10.5" r="6.5" stroke="currentColor" strokeWidth="1.8" />
              <line x1="15.4" y1="15.4" x2="20.6" y2="20.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" />
            </svg>
            <input
              id="memberQuery"
              className="control"
              type="search"
              autoComplete="off"
              spellCheck={false}
              placeholder="搜索用户名"
              value={q}
              onInput={(event) => replace(membersAddress(params, event.currentTarget.value.trim(), 1))}
              onChange={(event) => replace(membersAddress(params, event.currentTarget.value.trim(), 1))}
            />
          </label>
        </div>
        <p className="hint" id="grantsHint">{hint}</p>
        <ul className={'grants' + (shortPage ? ' is-partial' : '')} id="grantList" ref={list}>
          {shown.map((row) => {
            if (row.owner) {
              return (
                <li className="is-owner" key="owner">
                  <Avatar name={SKILL_OWNER} />
                  <span className="grant-who">{SKILL_OWNER}</span>
                  <TypeNode kind="person" />
                  <span className="role-tag">所有者</span>
                </li>
              )
            }
            return (
              <li key={row.who}>
                <Avatar name={row.who} />
                <span className="grant-who">{row.who}</span>
                <TypeNode kind={row.type} />
                {/* The rung is the one thing on this row that is edited rather than read. */}
                <SkillMenu
                  groups={[{
                    options: rungChoices(row.role, publicRung).map((rung) => ({ value: rung.name, label: rung.label })),
                  }]}
                  value={row.role}
                  config={{ ariaLabel: row.who + ' 的档位', onPick: (name) => onRole(row.who, name) }}
                />
                <button
                  type="button"
                  className="link-button danger withdraw"
                  onClick={() => onWithdraw(row.who)}
                >
                  撤销
                </button>
              </li>
            )
          })}
        </ul>
        <p className="hint member-none" id="memberNone" hidden={!enabled || matched.length > 0}>没有匹配的协作者。</p>
        <nav className="pager" id="memberPager" aria-label="分页">
          {enabled && matched.length > MEMBER_PAGE_SIZE && (
            <>
              <span className="pager-count">
                {shown.length === 0
                  ? '共 ' + matched.length + ' 人'
                  : '共 ' + matched.length + ' 人，第 ' + (start + 1) + '–' + (start + shown.length) + ' 人'}
              </span>
              {step('上一页', page - 1, page === 1)}
              {Array.from({ length: pages }, (_, index) => index + 1).map((number) => (
                number === page
                  ? <span className="is-current" aria-current="page" key={number}>{String(number)}</span>
                  : (
                    <a
                      className="pager-page"
                      key={number}
                      href={membersAddress(params, q, number)}
                      onClick={(event) => { event.preventDefault(); replace(membersAddress(params, q, number)) }}
                    >
                      {String(number)}
                    </a>
                  )
              ))}
              {step('下一页', page + 1, page === pages)}
            </>
          )}
        </nav>
      </section>

      <section className="settings-block" id="requestBlock" hidden={!enabled || requests.length === 0}>
        <h3>待处理的申请 <span className="count" id="requestCount">{enabled ? String(requests.length) : ''}</span></h3>
        <ul className="grants is-requests" id="requestList">
          {enabled && requests.map((request, index) => (
            <RequestRow
              key={request.who}
              request={request}
              index={index}
              offer={offer}
              onApprove={onApprove}
              onDeny={onDeny}
            />
          ))}
        </ul>
      </section>
    </section>
  )
}

function RequestRow({
  request,
  index,
  offer,
  onApprove,
  onDeny,
}: {
  request: Grant
  index: number
  offer: Array<{ name: string; label: string }>
  onApprove: (index: number, role: string) => void
  onDeny: (index: number) => void
}) {
  /* 批准时给哪一档由管理者定：预填申请的那一档，但可以改。能选的仍是公开没包掉的那些。 */
  const initial = offer.some((rung) => rung.name === request.role)
    ? request.role
    : (offer[0]?.name ?? request.role)
  const [wanted, setWanted] = useState(initial)
  const value = offer.some((rung) => rung.name === wanted) ? wanted : initial

  return (
    <li>
      <Avatar name={request.who} />
      <div className="grant-what">
        <span className="grant-who">{request.who}</span>
        {/* 申请带上要的那一档。不写「想以…参与」：已经在名单里的人申请的是更高一档。 */}
        <span className="grant-ask">{'申请「' + rungLabel(request.role) + '」'}</span>
      </div>
      <TypeNode kind={request.type} />
      <SkillMenu
        groups={[{ options: offer.map((rung) => ({ value: rung.name, label: rung.label })) }]}
        value={value}
        config={{ ariaLabel: '批准 ' + request.who + ' 的档位', onPick: setWanted }}
      />
      <div className="row-acts">
        <button type="button" className="button" onClick={() => onApprove(index, value)}>批准</button>
        <button type="button" className="button is-quiet" onClick={() => onDeny(index)}>拒绝</button>
      </div>
    </li>
  )
}
