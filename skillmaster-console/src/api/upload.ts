import { CSRF_HEADER, csrfToken, failureOf } from './client'
import { encodeRelpath, skillPath, versionPath } from './skills'
import type { ApiResult, DraftResponse } from './types'

/** One binary file into an existing draft (v39), with browser upload progress. */
export function uploadDraftFile(
  namespace: string,
  name: string,
  version: string,
  relpath: string,
  file: File,
  baseDigest: string,
  onProgress: (fraction: number) => void,
): Promise<ApiResult<DraftResponse>> {
  const path = `${skillPath(namespace, name)}/versions/${versionPath(version)}/files/${encodeRelpath(relpath)}`
  return sendXhr<DraftResponse>('PUT', path, file, { 'If-Match': baseDigest }, onProgress)
}

/** Creates a draft and writes its first file in one multipart request (v39). */
export function createDraftWithFile(
  namespace: string,
  name: string,
  body: {
    name: string
    note: string
    from?: string
    from_digest?: string
    path: string
    file: File
  },
  onProgress: (fraction: number) => void,
): Promise<ApiResult<DraftResponse>> {
  const form = new FormData()
  form.append('name', body.name)
  if (body.note !== '') form.append('note', body.note)
  if (body.from !== undefined) form.append('from', body.from)
  if (body.from_digest !== undefined) form.append('from_digest', body.from_digest)
  form.append('path', body.path)
  form.append('file', body.file)
  return sendXhr<DraftResponse>('POST', `${skillPath(namespace, name)}/versions`, form, {},
    onProgress)
}

/**
 * `XMLHttpRequest` rather than `fetch`: a 100 MiB upload needs an upload-progress event, and fetch
 * does not expose one. It uses the same CSRF header and the same error decoding as `request`, and a
 * generous budget: a 100 MiB file on a slow but working link must not be cut off.
 */
function sendXhr<T>(
  method: 'POST' | 'PUT',
  path: string,
  body: Document | XMLHttpRequestBodyInit,
  headers: Record<string, string>,
  onProgress: (fraction: number) => void,
): Promise<ApiResult<T>> {
  return new Promise((resolve) => {
    const xhr = new XMLHttpRequest()
    xhr.open(method, path)
    xhr.withCredentials = true
    xhr.timeout = 10 * 60 * 1000
    const token = csrfToken()
    if (token !== null) xhr.setRequestHeader(CSRF_HEADER, token)
    for (const [key, value] of Object.entries(headers)) xhr.setRequestHeader(key, value)
    xhr.upload.onprogress = (event) => {
      if (event.lengthComputable && event.total > 0) onProgress(event.loaded / event.total)
    }
    xhr.onload = () => {
      const text = xhr.responseText
      if (xhr.status >= 200 && xhr.status < 300) {
        try {
          resolve({ ok: true, status: xhr.status, data: JSON.parse(text) as T })
        } catch {
          resolve({
            ok: false,
            status: xhr.status,
            code: 'internal_error',
            message: '服务返回了无法识别的内容，请稍后重试。',
            details: [],
          })
        }
        return
      }
      resolve(failureOf(xhr.status, text, xhr.getResponseHeader('Retry-After'), path))
    }
    xhr.onerror = () => resolve({
      ok: false,
      status: 0,
      code: 'network_error',
      message: '网络请求失败，请检查网络后重试。',
      details: [],
    })
    xhr.ontimeout = () => resolve({
      ok: false,
      status: 0,
      code: 'timeout',
      // One sentence for every timeout in the app; an upload is not a different kind of silence.
      message: '服务响应太慢，请稍后重试。',
      details: [],
    })
    xhr.send(body)
  })
}

