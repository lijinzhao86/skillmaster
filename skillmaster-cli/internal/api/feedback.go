package api

import (
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"net/url"
)

// FeedbackConsent is what the server asks a person to agree to before a document travels, and the
// privacy policy version it belongs to. The CLI prints Notice verbatim and sends Version back; a
// deployment that replaces the notice between this read and the POST is refused, so the stored
// version always names the text this submission was checked against.
type FeedbackConsent struct {
	Version string `json:"version"`
	Notice  string `json:"notice"`
}

// FeedbackReceipt is the one handle a person needs after submitting: the value the notification
// carries and the delete command takes.
type FeedbackReceipt struct {
	Receipt   string `json:"receipt"`
	CreatedAt string `json:"created_at"`
}

// GetFeedbackConsent reads the current notice. It is the first call of every send, because the
// version is what the server will compare the submission against.
func (c Client) GetFeedbackConsent(ctx context.Context) (FeedbackConsent, error) {
	raw, err := c.request(ctx, http.MethodGet, APIPrefix+"/feedback/consent", nil)
	if err != nil {
		return FeedbackConsent{}, err
	}
	var consent FeedbackConsent
	if err := json.Unmarshal(raw, &consent); err != nil {
		return FeedbackConsent{}, fmt.Errorf("服务端没有返回同意说明：%w", err)
	}
	return consent, nil
}

// SubmitFeedback posts one document. The content is already redacted by the command layer; the
// server redacts again.
func (c Client) SubmitFeedback(ctx context.Context, content, skillAddress string,
	diagnostics map[string]string, consentVersion string, allowSensitive bool) (FeedbackReceipt, error) {
	body := map[string]any{
		"content":         content,
		"consent_version": consentVersion,
		"allow_sensitive": allowSensitive,
	}
	if skillAddress != "" {
		body["skill_address"] = skillAddress
	}
	if len(diagnostics) > 0 {
		body["diagnostics"] = diagnostics
	}
	payload, err := json.Marshal(body)
	if err != nil {
		return FeedbackReceipt{}, fmt.Errorf("组装反馈请求：%w", err)
	}
	raw, err := c.request(ctx, http.MethodPost, APIPrefix+"/feedback", payload)
	if err != nil {
		return FeedbackReceipt{}, err
	}
	var receipt FeedbackReceipt
	if err := json.Unmarshal(raw, &receipt); err != nil {
		return FeedbackReceipt{}, fmt.Errorf("服务端没有返回回执：%w", err)
	}
	return receipt, nil
}

// DeleteFeedback withdraws one document and its archive copy.
func (c Client) DeleteFeedback(ctx context.Context, receipt string) error {
	_, err := c.request(ctx, http.MethodDelete,
		APIPrefix+"/feedback/"+url.PathEscape(receipt), nil)
	return err
}
