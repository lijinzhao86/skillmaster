package api

import (
	"context"
	"encoding/json"
	"fmt"
)

// Me is the API plane's answer to "who am I, and what am I holding".
//
// It is the twin of the browser plane's `/web/session`, and the CLI wanted it for three things that
// are really one: a paste should be verified before it is stored, `auth status` has to say which
// credential is in use, and a 401 that says "this key expired" is much less useful without a way to
// ask "which key?".
type Me struct {
	User struct {
		UserID    string `json:"user_id"`
		Username  string `json:"username"`
		Namespace string `json:"namespace"`
	} `json:"user"`

	// Key is null when this credential is not an API key — an OAuth token. Null is an answer rather
	// than a gap, and the CLI says so instead of printing a name it made up.
	Key *struct {
		Name       string `json:"name"`
		Scope      string `json:"scope"`
		ExpiresAt  string `json:"expires_at"`
		LastUsedAt string `json:"last_used_at"`
	} `json:"key"`
}

// Me asks the server what this credential is.
//
// **It needs authentication and no scope**, deliberately: the moment this matters most is the moment
// something is wrong with the credential, and requiring a permission to ask about oneself would make
// exactly then the time the question cannot be asked.
func (c Client) Me(ctx context.Context) (Me, error) {
	body, err := c.get(ctx, APIPrefix+"/me")
	if err != nil {
		return Me{}, err
	}
	var me Me
	if err := json.Unmarshal(body, &me); err != nil {
		return Me{}, fmt.Errorf("读取 %s/me 的响应：%w", APIPrefix, err)
	}
	return me, nil
}
