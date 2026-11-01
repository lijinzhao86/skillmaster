// The narrow delete on the author plane (v37, ADR 0127): the one write that takes something away,
// and only from a skill that has never gone live.
package api

import (
	"context"
	"net/http"
)

// DeleteNeverPublishedSkill soft-deletes a skill that has never had a version go live (v37, ADR
// 0127). The server owns the condition — this call is the whole of the CLI's delete, and a refusal
// comes back as `skill_published`, `forbidden` or `skill_not_found`.
func (c Client) DeleteNeverPublishedSkill(ctx context.Context, address string) error {
	path, err := authorPath(address)
	if err != nil {
		return err
	}
	_, err = c.authorRequest(ctx, http.MethodPost, path+"/delete", nil)
	return err
}
