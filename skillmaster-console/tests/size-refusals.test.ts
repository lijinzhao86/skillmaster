import { describe, expect, it } from 'vitest'
import { codeMessage, importFailure } from '../src/api/errors'
import type { ErrorDetail } from '../src/api/types'

const MiB = 1024 * 1024

/**
 * The four sentences of voice-spec §上传与大小的四句话, from the numbers the server sends.
 *
 * The point of the test is the numbers: the client has no copy of 100 / 512 / 128 to fall out of
 * step with, so a sentence has to carry whatever `details` said.
 */
describe('size refusals', () => {
  function detail(
    issue: string,
    actualBytes?: number,
    limitBytes?: number,
    field = 'file',
  ): ErrorDetail {
    // The wire names, exactly as the server sends them (snake_case, set globally).
    return { field, issue, actual_bytes: actualBytes, limit_bytes: limitBytes }
  }

  it('names the file and both numbers when the server knew the path', () => {
    expect(codeMessage('invalid_upload', 'nope', [
      detail('file_too_large', 120 * MiB, 100 * MiB, 'assets/scan.pdf'),
    ])).toBe('文件 assets/scan.pdf 有 120 MiB，超过单文件上限 100 MiB。')
  })

  it('says "this file" when the route never saw a path', () => {
    expect(codeMessage('invalid_upload', 'nope', [
      detail('file_too_large', 120 * MiB, 100 * MiB),
    ])).toBe('这个文件有 120 MiB，超过单文件上限 100 MiB。')
  })

  it('states the written total when the version is over 512 MiB', () => {
    expect(codeMessage('invalid_upload', 'nope', [
      detail('package_too_large', 530 * MiB, 512 * MiB),
    ])).toBe('这一版有 530 MiB，超过 512 MiB 的上限。')
  })

  it('chooses by the limit, not the command, when the import ceiling is the one named', () => {
    // The same issue code serves both ceilings; only `limitBytes` tells them apart (TD §错误形状).
    expect(codeMessage('invalid_upload', 'nope', [
      detail('package_too_large', 300 * MiB, 128 * MiB),
    ])).toBe('整包导入最多 128 MiB；更大的包请逐个文件上传。')
  })

  it('counts files from the limit the server sent', () => {
    expect(codeMessage('invalid_upload', 'nope', [detail('too_many_files', 600, 512)]))
      .toBe('这一版最多 512 个文件。')
  })

  it('falls back to the code when the refusal carried no numbers', () => {
    expect(codeMessage('invalid_upload', 'the file is too large', [detail('file_too_large')]))
      .toBe('这份内容超出了这一版允许的大小，或 frontmatter 不合法。')
  })

  it('reaches the import sheet, which used to show the server English', () => {
    const failure = importFailure(
      {
        code: 'invalid_upload',
        message: 'the package expands to 300 MiB, above the 128 MiB import ceiling',
        details: [detail('package_too_large', 300 * MiB, 128 * MiB)],
      },
      'import',
    )
    expect(failure.message).toBe('整包导入最多 128 MiB；更大的包请逐个文件上传。')
  })
})
