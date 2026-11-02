import { useRef } from 'react'
import ConfirmDialog from '../../components/ConfirmDialog'
import EditorFileTree from '../../components/version/EditorFileTree'
import EditorHeader from '../../components/version/EditorHeader'
import EditorNotices from '../../components/version/EditorNotices'
import EditorPane from '../../components/version/EditorPane'
import { useSession } from '../../store'
import { infoText, previewKindOf } from './editorModel'
import { fileHrefOf, skillHrefOf, versionHrefOf, versionsHrefOf } from './links'
import type { FailureReporter, LabelsInit, VersionEditorPageProps } from './types'
import { useEditorBannerHeight } from './useEditorBannerHeight'
import { useEditorCaret } from './useEditorCaret'
import { useEditorDocument } from './useEditorDocument'
import { useEditorKeyboard } from './useEditorKeyboard'
import { useEditorLabels } from './useEditorLabels'
import { useEditorLeave } from './useEditorLeave'
import { useEditorLifecycle } from './useEditorLifecycle'
import { useEditorSave } from './useEditorSave'
import { useEditorTree } from './useEditorTree'

/**
 * 新建版本 / 编辑草稿 (v8).
 *
 * The page holds a base in memory — a live version, the newest draft, or the skill row's own minimal
 * `SKILL.md` — and creates the draft on the **first content change**, never when the page opens
 * (v8 TD §架构: copying a live version verbatim would carry its digest, and `UNIQUE (skill_id,
 * digest)` refuses two rows with one content).
 *
 * Everything visible here is the design's own markup (design/skillmaster-控制台演示.html
 * `#page-editor`, 2026-10-20/21), copied class by class. What changed on the way into the framework:
 * the demo's `?page=`/`state=` fiction became real addresses and requests, its fixtures became the
 * server's answers, and the local-only bookkeeping (empty folders, unsaved buffers) stayed local.
 *
 * **The page is the state machine and the composition.** Each hook owns one responsibility — the
 * documents, the labels, the save/notice flow, the tree's interactions, the leave guard, the
 * keyboard, the banner measurement — and the two bridges below are the only names that travel
 * backwards: the save hook reports a failed file read that the document's `ensureText` raises, and
 * the label hook hands the load the names it just read. Rendering is the four
 * `components/version/Editor*` pieces.
 */
export type { VersionEditorPageProps }

export default function VersionEditorPage({
  namespace,
  name,
  version: pathVersion,
  mode,
  file: requestedFile,
}: VersionEditorPageProps) {
  const { account } = useSession()

  // The two forward references between hooks: both are written during render and read only from a
  // callback that `await`s or runs after mount, by which time the writer has been assigned.
  const labelsInit = useRef<LabelsInit | null>(null)
  const reportFailure = useRef<FailureReporter>(() => {})
  const leavingPage = useRef(false)
  const canSaveRef = useRef(false)

  const doc = useEditorDocument({
    namespace,
    name,
    mode,
    pathVersion,
    requestedFile,
    username: account?.username ?? null,
    reportFailure,
    initializeLabels: labelsInit,
  })
  const labels = useEditorLabels({
    namespace,
    name,
    currentVersionRef: doc.currentVersionRef,
    isNewRef: doc.isNewRef,
  })
  labelsInit.current = labels.init

  const save = useEditorSave({ namespace, name, doc, labels, canSaveRef, leavingPage })
  reportFailure.current = save.showFailure

  const caret = useEditorCaret(doc)
  const tree = useEditorTree({ doc, labels, save, caret })
  const leave = useEditorLeave({ doc, leavingPage })
  const keyboard = useEditorKeyboard({ canSaveRef, save, tree, leave })
  const banners = useEditorBannerHeight({
    conflict: save.conflict,
    duplicate: save.duplicate,
    errorBanner: save.errorBanner,
    labelStatus: labels.labelStatus,
  })

  const skillHref = skillHrefOf(namespace, name)
  const versionsHref = versionsHrefOf(skillHref)
  const readSuffix = doc.readSuffix
  const viewHref = readSuffix === undefined ? versionsHref : versionHrefOf(skillHref, readSuffix)

  const canSave =
    !save.saving &&
    !doc.currentBinary &&
    doc.currentDirty &&
    (!doc.isNew || labels.draftName.trim() !== '')
  /** The keyboard listener is added once; it has to see what this render computed. */
  canSaveRef.current = canSave

  useEditorLifecycle({
    load: doc.load,
    syncBannerHeight: banners.syncBannerHeight,
    onKeydown: keyboard.onKeydown,
    onDocumentMouseDown: keyboard.onDocumentMouseDown,
    onBeforeUnload: leave.onBeforeUnload,
    setTreeCollapsed: doc.setTreeCollapsed,
  })

  return (
    <section className="page" id="page-editor">
      <nav className="crumbs" aria-label="面包屑">
        <a
          href={versionsHref}
          id="edBackCrumb"
          onClick={(event) => leave.guardLeave(event, versionsHref, event.currentTarget)}
        >
          版本
        </a>
        <span className="crumbs-sep">/</span>
        <span className="crumbs-here">{doc.isNew ? '新版本' : '编辑'}</span>
      </nav>

      <EditorHeader
        hidden={doc.loading || doc.missing}
        isNew={doc.isNew}
        draftName={labels.draftName}
        draftNote={labels.draftNote}
        onNameInput={labels.onNameInput}
        onNoteInput={labels.onNoteInput}
        commitName={labels.commitName}
        commitNote={labels.commitNote}
        onNameEnter={labels.onNameEnter}
        onNoteEnter={labels.onNoteEnter}
        facts={doc.facts}
        labelStatus={labels.labelStatus}
        canSave={canSave}
        saving={save.saving}
        save={save.save}
        viewHref={viewHref}
        guardLeave={leave.guardLeave}
      />

      <EditorNotices
        hidden={doc.loading || doc.missing}
        conflict={save.conflict}
        duplicate={save.duplicate}
        errorBanner={save.errorBanner}
        errorTitle={save.errorTitle}
        conflictBanner={banners.conflictBanner}
        duplicateBanner={banners.duplicateBanner}
        errorElement={banners.errorElement}
        reload={save.reload}
        overwrite={save.overwrite}
        dismissError={() => save.setErrorBanner(null)}
        guardLeave={leave.guardLeave}
      />


      <div ref={banners.workspace} className="editor-workspace" id="edWorkspace" style={{ display: (doc.loading || doc.missing) ? 'none' : undefined }}>
        <EditorFileTree
          rows={doc.rows}
          fileCount={doc.files.length}
          treeCollapsed={doc.treeCollapsed}
          setTreeCollapsed={doc.setTreeCollapsed}
          current={doc.current}
          query={doc.query}
          setQuery={doc.setQuery}
          onSearchKeydown={tree.onSearchKeydown}
          clearSearch={tree.clearSearch}
          create={tree.create}
          setCreate={tree.setCreate}
          createItem={tree.createItem}
          cancelCreate={tree.cancelCreate}
          startCreate={tree.startCreate}
          rename={tree.rename}
          setRename={tree.setRename}
          commitRename={tree.commitRename}
          cancelRename={tree.cancelRename}
          beginRename={tree.beginRename}
          menu={tree.menu}
          toggleMenu={tree.toggleMenu}
          askDelete={tree.askDelete}
          askDeleteFolder={tree.askDeleteFolder}
          buffers={doc.buffers}
          saved={doc.saved}
          openFile={tree.openFile}
          toggleFolder={tree.toggleFolder}
          uploads={save.uploads}
          uploadFiles={tree.uploadFiles}
        />

        <EditorPane
          current={doc.current}
          currentDirty={doc.currentDirty}
          currentBinary={doc.currentBinary}
          currentText={doc.currentText}
          gutterText={doc.gutterText}
          infoText={infoText(doc.currentBinary, doc.currentFile, doc.currentText)}
          caret={caret.caret}
          fileHref={fileHrefOf(namespace, name, readSuffix, doc.current)}
          preview={doc.currentBinary ? previewKindOf(doc.current) : null}
          gutter={caret.gutter}
          textarea={caret.textarea}
          onTextInput={caret.onTextInput}
          onTextScroll={caret.onTextScroll}
          onTextKeydown={caret.onTextKeydown}
          onCaret={caret.onCaret}
        />
      </div>

      <div className="editor-loading" id="edLoading" style={{ display: (!doc.loading) ? 'none' : undefined }}>
        加载中…
      </div>
      <div className="editor-missing" id="edMissing" style={{ display: (!doc.missing) ? 'none' : undefined }}>
        <div className="notfound">
          <h2>没有这一版</h2>
          <p className="hint">它可能已经被丢弃，或者不属于你能编辑的版本。</p>
          <p>
            <a href={versionsHref}>回到版本列表</a>
          </p>
        </div>
      </div>

      <ConfirmDialog
        open={tree.deleteTarget !== null}
        title="删除文件？"
        body={`${tree.deleteTarget ?? ''} 将从版本中移除。${tree.deleteTarget !== null && doc.buffers[tree.deleteTarget] !== doc.saved[tree.deleteTarget] ? ' 该文件未保存的改动也会丢失。' : ''}`}
        confirmLabel="删除文件"
        onConfirm={tree.confirmDelete}
        onCancel={tree.cancelDelete}
      />

      <ConfirmDialog
        open={tree.deleteFolderTarget !== null}
        title="删除文件夹？"
        body={`${tree.deleteFolderTarget ?? ''} 是空文件夹，删除不会改变版本内容。`}
        confirmLabel="删除文件夹"
        danger={false}
        onConfirm={tree.confirmDeleteFolder}
        onCancel={tree.cancelDeleteFolder}
      />

      <ConfirmDialog
        open={leave.leaveTarget !== null}
        title={`有 ${doc.dirtyPaths.length} 个文件未保存`}
        body="离开此页将丢失这些改动。"
        confirmLabel="放弃并离开"
        cancelLabel="继续编辑"
        onConfirm={leave.leave}
        onCancel={leave.cancelLeave}
      />

      <svg style={{ position: 'absolute', width: 0, height: 0, overflow: 'hidden' }} aria-hidden="true">
        <symbol id="fileMark" viewBox="0 0 24 24" fill="none">
          <path
            d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8z"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinejoin="round"
          />
          <path d="M14 3v5h5" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" />
        </symbol>
      </svg>
    </section>
  )
}
