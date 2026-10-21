/** Deliberately not signed in: every session this account had has just been revoked, and signing
 *  in with the new password is what proves to the person that it is the one that works. */

export interface ResetDoneViewProps {
  active: boolean
  onLogin: () => void
}

export default function ResetDoneView({ active, onLogin }: ResetDoneViewProps) {
  return (
    <div className="dialog-view" id="resetDoneView" hidden={!active}>
      <p>请用新密码重新登录。出于安全考虑，这个账号在所有设备上的登录都已经失效。</p>
      <p className="success-link">
        <a
          className="link"
          href="#"
          id="resetDoneToLogin"
          onClick={(event) => {
            event.preventDefault()
            onLogin()
          }}
        >
          去登录
        </a>
      </p>
    </div>
  )
}
