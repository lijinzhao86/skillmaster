package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
)

// Notification is one row of the feed (v45). `Kind` is the server's open vocabulary; `State` is
// the result snapshot, not the business object's state.
type Notification struct {
	ID        string    `json:"id"`
	Kind      string    `json:"kind"`
	State     string    `json:"state"`
	Actor     string    `json:"actor"`
	Skill     *SkillRef `json:"skill"`
	Role      string    `json:"role"`
	Title     string    `json:"title"`
	Body      string    `json:"body"`
	CreatedAt  string    `json:"created_at"`
	ResolvedAt string    `json:"resolved_at"`
	ExpiresAt  string    `json:"expires_at"`
	Read      bool      `json:"read"`
	Viewable  bool      `json:"viewable"`
}

// SkillRef is the address a notification points at; absent on announcements.
type SkillRef struct {
	Namespace string `json:"namespace"`
	Name      string `json:"name"`
}

// Address is the `namespace/name` form the command layer prints and feeds to other commands.
func (s SkillRef) Address() string { return s.Namespace + "/" + s.Name }

// NotificationPage is one page plus the two counts.
type NotificationPage struct {
	Items  []Notification `json:"items"`
	Total  int            `json:"total"`
	Unread int            `json:"unread"`
}

// ListNotifications reads the feed. status is attention|pending|all; page is 1-based.
func (c Client) ListNotifications(ctx context.Context, status string, unread bool,
	page int) (NotificationPage, error) {
	params := url.Values{}
	if status != "" {
		params.Set("status", status)
	}
	if unread {
		params.Set("unread", "true")
	}
	if page > 1 {
		params.Set("p", strconv.Itoa(page))
	}
	path := APIPrefix + "/notifications"
	if encoded := params.Encode(); encoded != "" {
		path += "?" + encoded
	}
	raw, err := c.request(ctx, http.MethodGet, path, nil)
	if err != nil {
		return NotificationPage{}, err
	}
	var result NotificationPage
	if err := json.Unmarshal(raw, &result); err != nil {
		return NotificationPage{}, fmt.Errorf("服务端没有返回一份通知列表：%w", err)
	}
	return result, nil
}

// GetNotification reads one row. It does not mark it read.
func (c Client) GetNotification(ctx context.Context, id string) (Notification, error) {
	raw, err := c.request(ctx, http.MethodGet,
		APIPrefix+"/notifications/"+url.PathEscape(id), nil)
	if err != nil {
		return Notification{}, err
	}
	var result Notification
	if err := json.Unmarshal(raw, &result); err != nil {
		return Notification{}, fmt.Errorf("服务端没有返回一条通知：%w", err)
	}
	return result, nil
}

// MarkNotificationRead marks one row read. Idempotent on the server.
func (c Client) MarkNotificationRead(ctx context.Context, id string) error {
	_, err := c.request(ctx, http.MethodPost,
		APIPrefix+"/notifications/"+url.PathEscape(id)+"/read", nil)
	return err
}

// MarkAllNotificationsRead marks everything this account can see. It returns how many moved.
func (c Client) MarkAllNotificationsRead(ctx context.Context) (int, error) {
	raw, err := c.request(ctx, http.MethodPost, APIPrefix+"/notifications/read-all", nil)
	if err != nil {
		return 0, err
	}
	var answer struct {
		Marked int `json:"marked"`
	}
	if err := json.Unmarshal(raw, &answer); err != nil {
		return 0, fmt.Errorf("服务端没有返回已读数量：%w", err)
	}
	return answer.Marked, nil
}

// AcceptedInvitation is the answer to accepting an invitation.
type AcceptedInvitation struct {
	Skill SkillRef `json:"skill"`
	Role  string   `json:"role"`
}

// AcceptInvitation accepts a share invitation.
func (c Client) AcceptInvitation(ctx context.Context, id string) (AcceptedInvitation, error) {
	raw, err := c.request(ctx, http.MethodPost,
		APIPrefix+"/notifications/"+url.PathEscape(id)+"/accept", nil)
	if err != nil {
		return AcceptedInvitation{}, err
	}
	var result AcceptedInvitation
	if err := json.Unmarshal(raw, &result); err != nil {
		return AcceptedInvitation{}, fmt.Errorf("服务端没有说明接受的结果：%w", err)
	}
	return result, nil
}

// DeclineInvitation declines a share invitation.
func (c Client) DeclineInvitation(ctx context.Context, id string) error {
	_, err := c.request(ctx, http.MethodPost,
		APIPrefix+"/notifications/"+url.PathEscape(id)+"/decline", nil)
	return err
}

// DecidedRequest is the answer to approving; denying has no body.
type DecidedRequest struct {
	Skill SkillRef `json:"skill"`
	Role  string   `json:"role"`
}

// ApproveAccessRequest approves a request, at role when non-empty and at the requested rung
// otherwise.
func (c Client) ApproveAccessRequest(ctx context.Context, id, role string) (DecidedRequest, error) {
	var payload []byte
	if role != "" {
		encoded, err := json.Marshal(map[string]string{"role": role})
		if err != nil {
			return DecidedRequest{}, fmt.Errorf("构造请求体：%w", err)
		}
		payload = encoded
	}
	raw, err := c.request(ctx, http.MethodPost,
		APIPrefix+"/notifications/"+url.PathEscape(id)+"/approve", payload)
	if err != nil {
		return DecidedRequest{}, err
	}
	var result DecidedRequest
	if err := json.Unmarshal(raw, &result); err != nil {
		return DecidedRequest{}, fmt.Errorf("服务端没有说明批准的结果：%w", err)
	}
	return result, nil
}

// DenyAccessRequest denies a request.
func (c Client) DenyAccessRequest(ctx context.Context, id string) error {
	_, err := c.request(ctx, http.MethodPost,
		APIPrefix+"/notifications/"+url.PathEscape(id)+"/deny", nil)
	return err
}

// InvitationPage is the invitations still waiting on one skill.
type InvitationPage struct {
	Items []Invitation `json:"items"`
}

// Invitation is one waiting share — the inviter's view.
type Invitation struct {
	Handle    string `json:"handle"`
	Role      string `json:"role"`
	CreatedAt string `json:"created_at"`
	ExpiresAt string `json:"expires_at"`
	// State is "pending" or "expired", computed by the server from expires_at.
	State string `json:"state"`
}

// ListInvitations reads every invitation on a skill; expired rows are included with state=expired.
func (c Client) ListInvitations(ctx context.Context, address string) (InvitationPage, error) {
	base, err := SkillPath(address)
	if err != nil {
		return InvitationPage{}, err
	}
	raw, err := c.authorSend(ctx, http.MethodGet, base+"/invitations", nil, "", "")
	if err != nil {
		return InvitationPage{}, err
	}
	var result InvitationPage
	if err := json.Unmarshal(raw, &result); err != nil {
		return InvitationPage{}, fmt.Errorf("服务端没有返回一份待接受名单：%w", err)
	}
	return result, nil
}

// RevokeInvitation withdraws a waiting invitation. Idempotent on the server.
func (c Client) RevokeInvitation(ctx context.Context, address, handle string) error {
	base, err := SkillPath(address)
	if err != nil {
		return err
	}
	_, err = c.authorSend(ctx, http.MethodDelete,
		base+"/invitations/"+url.PathEscape(handle), nil, "", "")
	return err
}
