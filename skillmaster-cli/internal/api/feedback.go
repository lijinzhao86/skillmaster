package api

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"net/url"
	"os"
)

// FeedbackConsent is what the server asks a person to agree to before a document travels, and the
// privacy policy version it belongs to. The CLI prints Notice verbatim and sends Version back; a
// deployment that replaces the notice between this read and the POST is refused, so the stored
// version always names the text this submission was checked against.
type FeedbackConsent struct {
	Version string `json:"version"`
	Notice  string `json:"notice"`
}

// FeedbackAttachment is what the server says it stored: the detected type, the stored size and the
// server's digest. The client's preview showed its own values; these are the record (v65).
type FeedbackAttachment struct {
	Filename  string `json:"filename"`
	MediaType string `json:"media_type"`
	SizeBytes int64  `json:"size_bytes"`
	SHA256    string `json:"sha256"`
}

// FeedbackReceipt is the one handle a person needs after submitting: the value the notification
// carries and the delete command takes.
type FeedbackReceipt struct {
	Receipt     string               `json:"receipt"`
	CreatedAt   string               `json:"created_at"`
	Attachments []FeedbackAttachment `json:"attachments"`
}

// FeedbackAttachmentUpload is one validated local file to send as a multipart part. The server
// reads the type and the size from the bytes; only a display name travels alongside the file.
type FeedbackAttachmentUpload struct {
	Path     string
	Filename string
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

// SubmitFeedbackWithAttachments posts the document and its files as one multipart request (v65).
//
// **The body is streamed.** A hundred mebibytes assembled in memory would double the memory and
// the copy; the pipe hands each file to the request as it is read, and the server spools it to its
// own temp file. The deadline is `archiveTimeout`, not `callTimeout`: a video over a slow link is
// not a broken request.
func (c Client) SubmitFeedbackWithAttachments(ctx context.Context, content, skillAddress string,
	diagnostics map[string]string, consentVersion string, allowSensitive bool,
	attachments []FeedbackAttachmentUpload) (FeedbackReceipt, error) {
	ctx, cancel := context.WithTimeout(ctx, archiveTimeout)
	defer cancel()

	reader, writer := io.Pipe()
	form := multipart.NewWriter(writer)
	writerDone := make(chan error, 1)
	go func() {
		err := writeFeedbackMultipart(form, content, skillAddress, diagnostics, consentVersion,
			allowSensitive, attachments)
		if err != nil {
			_ = writer.CloseWithError(err)
		} else {
			_ = writer.Close()
		}
		writerDone <- err
	}()

	req, err := http.NewRequestWithContext(ctx, http.MethodPost,
		c.BaseURL+APIPrefix+"/feedback", reader)
	if err != nil {
		_ = writer.CloseWithError(err)
		return FeedbackReceipt{}, fmt.Errorf("组装反馈请求：%w", err)
	}
	req.Header.Set("Content-Type", form.FormDataContentType())
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Authorization", "Bearer "+c.Token)

	resp, err := c.http().Do(req)
	if err != nil {
		// The writer's error is the one that explains a failed local read; prefer it to the
		// transport's wrapper around a closed pipe.
		if writeErr := <-writerDone; writeErr != nil {
			return FeedbackReceipt{}, writeErr
		}
		return FeedbackReceipt{}, c.withHint(fmt.Errorf("上传反馈失败：%w", err))
	}
	defer resp.Body.Close()
	body, readErr := readAll(resp)
	if writeErr := <-writerDone; writeErr != nil && resp.StatusCode == http.StatusCreated {
		// The server accepted a body that our writer could not have completed; the error names the
		// local file that failed, which is more useful than a receipt for a partial upload.
		return FeedbackReceipt{}, writeErr
	}
	if readErr != nil {
		return FeedbackReceipt{}, fmt.Errorf("读取反馈响应：%w", readErr)
	}
	switch resp.StatusCode {
	case http.StatusCreated, http.StatusOK:
		var receipt FeedbackReceipt
		if err := json.Unmarshal(body, &receipt); err != nil {
			return FeedbackReceipt{}, fmt.Errorf("服务端没有返回回执：%w", err)
		}
		return receipt, nil
	case http.StatusUnauthorized:
		return FeedbackReceipt{}, refusal(body)
	case http.StatusForbidden:
		return FeedbackReceipt{}, denial(resp, body)
	default:
		// **The envelope survives**, so the command layer can render the server's code (the same
		// shape `request` gives every other call). A `StatusRefusal` here would keep the status and
		// drop the code, which is the one fact the help page maps to a next step.
		return FeedbackReceipt{}, serverError(resp.StatusCode, body)
	}
}

func writeFeedbackMultipart(form *multipart.Writer, content, skillAddress string,
	diagnostics map[string]string, consentVersion string, allowSensitive bool,
	attachments []FeedbackAttachmentUpload) error {
	if err := form.WriteField("content", content); err != nil {
		return fmt.Errorf("构造反馈请求：%w", err)
	}
	if skillAddress != "" {
		if err := form.WriteField("skill_address", skillAddress); err != nil {
			return fmt.Errorf("构造反馈请求：%w", err)
		}
	}
	if len(diagnostics) > 0 {
		payload, err := json.Marshal(diagnostics)
		if err != nil {
			return fmt.Errorf("构造诊断字段：%w", err)
		}
		if err := form.WriteField("diagnostics", string(payload)); err != nil {
			return fmt.Errorf("构造反馈请求：%w", err)
		}
	}
	if err := form.WriteField("consent_version", consentVersion); err != nil {
		return fmt.Errorf("构造反馈请求：%w", err)
	}
	if allowSensitive {
		if err := form.WriteField("allow_sensitive", "true"); err != nil {
			return fmt.Errorf("构造反馈请求：%w", err)
		}
	}
	for _, attachment := range attachments {
		part, err := form.CreateFormFile("attachment", attachment.Filename)
		if err != nil {
			return fmt.Errorf("构造附件 %s：%w", attachment.Filename, err)
		}
		file, err := os.Open(attachment.Path)
		if err != nil {
			return fmt.Errorf("读附件 %s：%w", attachment.Filename, err)
		}
		_, copyErr := io.Copy(part, file)
		closeErr := file.Close()
		if copyErr != nil {
			return fmt.Errorf("上传附件 %s：%w", attachment.Filename, copyErr)
		}
		if closeErr != nil {
			return fmt.Errorf("上传附件 %s：%w", attachment.Filename, closeErr)
		}
	}
	if err := form.Close(); err != nil {
		return fmt.Errorf("构造反馈请求：%w", err)
	}
	return nil
}

// DeleteFeedback withdraws one document and its archive copy.
func (c Client) DeleteFeedback(ctx context.Context, receipt string) error {
	_, err := c.request(ctx, http.MethodDelete,
		APIPrefix+"/feedback/"+url.PathEscape(receipt), nil)
	return err
}
