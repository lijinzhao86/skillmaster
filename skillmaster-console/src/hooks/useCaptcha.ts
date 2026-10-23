import { useCallback, useState } from 'react'
import { getCaptcha } from '../api/account'
import { codeMessage } from '../api/errors'
import type { Captcha } from '../api/types'

/**
 * One captcha: its id, its image, and a way to get another.
 *
 * The refresh rules matter more than they look, because every challenge is single use. A new one is
 * needed after a successful code request, after any refusal naming the captcha field, and after a
 * throttled one — miss any of those and a person retypes a picture the server has already seen, or
 * has stopped accepting.
 */
export function useCaptcha() {
  const [id, setId] = useState('')
  const [image, setImage] = useState('')
  const [error, setError] = useState<string | null>(null)

  const refresh = useCallback(async (): Promise<void> => {
    const result = await getCaptcha()
    // The value is checked, not just `ok`, because a success with no data is a shape the client can
    // hand back — a 204, which this endpoint never sends but a proxy between the two might. Reading
    // a field off `undefined` inside a discarded promise leaves an empty box and no message at all.
    // `!= null` rather than `!== undefined`: a 200 whose body is the JSON literal `null` produces
    // `null`, which `!== undefined` lets through to a field read on nothing.
    const issued: Captcha | null | undefined = result.ok ? result.data : undefined
    if (issued != null) {
      setId(issued.captcha_id)
      // The server sends raw base64; the `data:` prefix is what makes it an image in a browser.
      setImage(`data:image/png;base64,${issued.image}`)
      setError(null)
      return
    }

    // Cleared rather than left showing the previous challenge: an id whose image is gone would let
    // somebody answer something they can no longer see.
    setId('')
    setImage('')
    setError(
      result.ok
        ? '服务返回了无法识别的内容，请稍后重试。'
        : codeMessage(result.code, result.message),
    )
  }, [])

  return { id, image, error, refresh }
}
