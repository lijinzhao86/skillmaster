package feedback

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"unicode"
)

// The attachment contract the CLI mirrors so a bad file fails before it travels. The server still
// enforces every one of these; these copies are what save a round trip and give the agent the
// answer while it can still change the file. Values are the same as
// `FeedbackAttachmentSafety` on the server (v65).
const (
	MaxAttachments         = 5
	MaxImageBytes    int64 = 10 * 1024 * 1024
	MaxVideoBytes    int64 = 50 * 1024 * 1024
	MaxTotalBytes    int64 = 100 * 1024 * 1024
	maxFilenameRunes       = 200
	headBytes              = 256
)

var (
	pngSignature  = []byte{0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'}
	jpegSignature = []byte{0xFF, 0xD8, 0xFF}
	gif87a        = []byte("GIF87a")
	gif89a        = []byte("GIF89a")
	riff          = []byte("RIFF")
	ebml          = []byte{0x1A, 0x45, 0xDF, 0xA3}
	mp4Brands     = map[string]bool{
		"isom": true, "iso2": true, "iso4": true, "iso5": true, "iso6": true,
		"mp41": true, "mp42": true, "avc1": true, "M4V ": true, "MSNV": true, "dash": true,
	}
)

// Media is what a file header says it is. Limit is the per-file ceiling for that type.
type Media struct {
	Type  string
	Ext   string
	Limit int64
}

var allowed = []Media{
	{"image/png", "png", MaxImageBytes},
	{"image/jpeg", "jpg", MaxImageBytes},
	{"image/gif", "gif", MaxImageBytes},
	{"image/webp", "webp", MaxImageBytes},
	{"video/mp4", "mp4", MaxVideoBytes},
	{"video/quicktime", "mov", MaxVideoBytes},
	{"video/webm", "webm", MaxVideoBytes},
}

// AllowedTypes is the sentence a refusal may show; ordered for a stable message.
func AllowedTypes() []string {
	types := make([]string, 0, len(allowed))
	for _, media := range allowed {
		types = append(types, media.Type)
	}
	return types
}

// Detect classifies a file header. The extension and any client-declared type are never consulted:
// a `.png` that begins with an SVG is not a PNG.
func Detect(head []byte) (Media, bool) {
	if bytes.HasPrefix(head, pngSignature) {
		return allowed[0], true
	}
	if bytes.HasPrefix(head, jpegSignature) {
		return allowed[1], true
	}
	if bytes.HasPrefix(head, gif87a) || bytes.HasPrefix(head, gif89a) {
		return allowed[2], true
	}
	if len(head) >= 12 && bytes.HasPrefix(head, riff) && string(head[8:12]) == "WEBP" {
		return allowed[3], true
	}
	if len(head) >= 12 && string(head[4:8]) == "ftyp" {
		brand := string(head[8:12])
		if brand == "qt  " {
			return allowed[5], true
		}
		if mp4Brands[brand] {
			return allowed[4], true
		}
		return Media{}, false
	}
	// **Lowercased first**, the same way the server reads the DocType; a non-standard `WEBM`
	// must not be accepted by one side and refused by the other.
	if bytes.HasPrefix(head, ebml) && bytes.Contains(bytes.ToLower(head), []byte("webm")) {
		return allowed[6], true
	}
	return Media{}, false
}

// DetectFile reads only the header; it never loads a video into memory.
func DetectFile(path string) (Media, error) {
	file, err := os.Open(path)
	if err != nil {
		return Media{}, fmt.Errorf("读附件：%w", err)
	}
	defer file.Close()
	head, err := io.ReadAll(io.LimitReader(file, headBytes))
	if err != nil {
		return Media{}, fmt.Errorf("读附件：%w", err)
	}
	media, ok := Detect(head)
	if !ok {
		return Media{}, ErrUnsupportedType
	}
	return media, nil
}

// ErrUnsupportedType is the one refusal the caller turns into a sentence naming the allowlist.
var ErrUnsupportedType = fmt.Errorf("不是允许的附件类型")

// SanitizeFilename keeps a display basename only: no path, no control or format characters, at
// most 200 runes. It is never used as an object key.
func SanitizeFilename(name string) string {
	name = strings.ReplaceAll(name, "\\", "/")
	name = filepath.Base(name)
	var cleaned strings.Builder
	for _, r := range name {
		// Format characters (U+202E and friends) are invisible and can reverse the tail of a name.
		if !unicode.IsControl(r) && !unicode.Is(unicode.Cf, r) {
			cleaned.WriteRune(r)
		}
	}
	base := strings.TrimSpace(cleaned.String())
	if base == "" || base == "." || base == "/" {
		return "attachment"
	}
	runes := []rune(base)
	if len(runes) > maxFilenameRunes {
		base = string(runes[:maxFilenameRunes])
	}
	return base
}

// SHA256File is the preview's digest; the server computes its own and that one is the record.
func SHA256File(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", fmt.Errorf("读附件：%w", err)
	}
	defer file.Close()
	digest := sha256.New()
	if _, err := io.Copy(digest, file); err != nil {
		return "", fmt.Errorf("读附件：%w", err)
	}
	return hex.EncodeToString(digest.Sum(nil)), nil
}

// HumanBytes is the size sentence the preview and the error messages use.
func HumanBytes(n int64) string {
	if n >= 1024*1024 {
		return fmt.Sprintf("%.1f MiB", float64(n)/(1024*1024))
	}
	if n >= 1024 {
		return fmt.Sprintf("%.1f KiB", float64(n)/1024)
	}
	return fmt.Sprintf("%d 字节", n)
}
