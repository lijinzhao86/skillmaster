import { describe, expect, it } from 'vitest'
import { codeIssue, isBlank, passwordIssue, phoneIssue, usernameIssue } from '../src/validation'

const USERNAME = 'demo-user'
const PHONE = '13800138000'

describe('the password rule', () => {
  it('measures in bytes that for accepted passwords are also characters', () => {
    // These two cases used to be 24 and 25 Chinese characters: 72 bytes fit, 75 did not, and a
    // character count could not tell them apart. That is no longer reachable — Chinese is refused
    // outright now, so it never reaches the length rule, and `refuses Chinese and full-width
    // characters` below is what pins that. The byte semantics are unchanged and still live where
    // they can be observed, at the ceiling this file tests either side of.
    expect(passwordIssue('a'.repeat(72), USERNAME, PHONE)).toBeNull()
    expect(passwordIssue('a'.repeat(73), USERNAME, PHONE)).toBe('too_long')
  })

  it('refuses fewer than 8 bytes', () => {
    expect(passwordIssue('abc1234', USERNAME, PHONE)).toBe('too_short')
    expect(passwordIssue('abcd1234', USERNAME, PHONE)).toBeNull()
  })

  it('refuses one byte over the ceiling, not only far over it', () => {
    // The off-by-one case: a rule written with `>=` on one end or `>` on the other passes every
    // test around this line and then refuses somebody at the server with nothing on screen.
    expect(passwordIssue('a'.repeat(72), USERNAME, PHONE)).toBeNull()
    expect(passwordIssue('a'.repeat(73), USERNAME, PHONE)).toBe('too_long')
  })

  it('accepts the characters on a keyboard, spaces included', () => {
    // A passphrase of words is the shape this policy encourages, and the space between the words is
    // the character that makes it one.
    expect(passwordIssue('correct horse battery', USERNAME, PHONE)).toBeNull()
    expect(passwordIssue("a!@#$%^&*()_+-=[]{};':\",./<>?", USERNAME, PHONE)).toBeNull()
  })

  it('refuses Chinese and full-width characters', () => {
    // The rule the form states outright (ADR 0017). Full-width is the case that matters: it looks
    // exactly like the half-width password it is not, it walked past the blocklist, and it is what a
    // Chinese input method produces when it is left in full-width mode.
    expect(passwordIssue('密码密码密码', USERNAME, PHONE)).toBe('invalid_format')
    expect(passwordIssue('ｐａｓｓｗｏｒｄ', USERNAME, PHONE)).toBe('invalid_format')
    expect(passwordIssue('🎵🎶 password', USERNAME, PHONE)).toBe('invalid_format')
    // Invisible but rejected all the same: a tab and a newline are not characters anyone can count.
    expect(passwordIssue('tab\there!', USERNAME, PHONE)).toBe('invalid_format')
  })

  it('answers the character set before the length', () => {
    // Told "too short" about a Chinese password, its author types more Chinese.
    expect(passwordIssue('密码', USERNAME, PHONE)).toBe('invalid_format')
  })

  it('treats a whitespace-only password as nothing typed', () => {
    // Eight spaces, and eight full-width spaces, which is what a full-width input method leaves
    // behind. `=== ''` would have let both through.
    expect(passwordIssue('        ', USERNAME, PHONE)).toBe('required')
    expect(passwordIssue('　　　　', USERNAME, PHONE)).toBe('required')
  })

  it('refuses the username and the phone number themselves', () => {
    expect(passwordIssue(USERNAME, USERNAME, PHONE)).toBe('same_as_username')
    expect(passwordIssue(PHONE, USERNAME, PHONE)).toBe('same_as_phone')
  })

  it('refuses the handle with something stuck on the end', () => {
    // Equality alone misses these, and this is what a hurried person types. Punctuation in the
    // middle changes nothing about what it is.
    expect(passwordIssue('demo-user123', USERNAME, PHONE)).toBe('too_common')
    expect(passwordIssue('demouser1', USERNAME, PHONE)).toBe('too_common')
    expect(passwordIssue('demo.user.2026', USERNAME, PHONE)).toBe('too_common')
  })

  it('refuses the phone number wherever it sits', () => {
    expect(passwordIssue('a13800138000', USERNAME, PHONE)).toBe('too_common')
    expect(passwordIssue('13800138000a', USERNAME, PHONE)).toBe('too_common')
  })

  it('keeps a long passphrase that merely holds the handle', () => {
    // The boundary, and the reason the rule strips a trailing run of digits rather than testing
    // containment: these two are good passwords, and refusing them is worse than the gap.
    expect(passwordIssue('demo-user-and-then-some', USERNAME, PHONE)).toBeNull()
    expect(passwordIssue('my-demo-user-passphrase', USERNAME, PHONE)).toBeNull()
  })

  it('does not look for a handle when there is none to look for', () => {
    // The reset page has no username, and passes an empty one.
    expect(passwordIssue('a-different-long-password', '', PHONE)).toBeNull()
  })

  it('asks for something when nothing was typed', () => {
    expect(passwordIssue('', USERNAME, PHONE)).toBe('required')
  })
})

describe('the username rule', () => {
  it('answers "wrong alphabet" before "too short", so the fix is the one that helps', () => {
    // Told "too short", the author of a two-character Chinese name types more of the same and is
    // refused again for the same reason.
    expect(usernameIssue('飞书')).toBe('invalid_format')
    expect(usernameIssue('AB')).toBe('invalid_format')
  })

  it('accepts the shapes an address can be built from', () => {
    // The last three are the server's own cases, kept here so the two rules are pinned to the same
    // answers rather than to the same intention: digits only, a doubled hyphen and a trailing
    // hyphen are all legal, and a mirror that quietly tightened any of them would refuse a name
    // the server would have taken.
    expect(usernameIssue('demo')).toBeNull()
    expect(usernameIssue('a1b')).toBeNull()
    expect(usernameIssue('ab-')).toBeNull()
    expect(usernameIssue('123')).toBeNull()
    expect(usernameIssue('a--b')).toBeNull()
    expect(usernameIssue('a'.repeat(29) + '-')).toBeNull()
  })

  it('refuses a leading hyphen, and the lengths outside the range', () => {
    expect(usernameIssue('-abc')).toBe('invalid_format')
    expect(usernameIssue('ab')).toBe('invalid_length')
    expect(usernameIssue('a'.repeat(31))).toBe('invalid_length')
    expect(usernameIssue('a'.repeat(30))).toBeNull()
  })

  it('asks for something when nothing was typed', () => {
    expect(usernameIssue('')).toBe('required')
    expect(usernameIssue('   ')).toBe('required')
  })
})

describe('the phone rule', () => {
  it('accepts the eleven digits a mainland number has', () => {
    expect(phoneIssue('13800138000')).toBeNull()
    expect(phoneIssue('19912345678')).toBeNull()
  })

  it('refuses a number that could not be one', () => {
    expect(phoneIssue('12800138000')).toBe('invalid_format')
    expect(phoneIssue('1380013800')).toBe('invalid_format')
    expect(phoneIssue('1380013800a')).toBe('invalid_format')
  })

  it('asks for something when nothing was typed', () => {
    expect(phoneIssue('')).toBe('required')
    expect(phoneIssue('  ')).toBe('required')
  })
})

describe('the sms code rule', () => {
  it('checks only that something was typed — the server owns the rest', () => {
    expect(codeIssue('')).toBe('required')
    expect(codeIssue(' ')).toBe('required')
    expect(codeIssue('12')).toBeNull()
  })
})

describe('what counts as nothing typed', () => {
  // Unicode's White_Space, which is the set both halves of the form use. Not `trim()` — the two
  // differ in opposite directions on the two cases below.
  it('treats every white-space character as blank', () => {
    for (const character of [' ', '\t', '\n', ' ', '　', ' ', ' ', '\u0085']) {
      expect(isBlank(character.repeat(8))).toBe(true)
    }
  })

  it('does not call a byte order mark blank, even though trim() does', () => {
    // The reason this file states the set instead of calling `trim()`.
    expect('﻿'.trim()).toBe('')
    expect(isBlank('﻿')).toBe(false)
  })

  it('calls a next-line character blank, even though trim() does not', () => {
    expect('\u0085'.trim()).not.toBe('')
    expect(isBlank('\u0085'.repeat(8))).toBe(true)
  })

  it('is not blank as soon as there is something in it', () => {
    expect(isBlank(' a ')).toBe(false)
  })
})
