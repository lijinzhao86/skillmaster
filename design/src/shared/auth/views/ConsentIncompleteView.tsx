/** A link that cannot work, said out loud rather than as a form that silently does nothing. A
 *  person can arrive here by editing the address or following something old. */

export interface ConsentIncompleteViewProps {
  active: boolean
}

export default function ConsentIncompleteView({ active }: ConsentIncompleteViewProps) {
  return (
    <div className="dialog-view" id="consentIncompleteView" hidden={!active}>
      <p>这个链接不完整，缺少 <code>client_id</code>、<code>state</code> 或 <code>code_challenge</code>，无法完成授权。请回到终端重新运行 <code>skmr login</code>。</p>
    </div>
  )
}
