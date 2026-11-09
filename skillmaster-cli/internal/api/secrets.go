// Service credentials (v52): the vault, the bindings, and the one call that returns a value.
//
// **This client never sees a value except through `LeaseCredentials`.** That is not a limitation of
// this file — the server has no endpoint that reads one back, deliberately (ADR 0168). A `set` sends
// a value and gets no echo; a `list` gets names and counts. The single place a value comes down the
// wire is the lease, and the only caller is `skmr run`.

package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
)

// Secret is one credential as the list endpoint describes it: what it is called and where it is
// used. There is no value field, and adding one here would not make the server send it.
type Secret struct {
	ID         string          `json:"id"`
	Name       string          `json:"name"`
	PerDay     int             `json:"per_day"`
	Used       int             `json:"used"`
	CreatedAt  string          `json:"created_at"`
	RotatedAt  string          `json:"rotated_at"`
	LastUsedAt string          `json:"last_used_at"`
	Bindings   []SecretBinding `json:"bindings"`
}

// SecretBinding is one exposure: this credential, this skill, this environment variable name.
type SecretBinding struct {
	Skill string `json:"skill"`
	Env   string `json:"env"`
}

// SecretList is the response of `GET /secrets`.
type SecretList struct {
	Items []Secret `json:"items"`
}

// SecretShare is one grant on one binding. Both the skill and the variable name travel with it,
// because a share is per (credential, skill) and the console shows the pair.
type SecretShare struct {
	User      string `json:"user"`
	Skill     string `json:"skill"`
	Env       string `json:"env"`
	CreatedAt string `json:"created_at"`
}

// SecretShares is the response of `GET /secrets/{id}/shares`.
type SecretShares struct {
	Items []SecretShare `json:"items"`
}

// IncomingSecret is one variable this caller receives from somebody else's credential.
//
// `Owner` is a handle rather than an id: the row exists so a person can decide whether to use the
// variable, and an id decides nothing.
type IncomingSecret struct {
	CredentialID string `json:"credential_id"`
	Name         string `json:"name"`
	Owner        string `json:"owner"`
	Skill        string `json:"skill"`
	Env          string `json:"env"`
}

// IncomingSecrets is the response of `GET /secrets/incoming`.
type IncomingSecrets struct {
	Items []IncomingSecret `json:"items"`
}

// CredentialLease is the one response that carries plaintext. `ExpiresAt` exists so the command
// layer can say the value is good for this run only — there is no renewal call.
type CredentialLease struct {
	Values    []LeasedValue `json:"values"`
	ExpiresAt string        `json:"expires_at"`
}

// LeasedValue is one environment variable and its value.
type LeasedValue struct {
	Env   string `json:"env"`
	Value string `json:"value"`
}

func secretPath(id string) string {
	return APIPrefix + "/secrets/" + url.PathEscape(id)
}

// CreateSecret stores a value and binds it to one skill in one step — the "make this work on every
// machine" path, which is what a person arrives to do.
func (c Client) CreateSecret(ctx context.Context, name, value, skill, env string) (Secret, error) {
	body, err := c.sendJSON(ctx, http.MethodPost, APIPrefix+"/secrets", map[string]any{
		"name": name, "value": value, "skill": skill, "env": env,
	})
	if err != nil {
		return Secret{}, err
	}
	return decodeSecret(body)
}

// ListSecrets reads the vault, optionally narrowed to the bindings of one skill.
func (c Client) ListSecrets(ctx context.Context, skill string) (SecretList, error) {
	path := APIPrefix + "/secrets"
	if skill != "" {
		path += "?skill=" + url.QueryEscape(skill)
	}
	body, err := c.request(ctx, http.MethodGet, path, nil)
	if err != nil {
		return SecretList{}, err
	}
	var result SecretList
	if err := json.Unmarshal(body, &result); err != nil {
		return SecretList{}, fmt.Errorf("服务端没有返回一份凭据列表：%w", err)
	}
	return result, nil
}

// RotateSecret replaces the value and keeps every binding and share.
func (c Client) RotateSecret(ctx context.Context, id, value string) (Secret, error) {
	body, err := c.sendJSON(ctx, http.MethodPut, secretPath(id)+"/value", map[string]any{"value": value})
	if err != nil {
		return Secret{}, err
	}
	return decodeSecret(body)
}

// DeleteSecret is a hard delete; the bindings and shares go with it.
func (c Client) DeleteSecret(ctx context.Context, id string) error {
	_, err := c.request(ctx, http.MethodDelete, secretPath(id), nil)
	return err
}

// SetSecretQuota changes how many leases a day this credential allows.
func (c Client) SetSecretQuota(ctx context.Context, id string, perDay int) (Secret, error) {
	body, err := c.sendJSON(ctx, http.MethodPatch, secretPath(id), map[string]any{"per_day": perDay})
	if err != nil {
		return Secret{}, err
	}
	return decodeSecret(body)
}

// BindSecret exposes an existing value to one more skill.
func (c Client) BindSecret(ctx context.Context, id, skill, env string) (SecretBinding, error) {
	body, err := c.sendJSON(ctx, http.MethodPost, secretPath(id)+"/bindings", map[string]any{
		"skill": skill, "env": env,
	})
	if err != nil {
		return SecretBinding{}, err
	}
	var binding SecretBinding
	if err := json.Unmarshal(body, &binding); err != nil {
		return SecretBinding{}, fmt.Errorf("服务端没有返回绑定的结果：%w", err)
	}
	return binding, nil
}

// UnbindSecret removes one exposure. The value stays; only this skill stops receiving it.
func (c Client) UnbindSecret(ctx context.Context, id, skill, env string) error {
	path := secretPath(id) + "/bindings?skill=" + url.QueryEscape(skill) + "&env=" + url.QueryEscape(env)
	_, err := c.request(ctx, http.MethodDelete, path, nil)
	return err
}

// UnshareSecret withdraws one person's grant on one skill. **This is the direction that stays on
// the API plane**: taking access back is not the risky half, so the CLI does it directly while
// creating a grant opens the console (ADR 0081's first exception is about giving, not revoking).
func (c Client) UnshareSecret(ctx context.Context, id, skill, user string) error {
	path := secretPath(id) + "/shares?skill=" + url.QueryEscape(skill) + "&user=" + url.QueryEscape(user)
	_, err := c.request(ctx, http.MethodDelete, path, nil)
	return err
}

// IncomingSecrets lists the variables this caller receives from **other people's** credentials on
// one skill. Read-only, and it carries no value: it answers 「这个 skill 为什么能跑」 for somebody
// who never bound anything.
func (c Client) IncomingSecrets(ctx context.Context, skill string) (IncomingSecrets, error) {
	body, err := c.request(ctx, http.MethodGet,
		"/api/v1/secrets/incoming?skill="+url.QueryEscape(skill), nil)
	if err != nil {
		return IncomingSecrets{}, err
	}
	var result IncomingSecrets
	if err := json.Unmarshal(body, &result); err != nil {
		return IncomingSecrets{}, fmt.Errorf("服务端没有返回收到的凭据列表：%w", err)
	}
	return result, nil
}

// ReadSecretShares lists who a credential is shared with — read-only, because creating a share
// happens only in the console (ADR 0081, high-risk exception). Revoking one does not.
func (c Client) ReadSecretShares(ctx context.Context, id, skill string) (SecretShares, error) {
	path := secretPath(id) + "/shares"
	if skill != "" {
		path += "?skill=" + url.QueryEscape(skill)
	}
	body, err := c.request(ctx, http.MethodGet, path, nil)
	if err != nil {
		return SecretShares{}, err
	}
	var result SecretShares
	if err := json.Unmarshal(body, &result); err != nil {
		return SecretShares{}, fmt.Errorf("服务端没有返回共享列表：%w", err)
	}
	return result, nil
}

// LeaseCredentials asks, for one skill, for every value this caller may use there.
//
// **No version pin travels with this.** A binding belongs to the skill, not to a version of it, so
// asking about a resolved version would be asking a narrower question than the one that has an
// answer. The caller asks only after the server said there are bindings (`credentials_bound`), which
// is what keeps a skill without credentials at exactly today's request count.
func (c Client) LeaseCredentials(ctx context.Context, address string) (CredentialLease, error) {
	path, err := SkillPath(address)
	if err != nil {
		return CredentialLease{}, err
	}
	body, err := c.request(ctx, http.MethodPost, path+"/credentials/lease", nil)
	if err != nil {
		return CredentialLease{}, err
	}
	var lease CredentialLease
	if err := json.Unmarshal(body, &lease); err != nil {
		return CredentialLease{}, fmt.Errorf("服务端没有返回凭据租约：%w", err)
	}
	for _, value := range lease.Values {
		// **A name that cannot be an environment variable is refused here, not passed on.** The
		// child would ignore it silently, and a skill whose key "did not work" for that reason is a
		// failure nobody can see. The server validates too; this is the side that would lose the
		// error.
		if !validEnvName(value.Env) {
			return CredentialLease{}, fmt.Errorf(
				"服务端给的凭据变量名 %q 不是合法的环境变量名（字母、数字、下划线，不以数字开头），没有注入。",
				value.Env)
		}
	}
	return lease, nil
}

// validEnvName is the portable rule: a shell and an exec environment both accept this and little
// else, and it is the same rule the server applies when a binding is created.
func validEnvName(name string) bool {
	if name == "" {
		return false
	}
	for index, r := range name {
		letter := r == '_' || (r >= 'A' && r <= 'Z') || (r >= 'a' && r <= 'z')
		digit := r >= '0' && r <= '9'
		if index == 0 && !letter {
			return false
		}
		if !letter && !digit {
			return false
		}
	}
	return true
}

// EnvPairs turns a lease into `NAME=value` entries for the child process.
func (l CredentialLease) EnvPairs() []string {
	pairs := make([]string, 0, len(l.Values))
	for _, value := range l.Values {
		pairs = append(pairs, value.Env+"="+value.Value)
	}
	return pairs
}

func decodeSecret(body []byte) (Secret, error) {
	var secret Secret
	if err := json.Unmarshal(body, &secret); err != nil {
		return Secret{}, fmt.Errorf("服务端没有返回凭据：%w", err)
	}
	return secret, nil
}
