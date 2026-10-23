import { query } from '../address'
import { csrfToken } from '../api/client'

/**
 * The page a person sees once, when `skillmaster login` hands their browser over.
 *
 * **It submits a plain HTML form, not `fetch`.** The answer to this POST is a 302 to the CLI's
 * loopback port with the authorization code; `fetch` would follow that redirect itself, and the
 * navigation has to be the browser's. `method="post"` with `action` on the framework's own endpoint
 * is what does it.
 *
 * **Two forms, not one with a disabled button.** Declining is the same submission with nothing
 * agreed to — the server reads an empty scope set as `access_denied` — so the second form simply
 * carries no `scope` inputs. Doing it with JavaScript would mean the refusal depended on script
 * having run.
 *
 * Nothing here is a parameter this page invents: `client_id`, `state` and `scope` all came from the
 * redirect the framework sent, and it is the framework that checks them again.
 */
const SCOPE_MEANINGS: Record<string, string> = {
  'skills:read': '读取你能看到的 skill — 包括你自己命名空间里未公开的那些',
  'skills:write': '以你的名义提交新版本、管理共享',
}

/**
 * Who is asking.
 *
 * v1 has exactly one client, seeded at deployment, and M2 deliberately has no read seam to ask for
 * its display name (see the module doc). A name typed here is therefore a name that can rot — so an
 * unrecognised `client_id` is shown as itself with a warning rather than as something friendly.
 */
const CLIENT_NAMES: Record<string, string> = {
  'skillmaster-cli': 'SkillMaster CLI',
}

export default function ConsentPage() {
  const params = query()
  const clientId = params.get('client_id')
  const state = params.get('state')

  /** The redirect puts every scope in one space-separated parameter, the submission wants them apart. */
  const scopes = params
    .getAll('scope')
    .flatMap((value) => value.split(/\s+/))
    .filter((value) => value !== '')

  const clientName = clientId === null ? null : (CLIENT_NAMES[clientId] ?? null)

  /** The redirect is incomplete if either of these is missing, and posting anyway would be refused. */
  const incomplete = clientId === null || state === null || clientId === ''

  return (
    <div className="card">
      <h2>授权请求</h2>

      {/* The framework only builds this redirect; a person can also arrive here by editing the
          address or following an old link. Saying so is better than a form that cannot work. */}
      {incomplete ? (
        <p className="muted">
          这个链接不完整，缺少 <code>client_id</code> 或 <code>state</code>，无法完成授权。 请回到终端重新运行{' '}
          <code>skillmaster login</code>。
        </p>
      ) : (
        <>
          <p>
            <strong>{clientName ?? clientId}</strong>
            {clientName === null ? (
              <span className="muted">（这个客户端不在已知名单里，请确认它确实是你安装的）</span>
            ) : null}
            请求以你的名义：
          </p>

          <ul>
            {scopes.map((scope) => (
              <li key={scope}>
                {SCOPE_MEANINGS[scope] !== undefined ? (
                  SCOPE_MEANINGS[scope]
                ) : (
                  <>
                    <code>{scope}</code>
                    <span className="muted">（我们不知道这一项的含义，请谨慎同意）</span>
                  </>
                )}
              </li>
            ))}
          </ul>

          {/* The boundary gets its own paragraph rather than riding along in the item above: what a
              tool **cannot** do is the part of a consent screen people get wrong, and a clause at the
              end of a list item reads as a qualification of it. */}
          <p className="muted">它不能上线——把版本对外公开，是你在网页上确认的动作。</p>

          {/* **Not `logout`.** Since v2 that deletes only the local copy and revokes nothing on the
              server (ADR 0038 §决定 7) — so the sentence this page carried promised the one thing
              somebody giving up a machine most needs to be true. */}
          <p className="muted">
            同意之后，这个工具会拿到一份长期凭据，存在你的机器上；此后它替你做的事都不再询问。
            要结束它，请重置密码——这会一并撤销这台机器上的授权。
          </p>

          {/* `_csrf` is rendered because this is a browser write and the field is what such a form owes
              the server. It is currently not checked — the framework exempts its own endpoints from
              CSRF protection — so this is here for the day that changes. */}
          <form method="post" action="/oauth/authorize">
            <input type="hidden" name="client_id" value={clientId ?? ''} />
            <input type="hidden" name="state" value={state ?? ''} />
            <input type="hidden" name="_csrf" value={csrfToken() ?? ''} />
            {scopes.map((scope) => (
              <input key={scope} type="hidden" name="scope" value={scope} />
            ))}
            <button type="submit">同意并继续</button>
          </form>

          <form method="post" action="/oauth/authorize">
            <input type="hidden" name="client_id" value={clientId ?? ''} />
            <input type="hidden" name="state" value={state ?? ''} />
            <input type="hidden" name="_csrf" value={csrfToken() ?? ''} />
            <button type="submit" className="link">
              拒绝
            </button>
          </form>
        </>
      )}
    </div>
  )
}
