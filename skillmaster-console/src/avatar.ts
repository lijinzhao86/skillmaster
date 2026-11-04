/** The one-letter avatar used by every collaborator-shaped list. */
export function avatarInitial(person: string): string {
  return person.slice(0, 1)
}
