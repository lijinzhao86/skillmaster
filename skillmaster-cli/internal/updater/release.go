// Package updater is how this binary replaces itself.
//
// The channel is a property of the binary (`version.UpdateBase`, injected at build time), so the
// only question at run time is "is what that channel serves newer than me". The two shapes that
// answer comes in are not the same: a formal release publishes a version number (`1.0.2`), a beta
// publishes its version *and* the commit (`1.1.0-beta (1485383)`), because a beta's number never
// moves between builds (ADR 0099). `Newer` is the one place that knows both.
//
// **Nothing here deletes anything** (ADR 0101). A failed download leaves nothing behind — the
// archive is verified in memory before a byte is written — and a successful one replaces the
// running binary with `rename`, which is an overwrite, not an unlink.
package updater

import (
	"fmt"
	"strconv"
	"strings"
)

// Release is one build, as either side spells it.
type Release struct {
	// Tag is the version number: `1.0.2`, `1.1.0-beta`.
	Tag string
	// Revision is the short commit, present on beta builds only.
	Revision string
	// Raw is the line as published, trimmed — what messages quote.
	Raw string
}

// ParseRelease reads either shape of `version.txt`.
func ParseRelease(line string) (Release, error) {
	raw := strings.TrimSpace(line)
	if raw == "" {
		return Release{}, fmt.Errorf("版本标记是空的")
	}
	tag := raw
	revision := ""
	if open := strings.Index(raw, " ("); open >= 0 && strings.HasSuffix(raw, ")") {
		tag = strings.TrimSpace(raw[:open])
		revision = strings.TrimSpace(raw[open+2 : len(raw)-1])
	}
	if tag == "" || strings.ContainsAny(tag, " \t") {
		return Release{}, fmt.Errorf("版本标记「%s」不是版本号", raw)
	}
	if strings.ContainsAny(revision, " \t()") {
		return Release{}, fmt.Errorf("版本标记「%s」里的提交号不认识", raw)
	}
	return Release{Tag: tag, Revision: revision, Raw: raw}, nil
}

// Newer reports whether remote is a build this one should move to.
//
// **Only forward, and never by guess.** The site only moves forward (`RELEASE.md` §6 refuses an
// older tag), so a remote that compares older is not a downgrade to take — it is a mirror that is
// behind, or a machine that was pointed at the wrong base.
//
// The beta case is the one the version number cannot answer: two builds of `1.1.0-beta` differ only
// in the commit, so when both sides name one and the tags are equal, the commit decides. When only
// one side names a commit, the tags decide and equal tags mean "cannot tell" — which stays put.
func Newer(current, remote Release) bool {
	if current.Raw == remote.Raw {
		return false
	}
	sameTag := current.Tag == remote.Tag
	if sameTag && current.Revision != "" && remote.Revision != "" {
		return current.Revision != remote.Revision
	}
	return compareTags(remote.Tag, current.Tag) > 0
}

// compareTags orders two version numbers, ignoring any pre-release suffix except as a tiebreak.
//
// Deliberately not a semver library: the only numbers this program will ever compare are its own
// release tags, and the comparison has to agree with what the release pipeline writes (ADR 0091).
// A pre-release sorts *below* the release it leads to, and two pre-releases of the same base are
// ordered lexically — good enough to tell `1.1.0-beta` from `1.0.2`, which is the real question.
func compareTags(a, b string) int {
	abase, apre := splitTag(a)
	bbase, bpre := splitTag(b)
	anums, bn := numericParts(abase), numericParts(bbase)
	for i := 0; i < len(anums) || i < len(bn); i++ {
		var x, y int
		if i < len(anums) {
			x = anums[i]
		}
		if i < len(bn) {
			y = bn[i]
		}
		if x != y {
			if x < y {
				return -1
			}
			return 1
		}
	}
	switch {
	case apre == bpre:
		return 0
	case apre == "":
		return 1
	case bpre == "":
		return -1
	case apre < bpre:
		return -1
	default:
		return 1
	}
}

func splitTag(tag string) (base, pre string) {
	if dash := strings.Index(tag, "-"); dash >= 0 {
		return tag[:dash], tag[dash+1:]
	}
	return tag, ""
}

// numericParts reads the dotted numeric prefix of a version. A part that is not a number ends the
// prefix: `1.2.x` compares as `1.2`.
func numericParts(base string) []int {
	parts := strings.Split(base, ".")
	numbers := make([]int, 0, len(parts))
	for _, part := range parts {
		n, err := strconv.Atoi(part)
		if err != nil {
			break
		}
		numbers = append(numbers, n)
	}
	return numbers
}

// AssetName is the file this platform downloads: the same names the install scripts use, without a
// version number, so the address never changes (RELEASE.md §4).
func AssetName(goos, goarch string) string {
	if goos == "windows" {
		return fmt.Sprintf("skillmaster_%s_%s.zip", goos, goarch)
	}
	return fmt.Sprintf("skillmaster_%s_%s.tar.gz", goos, goarch)
}

// BinaryName is the executable's file name on this platform.
func BinaryName(goos string) string {
	if goos == "windows" {
		return "skillmaster.exe"
	}
	return "skillmaster"
}

// StagedPath is where a verified download waits before it replaces the binary.
//
// **The revision is in the name, and the extension is the platform's.** The revision keeps two
// runs from writing each other's download, and on Windows the `.exe` has to be last: the staged
// file is executed — it is the helper that performs the swap — and CreateProcess will not run a
// file whose name does not end in `.exe`.
func StagedPath(target string, release Release, goos string) string {
	revision := release.Revision
	if revision == "" {
		revision = release.Tag
	}
	revision = strings.NewReplacer("/", "_", "\\", "_", ":", "_", " ", "_").Replace(revision)
	if goos == "windows" {
		return target + ".new-" + revision + ".exe"
	}
	return target + ".new-" + revision
}
