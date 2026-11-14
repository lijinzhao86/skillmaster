/** A link that is no longer a question: it was answered, it was cancelled, or it simply ran out.
 *  There is nothing to approve and no button to press — the only way on is back at the machine that
 *  asked, where a new link costs one command. */

export interface ConsentExpiredViewProps {
  active: boolean
}

export default function ConsentExpiredView({ active }: ConsentExpiredViewProps) {
  return (
    <div className="dialog-view" id="consentExpiredView" hidden={!active}>
      <p>这条链接已经用过或过期了，不能再批准。请回到那台设备，重新运行 <code>skmr login</code>。</p>
    </div>
  )
}
