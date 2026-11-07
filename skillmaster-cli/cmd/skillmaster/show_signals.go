package main

import (
	"fmt"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// printOrigin is the source block of `skill show`. Signals are rendered by label and display value;
// the CLI does not know GitHub from SkillHub.
func printOrigin(meta api.Metadata) {
	if meta.Origin == nil {
		return
	}
	origin := meta.Origin
	line := "  来源：" + origin.Label
	if origin.Kind != "official" && origin.Locator != "" {
		line += " · " + origin.Locator
	}
	fmt.Println(line)
	if origin.Revision != "" && origin.Kind != "official" {
		fmt.Printf("  对应版本：commit %s\n", shortRevision(origin.Revision))
	}
	var signals []string
	for _, signal := range origin.Signals {
		if signal.Display == "" {
			continue
		}
		signals = append(signals, signal.Label+" "+signal.Display)
	}
	if len(signals) > 0 {
		fmt.Printf("  指标：%s\n", strings.Join(signals, " · "))
	}
	if origin.CheckedAt != "" && origin.Kind != "official" {
		checked := origin.CheckedAt
		if len(checked) >= 10 {
			checked = checked[:10]
		}
		fmt.Printf("  最近校验：%s\n", checked)
	}
}

func shortRevision(revision string) string {
	if len(revision) > 7 {
		return revision[:7]
	}
	return revision
}

// licenseLabel is the name-and-status half both commands share.
func licenseLabel(license *api.License) string {
	switch license.Status {
	case "ok":
		return license.Spdx
	case "declared-only":
		if license.Spdx == "NOASSERTION" {
			/* v50 records a non-text declaration as declared-only + NOASSERTION; SPDX's sentinel
			   is not a license name, and the console says 其他许可 for the same state. */
			return "其他许可"
		}
		return license.Spdx + "（仅声明，未附全文）"
	case "other":
		return "其他许可"
	default:
		return "未声明"
	}
}

// printLicenseLine is `skill show`'s one line: the name and status only. What the name means is
// `skill license`'s subject (PRD §许可证怎么展示).
func printLicenseLine(license *api.License) {
	if license == nil {
		return
	}
	fmt.Printf("  许可证：%s\n", licenseLabel(license))
}

// printLicenseBlock is `skill license`: the name and status, then what the license allows and
// requires. It never prints the full text — that is the `.skillmaster/LICENSE` sidecar or the link.
func printLicenseBlock(address string, license *api.License) {
	fmt.Println(address)
	if license == nil {
		// An older server sends no block at all. Saying so is the honest answer for a command whose
		// whole subject is that block.
		fmt.Println("  许可证：—")
		fmt.Println("  说明：这个服务端没有返回许可信息。")
		return
	}
	fmt.Printf("  许可证：%s\n", licenseLabel(license))
	switch license.Status {
	case "ok":
		fmt.Println("  状态：已识别")
		if license.Copyright != "" {
			fmt.Printf("  版权：%s\n", license.Copyright)
		}
		if allows := translateAllows(license.Allows); allows != "" {
			fmt.Printf("  可做：%s\n", allows)
		}
		if requires := translateRequires(license.Requires); requires != "" {
			fmt.Printf("  要履行：%s\n", requires)
		}
		if license.URL != "" {
			fmt.Printf("  全文：%s\n", license.URL)
		}
	case "declared-only":
		fmt.Println("  状态：仅声明")
	case "other":
		fmt.Println("  状态：其他")
		if license.URL != "" {
			fmt.Printf("  全文：%s\n", license.URL)
		}
		fmt.Println("  说明：以全文为准。")
	default:
		fmt.Println("  状态：未声明")
		fmt.Println("  说明：来源没有声明再分发许可；可以按平台可见性使用，不要复制到平台外。")
	}
}

func translateAllows(allows []string) string {
	words := make([]string, 0, len(allows))
	for _, allow := range allows {
		switch allow {
		case "commercial":
			words = append(words, "可商用")
		case "modify":
			words = append(words, "可修改")
		case "redistribute":
			words = append(words, "可再分发")
		case "patent":
			words = append(words, "含专利授权")
		default:
			words = append(words, allow)
		}
	}
	return strings.Join(words, " · ")
}

func translateRequires(requires []string) string {
	words := make([]string, 0, len(requires))
	for _, require := range requires {
		switch require {
		case "notice":
			words = append(words, "保留版权与许可声明")
		case "state-changes":
			words = append(words, "标注修改")
		case "same-license":
			words = append(words, "衍生作品同许可")
		case "same-license-files":
			words = append(words, "修改文件同许可")
		case "network":
			words = append(words, "网络服务也需开放")
		case "attribution":
			words = append(words, "署名")
		default:
			words = append(words, require)
		}
	}
	return strings.Join(words, " · ")
}

func printUsage(usage *api.Usage) {
	if usage == nil {
		// An older server sends no block at all; silence is the only honest answer.
		return
	}
	if usage.CallsBucket == "" && usage.LastUsed == "" && usage.Joins == 0 {
		fmt.Println("  使用：还没有人用过")
		return
	}
	var parts []string
	if usage.CallsBucket != "" {
		parts = append(parts, callsBucket(usage.CallsBucket))
	}
	if usage.Bucket != "" {
		parts = append(parts, usageBucket(usage.Bucket)+"用过")
	}
	if usage.LastUsed != "" {
		parts = append(parts, "最近使用 "+dayOf(usage.LastUsed))
	}
	if usage.Joins > 0 {
		parts = append(parts, fmt.Sprintf("入库 %d", usage.Joins))
	}
	fmt.Println("  使用：" + strings.Join(parts, " · "))
}

func usageBucket(bucket string) string {
	if bucket == "少于 10 个账号" {
		return bucket
	}
	return bucket + " 个账号"
}

func callsBucket(bucket string) string {
	if bucket == "少于 10 次调用" {
		return bucket
	}
	return bucket + " 次调用"
}
