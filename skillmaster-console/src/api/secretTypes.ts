/**
 * The service-credential screen's wire shapes (v52).
 *
 * **No shape here has a `value`**, and that is not an accident of this file: the server has no
 * endpoint that returns one. The list says what a credential is called, where it is bound and how
 * much of today's quota it has used; the value itself only ever leaves the platform through the
 * lease, which a skill's process asks for.
 */

/** One binding: this credential, on this skill, into this environment variable. */
export interface SecretBinding {
  skill: string
  env: string
}

export interface Secret {
  id: string
  name: string
  per_day: number
  used: number
  created_at: string
  rotated_at: string | null
  last_used_at: string | null
  bindings: SecretBinding[]
}

export interface SecretList {
  items: Secret[]
}

/** What a create answers with: the new row, already bound to its first skill. */
export interface CreatedSecret {
  id: string
  name: string
  per_day: number
  used: number
  created_at: string
  bindings: SecretBinding[]
}

/** One live grant: this person receives the credential on this skill, through this variable. */
export interface SecretShare {
  user: string
  skill: string
  env: string
  created_at: string
}

export interface SecretShares {
  items: SecretShare[]
}

/**
 * One variable this person receives from somebody else's credential.
 *
 * `owner` is a handle, resolved by the server: the row has to say *who* lent it, because that is
 * what decides whether the person trusts it. It carries no value, like everything else here.
 */
export interface IncomingSecret {
  credential_id: string
  name: string
  owner: string
  skill: string
  env: string
  /** The owner's daily ceiling and today's count across everybody it serves — the pane draws the
   *  same five columns for every row. */
  per_day: number
  used: number
}
