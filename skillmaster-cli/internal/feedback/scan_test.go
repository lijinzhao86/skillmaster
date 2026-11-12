package feedback

import "testing"

func TestScanTextClassifiesDigitRunsByLength(t *testing.T) {
	cases := []struct {
		name    string
		content string
		want    map[string]int
	}{
		{"phone", "报错时收到的短信验证码发到了 13800138000", map[string]int{"phone": 1}},
		{"id", "证件 110101199001011234 绑定失败", map[string]int{"id_number": 1}},
		{"id with X", "证件 11010119900101123X 绑定失败", map[string]int{"id_number": 1}},
		{"bank card", "卡号 6222021234567890 被拒", map[string]int{"bank_card": 1}},
		{"short run", "退出码 125 与 2", map[string]int{}},
		{"landline is not a mobile", "电话 01012345678", map[string]int{}},
		{"separated phone", "手机 138-0013-8000", map[string]int{"phone": 1}},
		{"separated bank card", "卡号 6222 0212 3456 7890", map[string]int{"bank_card": 1}},
		{"phone at the end of a line", "手机号 13800138000\n1. 收不到验证码", map[string]int{"phone": 1}},
		{"phone above a numbered list", "手机号 13800138000\n123456789 条错误", map[string]int{"phone": 1}},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got := ScanText(tc.content).Counts
			if len(got) != len(tc.want) {
				t.Fatalf("counts %v, want %v", got, tc.want)
			}
			for category, count := range tc.want {
				if got[category] != count {
					t.Fatalf("counts %v, want %v", got, tc.want)
				}
			}
		})
	}
}

func TestOrdinaryWordsAreNotMistakenForKeys(t *testing.T) {
	for _, word := range []string{"disk-usage-full", "task-management", "risk-assessment"} {
		if got := RedactSecrets(word); got != word {
			t.Errorf("redacted %q to %q", word, got)
		}
		if counts := ScanText(word).Counts; len(counts) != 0 {
			t.Errorf("%q counted as %v", word, counts)
		}
	}
}

func TestRedactSecretsRemovesOnlyKeys(t *testing.T) {
	content := "key sk-abcdefgh1234 发给 13800138000"
	got := RedactSecrets(content)
	if got != "key [已隐藏] 发给 13800138000" {
		t.Fatalf("redacted to %q", got)
	}
	if ScanText(content).Counts["secret_prefix"] != 1 {
		t.Fatalf("the key prefix was not counted: %v", ScanText(content).Counts)
	}
	// The replacement is idempotent: the server runs it again on what the CLI sent.
	if again := RedactSecrets(got); again != got {
		t.Fatalf("second pass changed the text: %q", again)
	}
}
