package feedback

import (
	"bytes"
	"encoding/base64"
	"strings"
	"testing"
)

var pngBytes = mustBase64(
	"iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=")

func mustBase64(encoded string) []byte {
	decoded, err := base64.StdEncoding.DecodeString(encoded)
	if err != nil {
		panic(err)
	}
	return decoded
}

func TestDetectClassifiesTheSevenAllowedTypes(t *testing.T) {
	cases := []struct {
		name string
		head []byte
		want string
	}{
		{"png", pngBytes, "image/png"},
		{"jpeg", []byte{0xFF, 0xD8, 0xFF, 0}, "image/jpeg"},
		{"gif", []byte("GIF89a"), "image/gif"},
		{"webp", webpHeader(), "image/webp"},
		{"mp4", isoHeader("isom"), "video/mp4"},
		{"mov", isoHeader("qt  "), "video/quicktime"},
		{"webm", webmHeader(), "video/webm"},
		{"webm uppercase doctype", bytes.ReplaceAll(webmHeader(), []byte("webm"), []byte("WEBM")),
			"video/webm"},
	}
	for _, testCase := range cases {
		t.Run(testCase.name, func(t *testing.T) {
			media, ok := Detect(testCase.head)
			if !ok || media.Type != testCase.want {
				t.Fatalf("Detect(%s) = %+v, %v; want %s", testCase.name, media, ok, testCase.want)
			}
		})
	}
}

func TestDetectRejectsWhatTheProductDoesNotShow(t *testing.T) {
	for _, head := range [][]byte{
		[]byte("<svg xmlns=\"http://www.w3.org/2000/svg\"/>"),
		isoHeader("heic"),
		[]byte("II*\x00"),
		[]byte("MZ"),
		[]byte("PK\x03\x04"),
	} {
		if media, ok := Detect(head); ok {
			t.Errorf("Detect(%q) accepted %+v", head, media)
		}
	}
}

func TestSanitizeFilenameKeepsADisplayBasename(t *testing.T) {
	cases := map[string]string{
		"../../etc/passwd":                "passwd",
		`C:\Users\me\shot.png`:            "shot.png",
		"a\x00b\x07c.png":                 "abc.png",
		"a‮b.png":                         "ab.png",
		"   ":                             "attachment",
		strings.Repeat("字", 300) + ".png": strings.Repeat("字", 200),
	}
	for input, want := range cases {
		if got := SanitizeFilename(input); got != want {
			t.Errorf("SanitizeFilename(%q) = %q, want %q", input, got, want)
		}
	}
}

func TestHumanBytesUsesTheUnitThePreviewShows(t *testing.T) {
	if got := HumanBytes(68); got != "68 字节" {
		t.Errorf("HumanBytes(68) = %q", got)
	}
	if got := HumanBytes(10 * 1024 * 1024); got != "10.0 MiB" {
		t.Errorf("HumanBytes(10 MiB) = %q", got)
	}
}

func webpHeader() []byte {
	head := make([]byte, 16)
	copy(head, "RIFF")
	copy(head[8:], "WEBP")
	return head
}

func isoHeader(brand string) []byte {
	head := make([]byte, 24)
	head[3] = 24
	copy(head[4:], "ftyp")
	copy(head[8:], brand)
	return head
}

func webmHeader() []byte {
	head := make([]byte, 32)
	copy(head, []byte{0x1A, 0x45, 0xDF, 0xA3})
	copy(head[12:], "webm")
	return head
}
