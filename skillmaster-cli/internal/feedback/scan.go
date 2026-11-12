package feedback

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode"
)

// The scan is a warning, not a redactor — except for secrets, which never leave the machine in
// clear text. A phone, an ID or a card number may be exactly what the report is about; a key
// prefix never is, and cannot be rotated once it has travelled.
var (
	// **A key prefix only counts when no word-like character precedes it.** `disk-usage-full` and
	// `task-management` contain `sk-`; the scan's false positives cost a confirmation, but the
	// replacement below rewrites the document, so this pattern must not match them.
	secretPattern = regexp.MustCompile(
		`(?i)(^|[^A-Za-z0-9_])(sk-[a-z0-9_-]{8,}|AKIA[0-9A-Z]{16}|skm_[a-z0-9]+)`)
	digitRun = regexp.MustCompile(`\b\d{11,19}[Xx]?\b`)
	mobile   = regexp.MustCompile(`^1[3-9]\d{9}$`)
)

var categoryNames = map[string]string{
	"phone":         "手机号",
	"id_number":     "身份证号",
	"bank_card":     "银行卡号",
	"secret_prefix": "密钥前缀",
}

// Scan is what the client found; the server runs the same classification and is the gate.
type Scan struct {
	Counts map[string]int
}

func (s Scan) Any() bool { return len(s.Counts) > 0 }

// Summary is one readable line, e.g. `手机号 1、密钥前缀 1`. Sorted so the same content always
// produces the same sentence.
func (s Scan) Summary() string {
	categories := make([]string, 0, len(s.Counts))
	for category := range s.Counts {
		categories = append(categories, category)
	}
	sort.Strings(categories)
	parts := make([]string, 0, len(categories))
	for _, category := range categories {
		name := categoryNames[category]
		if name == "" {
			name = category
		}
		parts = append(parts, name+" "+strconv.Itoa(s.Counts[category]))
	}
	return strings.Join(parts, "、")
}

// ScanText classifies digit runs by length and counts secret-bearing prefixes separately.
//
// The classification is deliberately coarse: a false positive costs one confirmation, a silent
// miss costs a leaked value. An 18-digit run is reported as an ID number even when it is a bank
// card; the person sees the category, not a verdict.
func ScanText(content string) Scan {
	counts := map[string]int{}
	if n := len(secretPattern.FindAllStringSubmatch(content, -1)); n > 0 {
		counts["secret_prefix"] = n
	}
	for _, run := range digitRun.FindAllString(withoutDigitSeparators(content), -1) {
		xSuffix := strings.HasSuffix(run, "X") || strings.HasSuffix(run, "x")
		length := len(run)
		if xSuffix {
			length--
		}
		if xSuffix && length == 17 {
			counts["id_number"]++
			continue
		}
		switch length {
		case 11:
			if mobile.MatchString(run) {
				counts["phone"]++
			}
		case 18:
			counts["id_number"]++
		case 16, 17, 19:
			counts["bank_card"]++
		}
	}
	return Scan{Counts: counts}
}

// RedactSecrets is applied before the document is sent, so a key never travels. The server runs
// the same replacement again; doing it twice is idempotent. The leading separator the pattern had
// to match is put back, so only the key itself is replaced.
func RedactSecrets(content string) string {
	return secretPattern.ReplaceAllString(content, "${1}[已隐藏]")
}

// withoutDigitSeparators drops a space, tab, hyphen or dot that sits between two digits on the
// same line: `138-0013-8000` is one phone number, not three numbers. The stored document keeps the separators; this copy is
// only what the length classification reads.
func withoutDigitSeparators(content string) string {
	runes := []rune(content)
	var out strings.Builder
	out.Grow(len(content))
	for i, r := range runes {
		if isDigitSeparator(r) && i > 0 && i+1 < len(runes) &&
			unicode.IsDigit(runes[i-1]) && unicode.IsDigit(runes[i+1]) {
			continue
		}
		out.WriteRune(r)
	}
	return out.String()
}

// isDigitSeparator covers the same-line separators only. A newline is a boundary between values:
// gluing across it would turn `13800138000\n1. …` into a 12-digit run that no category names.
func isDigitSeparator(r rune) bool {
	switch r {
	case ' ', '\t', '-', '.':
		return true
	}
	return false
}
