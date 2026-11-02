import { describe, expect, it } from 'vitest'
import { uploadRefusal } from '../src/pages/version-editor/editorModel'
import type { AuthoredFile } from '../src/api/types'

function file(relpath: string, size: number): AuthoredFile {
  return { relpath, sha256: 'sha256:' + '0'.repeat(64), size, is_binary: true }
}

describe('uploadRefusal', () => {
  it('refuses one file above 100 MiB with both numbers', () => {
    expect(uploadRefusal([{ name: 'big.bin', size: 101 * 1024 * 1024 }], []))
      .toBe('文件 big.bin 有 101 MiB，超过单文件上限 100 MiB。')
  })

  it('refuses a batch that would pass 512 MiB even when every file is legal', () => {
    expect(uploadRefusal([{ name: 'a.bin', size: 100 * 1024 * 1024 }],
      [file('references/b.bin', 450 * 1024 * 1024)]))
      .toBe('这一版加上这次上传会超过 512 MiB 的上限。')
  })

  it('refuses more than 512 files', () => {
    const existing = Array.from({ length: 512 }, (_, index) => file(`f${index}.md`, 1))
    expect(uploadRefusal([{ name: 'new.md', size: 1 }], existing))
      .toBe('这一版最多 512 个文件。')
  })

  it('lets a fitting batch through', () => {
    expect(uploadRefusal([{ name: 'a.png', size: 2 * 1024 * 1024 }],
      [file('references/b.pdf', 10 * 1024 * 1024)])).toBeNull()
  })
})
