package main

import (
	"testing"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// ADR 0179: text refusals carry code points and a field path. The field decides the sentence,
// because `too_long` also covers a tag's 200-word note.
func TestTextRefusalNamesTheFieldAndBothNumbers(t *testing.T) {
	cases := []struct {
		name   string
		detail api.IssueDetail
		want   string
	}{
		{
			"description over 1024",
			api.IssueDetail{Field: "description", Issue: "too_long",
				ActualCodePoints: 1025, LimitCodePoints: 1024},
			"描述有 1025 个字，超过 1024 个的上限。",
		},
		{
			"author when_to_use under metadata",
			api.IssueDetail{Field: "metadata.when_to_use", Issue: "too_long",
				ActualCodePoints: 201, LimitCodePoints: 200},
			"适用场景有 201 个字，超过 200 个的上限。",
		},
		{
			"a missing description",
			api.IssueDetail{Field: "description", Issue: "required_field_missing"},
			"描述是必填的。",
		},
		{
			"a non-text when_to_use",
			api.IssueDetail{Field: "when_to_use", Issue: "field_not_text"},
			"适用场景要填文本，不能是数字或列表。",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			server := &api.ServerError{Code: "invalid_upload", DetailList: []api.IssueDetail{tc.detail}}
			got, ok := textRefusal(server)
			if !ok || got != tc.want {
				t.Fatalf("textRefusal = %q, %v; want %q", got, ok, tc.want)
			}
		})
	}
}

func TestTextRefusalLeavesTheTagNoteAlone(t *testing.T) {
	server := &api.ServerError{Code: "invalid_request", DetailList: []api.IssueDetail{
		{Field: "description", Issue: "too_long"},
	}}
	if _, ok := textRefusal(server); ok {
		t.Fatal("a too_long without code points belongs to the tag note, not a skill-text field")
	}
}
