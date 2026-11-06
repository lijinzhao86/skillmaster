import { useEffect, useRef, useState } from 'react'
import { failureMessage } from '../api/errors'
import { requestAccess, withdrawAccessRequest } from '../api/skills'
import type { AuthoredSkill, Rung, Standing } from '../api/types'
import { rungsAbove, RUNG_NOTES } from '../rungs'

const STANDING: Partial<Record<string, string>> = {
  invoke: '当前档位为「调用」：可以调用，但无法查看文件清单。',
  browse: '当前档位为「浏览」：可以读取，但无法提交版本。',
  write: '当前档位为「写入」：可以提交版本，但无法上线。',
  maintain: '当前档位为「维护」：可以上线，但无法修改可见性与协作者。',
}

/**
 * The 申请 block on the information page: the rung above this one, the sentence that says what it
 * adds, and the one write that asks for it.
 *
 * Its own hook because it is a small state machine (a picker whose options change with the rung,
 * a pending request that survives a reload) and because the page already carries the rest.
 */
export interface SkillAskProps {
  namespace: string
  name: string
  skill: AuthoredSkill | null
  rung: Standing
  /** Whether the skill is in the caller's library — the relationship an ask needs (ADR 0150). */
  inLibrary: boolean
  setBanner: (message: string | null) => void
}

export function useSkillAsk({ namespace, name, skill, rung, inLibrary, setBanner }: SkillAskProps) {
  const above = rungsAbove(rung)
  const showAsk = skill !== null && inLibrary && rung !== 'owner' && above.length > 0

  const askWhy = STANDING[rung] ?? ''
  const pendingRung = skill?.pending_rung ?? null
  const [askedRung, setAskedRung] = useState<Rung>(above[0]?.name ?? 'browse')
  const aboveKey = above.map((option) => option.name).join(',')
  const aboveSettled = useRef(false)
  useEffect(() => {
    if (!aboveSettled.current) {
      aboveSettled.current = true
      return
    }
    if (!above.some((option) => option.name === askedRung)) {
      setAskedRung(above[0]?.name ?? 'browse')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [aboveKey])
  const askNote = RUNG_NOTES[askedRung] ?? ''
  // The block only appears for a skill already in the library, so "become a collaborator" is no
  // longer the right name for it: everyone who sees this is asking for one rung more.
  const askLabel = '申请'

  async function submitAsk(): Promise<void> {
    setBanner(null)
    const result = await requestAccess(namespace, name, askedRung)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    window.location.reload()
  }

  async function withdrawAsk(): Promise<void> {
    setBanner(null)
    const result = await withdrawAccessRequest(namespace, name)
    if (!result.ok) {
      setBanner(failureMessage(result))
      return
    }
    window.location.reload()
  }

  return {
    above,
    showAsk,
    askWhy,
    pendingRung,
    askedRung,
    setAskedRung,
    askNote,
    askLabel,
    submitAsk,
    withdrawAsk,
  }
}
