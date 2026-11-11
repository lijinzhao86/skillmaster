import ConfirmDialog from '../../components/ConfirmDialog'
import CopyDialog, { type CopyVersion } from '../../components/skill/CopyDialog'
import ImportDialog from '../../components/skill/ImportDialog'

/**
 * The page's sheets, composed in the order the page draws them: the four ConfirmDialogs, then the
 * import sheet. They are kept out of the page because none of them has state of its own — each is
 * a question the page's hooks already answered.
 */
export interface SkillPageDialogsProps {
  publicBrowseOpen: boolean
  publicBrowsePending: boolean
  publicBrowseError: string | null
  onAcceptPublicBrowse: () => Promise<void>
  onCancelPublicBrowse: () => void

  offline: boolean
  /* v61: the service's three states, and the two counts the copy asks for. */
  phase: 'online' | 'pending' | 'offline'
  publicSkill: boolean
  joins: number
  grantees: number
  offlineOpen: boolean
  offlinePending: boolean
  offlineError: string | null
  onConfirmOffline: () => Promise<void>
  onCancelOffline: () => void

  /* v61: 入库 is a dependency, so it is confirmed once with what it means. */
  joinOpen: boolean
  joining: boolean
  onConfirmJoin: () => Promise<void>
  onCancelJoin: () => void

  removeOpen: boolean
  removePending: boolean
  onConfirmRemove: () => Promise<void>
  onCancelRemove: () => void

  deleteOpen: boolean
  deletePending: boolean
  deleteError: string | null
  onConfirmDelete: () => Promise<void>
  onCancelDelete: () => void

  namespace: string
  name: string
  /* v54: 复制. The versions are the published ones this rung can read; the dialog defaults to the
     live one. */
  copyOpen: boolean
  copyVersions: CopyVersion[]
  copyInitialVersion: string
  onCloseCopy: () => void
  importOpen: boolean
  importRung: string
  onCloseImport: () => void
  onNavigate: (href: string) => void
}

export default function SkillPageDialogs({
  publicBrowseOpen,
  publicBrowsePending,
  publicBrowseError,
  onAcceptPublicBrowse,
  onCancelPublicBrowse,
  offline,
  phase,
  publicSkill,
  joins,
  grantees,
  joinOpen,
  joining,
  onConfirmJoin,
  onCancelJoin,
  offlineOpen,
  offlinePending,
  offlineError,
  onConfirmOffline,
  onCancelOffline,
  removeOpen,
  removePending,
  onConfirmRemove,
  onCancelRemove,
  deleteOpen,
  deletePending,
  deleteError,
  onConfirmDelete,
  onCancelDelete,
  namespace,
  name,
  copyOpen,
  copyVersions,
  copyInitialVersion,
  onCloseCopy,
  importOpen,
  importRung,
  onCloseImport,
  onNavigate,
}: SkillPageDialogsProps) {
  /* v61: the confirmation says the consequence that applies — a ramp announcement, a cancellation,
     a resume, or an immediate stop — and never a generic one. */
  const pending = phase === 'pending'
  const ramped = publicSkill && joins > 0
  const impactParts: string[] = []
  if (grantees > 0) impactParts.push(`${grantees} 人获得授权`)
  if (joins > 0) impactParts.push(`${joins} 人入库`)
  // A3: the confirmation names all three facts — 公开/私有, grantees, joiners — in the same
  // sentence the design's own sheet uses.
  const visibilityWord = publicSkill ? '公开' : '私有'
  const impact = impactParts.length > 0
    ? `它是${visibilityWord}的；有 ${impactParts.join('、')}；删除后他们会收到通知。`
    : `它是${visibilityWord}的；没有其他人获得授权或入库。`
  return (
    <>
      {/* 公开 · 浏览 的确认: the selector does not change before this is answered, so cancelling
          leaves the page exactly as it was. */}
      <ConfirmDialog
        open={publicBrowseOpen}
        title="改为「公开 · 浏览」"
        body="公开之后，任何人都可以查看全部文件、下载并复制这个 skill；已经发生的下载与复制无法收回。"
        confirmLabel="确认公开"
        pendingLabel="正在公开…"
        danger={false}
        pending={publicBrowsePending}
        error={publicBrowseError}
        onConfirm={onAcceptPublicBrowse}
        onCancel={onCancelPublicBrowse}
      />

      {/* v61: 入库 is a dependency declaration, not a favourite. Said before the click takes
          effect, so somebody who needs an independent copy learns it here (ADR 0184). */}
      <ConfirmDialog
        open={joinOpen}
        title="入库这个 skill？"
        body="入库是把它收进你的库，不是复制一份内容。作者下线时会提前 30 天通知你，删除时也会通知你；要长期独立运行，请用「复制」留一份自己的。"
        confirmLabel="确认入库"
        pendingLabel="正在入库…"
        danger={false}
        pending={joining}
        error={null}
        onConfirm={onConfirmJoin}
        onCancel={onCancelJoin}
      />

      {/* 下线 / 恢复服务，与删除：同一个确认壳，取消是默认焦点，Esc 等于取消。 */}
      <ConfirmDialog
        open={offlineOpen}
        title={pending ? '取消下线？' : offline ? '恢复服务' : '下线这个 skill'}
        body={
          pending
            ? '取消后恢复正常服务，调用提醒消失；入库与授权的人会收到通知。'
            : offline
              ? '恢复后，当前线上版本直接重新对外，不需要重新上线。'
              : ramped
                ? `它已公开，有 ${joins} 人入库、${grantees} 人获得授权。确认后进入 30 天下线中：期间仍可调用，每次调用都会提醒；满 30 天后正式下线。入库与授权的人会收到通知。`
                : '下线后，所有调用都会失败；版本状态与当前版本都不变。'
        }
        confirmLabel={pending ? '确认取消下线' : offline ? '确认恢复服务' : ramped ? '开始下线' : '确认下线'}
        pendingLabel={pending ? '正在取消下线…' : offline ? '正在恢复服务…' : ramped ? '正在开始下线…' : '正在下线…'}
        danger={!pending && !offline}
        pending={offlinePending}
        error={offlineError}
        onConfirm={onConfirmOffline}
        onCancel={onCancelOffline}
      />

      {/* 移除加入的 skill: **a normal sheet, not red** (ADR 0056). Only my reference goes away;
          the skill stays public and callable, and the tags come back on re-join. */}
      <ConfirmDialog
        open={removeOpen}
        title="从我的 skill 移除？"
        body="我的标签与我的适用场景会保留，重新入库后会回来。这个 skill 仍在公域，仍可直接调用。"
        confirmLabel="确认移除"
        pendingLabel="正在移除…"
        danger={false}
        pending={removePending}
        error={null}
        onConfirm={() => void onConfirmRemove()}
        onCancel={onCancelRemove}
      />

      <ConfirmDialog
        open={deleteOpen}
        title="删除这个 skill？"
        body={`删除后，当前地址立即失效；名字会立即释放，可以用同一个名字创建新 skill。${impact}`}
        confirmLabel="确认删除"
        pendingLabel="正在删除…"
        pending={deletePending}
        error={deleteError}
        onConfirm={onConfirmDelete}
        onCancel={onCancelDelete}
      />

      {/* 复制 (v54): the source is this page's skill; the destination is the caller's own
          namespace, which is why the new name is asked for rather than the target address. */}
      <CopyDialog
        open={copyOpen}
        sourceNamespace={namespace}
        sourceName={name}
        versions={copyVersions}
        initialVersion={copyInitialVersion}
        onClose={onCloseCopy}
        onNavigate={onNavigate}
      />

      <ImportDialog
        open={importOpen}
        mode="version"
        namespace={namespace}
        name={name}
        rung={importRung}
        onClose={onCloseImport}
        onNavigate={onNavigate}
      />
    </>
  )
}
