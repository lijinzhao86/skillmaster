// `edit --stdin-json` and the batch it can carry (v44, ADR 0137).

package main

import (
	"encoding/json"
	"fmt"
	"io"
)

// editSpec is one replacement. The same shape is used by the single form and each entry of
// `{"edits": [...]}`.
type editSpec struct {
	Old        string `json:"old"`
	New        string `json:"new"`
	ReplaceAll bool   `json:"replace_all"`
	Regex      bool   `json:"regex"`
}

// parseStdinEdits reads one object from standard input: either `{old,new,...}` or `{edits:[...]}`.
//
// **The two forms are mutually exclusive by their keys, not by their values.** `{"old":"x","new":""}`
// is a valid replacement with an empty `new`, so emptiness cannot tell "absent" from "deliberate".
func parseStdinEdits() ([]editSpec, error) {
	raw, err := io.ReadAll(stdin)
	if err != nil {
		return nil, fmt.Errorf("读标准输入：%w", err)
	}
	var keys map[string]json.RawMessage
	if err := json.Unmarshal(raw, &keys); err != nil {
		return nil, usagef("--stdin-json 不是合法的 JSON：%v。%s", err, usageAt("skill edit"))
	}
	_, hasEdits := keys["edits"]
	_, hasOld := keys["old"]
	_, hasNew := keys["new"]
	_, hasReplace := keys["replace_all"]
	_, hasRegex := keys["regex"]
	if hasEdits && (hasOld || hasNew || hasReplace || hasRegex) {
		return nil, usagef("--stdin-json 要么是一条 {old,new}，要么是 {edits:[...]}，不要同时给。%s",
			usageAt("skill edit"))
	}
	if hasEdits {
		var body struct {
			Edits []editSpec `json:"edits"`
		}
		if err := json.Unmarshal(raw, &body); err != nil {
			return nil, usagef("--stdin-json 的 edits 不是一组改动：%v。%s", err,
				usageAt("skill edit"))
		}
		if len(body.Edits) == 0 {
			return nil, usagef("--stdin-json 的 edits 是空的；没有要改的内容。%s",
				usageAt("skill edit"))
		}
		return validateEditSpecs(body.Edits)
	}
	if !hasOld {
		return nil, usagef("--stdin-json 里没有 old；一条改动至少要有 "+
			`{"old":…,"new":…}。%s`, usageAt("skill edit"))
	}
	var one editSpec
	if err := json.Unmarshal(raw, &one); err != nil {
		return nil, usagef("--stdin-json 不是一条改动：%v。%s", err, usageAt("skill edit"))
	}
	return validateEditSpecs([]editSpec{one})
}

// validateEditSpecs refuses at index granularity, before any request: a batch is all or nothing,
// and "which one" is the first thing the caller needs.
func validateEditSpecs(specs []editSpec) ([]editSpec, error) {
	for index, spec := range specs {
		where := fmt.Sprintf("第 %d 处", index+1)
		if spec.Old == "" {
			return nil, usagef("%s没有 old；每一处都要给被替换的文本。%s", where,
				usageAt("skill edit"))
		}
		if !spec.Regex && spec.Old == spec.New {
			return nil, fmt.Errorf("%s：old 与 new 相同，没有可改的内容。", where)
		}
		if spec.Regex {
			if _, err := compilePattern(spec.Old); err != nil {
				return nil, fmt.Errorf("%s：%w", where, err)
			}
		}
	}
	return specs, nil
}

// applyEdits runs the batch in memory. A failure names its index and returns no bytes, so the
// caller never writes a half-applied draft.
func applyEdits(content []byte, specs []editSpec, address, relpath string) ([]byte, error) {
	updated := content
	for index, spec := range specs {
		next, err := replaceExactly(updated, spec.Old, spec.New, address, relpath,
			spec.ReplaceAll, spec.Regex)
		if err != nil {
			return nil, fmt.Errorf("第 %d 处：%w", index+1, err)
		}
		updated = next
	}
	return updated, nil
}
