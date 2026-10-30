import { describe, expect, it } from 'vitest'
import { collisionIssue, issueSentence, normalizePath, pathIssue } from '../src/nameRules'

/**
 * v33's name rules, without a page around them (test-plan W1–W4's rules half).
 *
 * The sentences are asserted whole: they are the version's copy (§措辞), and one changed character
 * is a different promise.
 */
describe('nameRules', () => {
  it('names the character Windows refuses', () => {
    expect(pathIssue('报告:v2.md')).toBe('冒号不能作为文件名（Windows）。')
    expect(pathIssue('notes/plan?.md')).toBe('问号不能作为文件名（Windows）。')
    expect(pathIssue('notes/a*b.md')).toBe('星号不能作为文件名（Windows）。')
  })

  it('knows the device names, with or without an extension', () => {
    expect(pathIssue('CON.md')).toBe('CON 是 Windows 保留的设备名。')
    // The device name is upper case in the sentence even when it was typed lower case: that is the
    // spelling Windows documents it under, and the name to look up.
    expect(pathIssue('notes/con.txt')).toBe('CON 是 Windows 保留的设备名。')
    expect(pathIssue('LPT1')).toBe('LPT1 是 Windows 保留的设备名。')
    expect(pathIssue('com9.md')).toBe('COM9 是 Windows 保留的设备名。')
  })

  it('refuses an ending Windows would trim', () => {
    expect(pathIssue('notes.md ')).toBe('结尾有一个空格（或点），Windows 会去掉它。')
    expect(pathIssue('a.b.')).toBe('结尾有一个空格（或点），Windows 会去掉它。')
  })

  it('leaves ordinary names alone', () => {
    expect(pathIssue('SKILL.md')).toBeNull()
    expect(pathIssue('references/split.md')).toBeNull()
    expect(pathIssue('notes .md')).toBeNull()
  })

  it('compares names the way macOS and Windows do', () => {
    expect(collisionIssue('references/SPLIT.md', ['references/split.md'])).toBe(
      '与已有的 references/split.md 在 macOS 和 Windows 上会被当成同一个文件。',
    )
    const nfd = 'café.md'.normalize('NFD')
    expect(collisionIssue('café.md', [nfd])).toBe(
      `与已有的 ${nfd} 只有编码写法不同，macOS 会把它们当成同一个文件。`,
    )
    expect(collisionIssue('notes/new.md', ['notes/other.md'])).toBeNull()
    // A rename does not collide with the row it is moving away from.
    expect(collisionIssue('notes/Readme.md', ['notes/Readme.md'], 'notes/Readme.md')).toBeNull()
  })

  it('normalizes every segment', () => {
    const nfd = 'café/notes.md'.normalize('NFD')
    expect(normalizePath(nfd)).toBe('café/notes.md')
  })

  it('says the server’s issues in the shared words', () => {
    expect(issueSentence('windows_reserved_name', 'CON.md')).toBe(
      'CON 是 Windows 保留的设备名。',
    )
    expect(issueSentence('case_collision', 'A.md')).toBe(
      '两个名字在 macOS 和 Windows 上会被当成同一个文件。',
    )
    expect(issueSentence('invalid_zip_name', '')).toBe(
      '有一个文件名解不出来（压缩包不是 UTF-8 编码）。',
    )
    expect(issueSentence('empty_name', 'x')).toBeNull()
  })
})
