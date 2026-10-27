package updater

import (
	"archive/tar"
	"archive/zip"
	"bytes"
	"compress/gzip"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Limits. They are not paranoia about the mirror: they are what keeps a wrong address (a login
// page, an HTML error page with a 200) from being streamed into memory until the machine dies.
const (
	maxMarkerBytes    = 4 << 10
	maxChecksumsBytes = 256 << 10
	maxArchiveBytes   = 32 << 20
	maxBinaryBytes    = 64 << 20
)

// CheckTimeout bounds the check that every command may pay for.
const CheckTimeout = 1500 * time.Millisecond

// Check asks the channel what it serves.
func Check(ctx context.Context, base string, client *http.Client) (Release, error) {
	body, err := get(ctx, client, base+"/version.txt", maxMarkerBytes)
	if err != nil {
		return Release{}, err
	}
	return ParseRelease(string(body))
}

// Fetch downloads, verifies and stages the binary, and returns the staged path.
//
// **Nothing touches the disk until everything has been checked.** The archive is held in memory,
// its SHA-256 compared with `checksums.txt`, and the executable extracted from it in memory too; the
// staged file is one `Write`. A failure therefore leaves no half-file to explain later — which
// matters more than the 8 MB the memory costs.
func Fetch(ctx context.Context, base string, release Release, dir, goos, goarch string, client *http.Client) (string, error) {
	asset := AssetName(goos, goarch)
	checksums, err := get(ctx, client, base+"/checksums.txt", maxChecksumsBytes)
	if err != nil {
		return "", err
	}
	want, ok := checksumOf(checksums, asset)
	if !ok {
		return "", &VerifyError{Err: fmt.Errorf("校验和文件里没有 %s", asset)}
	}
	archive, err := get(ctx, client, base+"/"+asset, maxArchiveBytes)
	if err != nil {
		return "", err
	}
	sum := sha256.Sum256(archive)
	if got := hex.EncodeToString(sum[:]); got != want {
		return "", &VerifyError{Err: fmt.Errorf("%s 的校验和不符（期望 %s，实际 %s）", asset, want, got)}
	}
	binary, err := extractBinary(archive, goos)
	if err != nil {
		return "", &VerifyError{Err: err}
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", fmt.Errorf("创建 %s：%w", dir, err)
	}
	staged := StagedPath(filepath.Join(dir, BinaryName(goos)), release, goos)
	file, err := os.OpenFile(staged, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o755)
	if err != nil {
		return "", fmt.Errorf("写 %s：%w", staged, err)
	}
	if _, err := file.Write(binary); err != nil {
		file.Close()
		return "", fmt.Errorf("写 %s：%w", staged, err)
	}
	if err := file.Close(); err != nil {
		return "", fmt.Errorf("关闭 %s：%w", staged, err)
	}
	return staged, nil
}

// checksumOf finds one file's line in `checksums.txt` (`<hex>  <name>`), matching on the base name
// because the file is published flat.
func checksumOf(checksums []byte, asset string) (string, bool) {
	for _, line := range strings.Split(string(checksums), "\n") {
		fields := strings.Fields(line)
		if len(fields) < 2 {
			continue
		}
		if filepath.Base(fields[len(fields)-1]) == asset {
			return fields[0], true
		}
	}
	return "", false
}

// extractBinary pulls the executable out of the archive.
func extractBinary(archive []byte, goos string) ([]byte, error) {
	want := BinaryName(goos)
	if goos == "windows" {
		reader, err := zip.NewReader(bytes.NewReader(archive), int64(len(archive)))
		if err != nil {
			return nil, fmt.Errorf("读 zip：%w", err)
		}
		for _, entry := range reader.File {
			if filepath.Base(entry.Name) != want {
				continue
			}
			file, err := entry.Open()
			if err != nil {
				return nil, fmt.Errorf("读 %s：%w", entry.Name, err)
			}
			defer file.Close()
			return readLimited(file, maxBinaryBytes)
		}
		return nil, fmt.Errorf("压缩包里没有 %s", want)
	}
	gz, err := gzip.NewReader(bytes.NewReader(archive))
	if err != nil {
		return nil, fmt.Errorf("解 gzip：%w", err)
	}
	defer gz.Close()
	tarReader := tar.NewReader(gz)
	for {
		header, err := tarReader.Next()
		if err == io.EOF {
			break
		}
		if err != nil {
			return nil, fmt.Errorf("读 tar：%w", err)
		}
		if header.Typeflag != tar.TypeReg || filepath.Base(header.Name) != want {
			continue
		}
		return readLimited(tarReader, maxBinaryBytes)
	}
	return nil, fmt.Errorf("压缩包里没有 %s", want)
}

// get reads one small object.
func get(ctx context.Context, client *http.Client, url string, limit int64) ([]byte, error) {
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return nil, err
	}
	response, err := client.Do(request)
	if err != nil {
		return nil, err
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("%s 返回 HTTP %d", url, response.StatusCode)
	}
	return readLimited(response.Body, limit)
}

func readLimited(reader io.Reader, limit int64) ([]byte, error) {
	body, err := io.ReadAll(io.LimitReader(reader, limit+1))
	if err != nil {
		return nil, err
	}
	if int64(len(body)) > limit {
		return nil, fmt.Errorf("内容超过 %d 字节的上限", limit)
	}
	return body, nil
}
