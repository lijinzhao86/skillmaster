import ConfirmDialog from '../../components/ConfirmDialog'
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
  offlineOpen: boolean
  offlinePending: boolean
  offlineError: string | null
  onConfirmOffline: () => Promise<void>
  onCancelOffline: () => void

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
  importOpen,
  importRung,
  onCloseImport,
  onNavigate,
}: SkillPageDialogsProps) {
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

      {/* 下线 / 恢复服务，与删除：同一个确认壳，取消是默认焦点，Esc 等于取消。 */}
      <ConfirmDialog
        open={offlineOpen}
        title={offline ? '恢复服务' : '下线这个 skill'}
        body={
          offline
            ? '恢复后，当前线上版本直接重新对外，不需要重新上线。'
            : '下线后，所有调用都会失败；版本状态与当前版本都不变。'
        }
        confirmLabel={offline ? '确认恢复服务' : '确认下线'}
        pendingLabel={offline ? '正在恢复服务…' : '正在下线…'}
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
        body="标签会保留，重新入库后会回来。这个 skill 仍在公域，仍可直接调用。"
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
        body="删除后，该名称将无法用于创建新 skill，当前地址立即失效。"
        confirmLabel="确认删除"
        pendingLabel="正在删除…"
        pending={deletePending}
        error={deleteError}
        onConfirm={onConfirmDelete}
        onCancel={onCancelDelete}
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
