// get: fetch one file to a local path, streaming.

package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/api"
)

// The same budget the archive path uses: a 100 MiB body on a slow link is not a hung one.
const getTimeout = 10 * time.Minute

type getOptions struct {
	out       string
	overwrite bool
}

func parseGetFlags(args []string) (getOptions, []string, error) {
	var opts getOptions
	var rest []string
	for index := 0; index < len(args); index++ {
		switch args[index] {
		case "-o":
			if index+1 >= len(args) {
				return opts, nil, usagef("-o 后面要跟本地路径（用 - 表示标准输出）。%s",
					usageAt("skill get"))
			}
			opts.out = args[index+1]
			index++
		case "--overwrite":
			opts.overwrite = true
		default:
			if strings.HasPrefix(args[index], "-") && args[index] != "-" {
				return opts, nil, unknownFlag("skill get", args[index])
			}
			rest = append(rest, args[index])
		}
	}
	return opts, rest, nil
}

// get writes one file to disk. `cat` is the same bytes on stdout; `download` is the whole version.
//
// The transfer goes to a temporary file beside the destination and is renamed only after it has
// arrived, so an interrupted download never replaces a good file with half of another one.
func get(ctx context.Context, args []string) error {
	opts, rest, err := parseGetFlags(args)
	if err != nil {
		return err
	}
	if len(rest) != 2 {
		return usagef("要接地址和相对路径。%s", usageAt("skill get"))
	}
	if err := checkAddressSyntax("skill get", rest[0]); err != nil {
		return err
	}
	address, relpath := rest[0], rest[1]

	// A body that answers 200 and then stalls must not hang forever; archive after the same budget.
	ctx, cancel := context.WithTimeout(ctx, getTimeout)
	defer cancel()

	target, err := authenticated(ctx, func(client api.Client) (fileTarget, error) {
		return resolve(ctx, client, address, false)
	})
	if err != nil {
		return addressRefusal(err)
	}
	stream, err := authenticated(ctx, func(client api.Client) (io.ReadCloser, error) {
		return openFile(ctx, client, target, relpath)
	})
	if err != nil {
		return fileRefusal(err, relpath)
	}
	defer stream.Close()

	if opts.out == "-" {
		if _, err := io.Copy(os.Stdout, stream); err != nil {
			// The reader closed the pipe (`| head`): the download did its job.
			if isBrokenPipe(err) {
				return nil
			}
			return fmt.Errorf("写标准输出：%w", err)
		}
		printNote(target)
		return nil
	}

	destination := opts.out
	if destination == "" {
		destination = filepath.Base(relpath)
	}
	if info, statErr := os.Stat(destination); statErr == nil && info.IsDir() {
		destination = filepath.Join(destination, filepath.Base(relpath))
	}
	if _, statErr := os.Stat(destination); statErr == nil && !opts.overwrite {
		return fmt.Errorf("本地已经有 %s；覆盖加 --overwrite。文件没有下载。", destination)
	}

	temp, err := os.CreateTemp(filepath.Dir(destination), "."+filepath.Base(destination)+".part-")
	if err != nil {
		return fmt.Errorf("创建临时文件：%w", err)
	}
	tempName := temp.Name()

	digest := sha256.New()
	if _, err := io.Copy(io.MultiWriter(temp, digest), stream); err != nil {
		temp.Close()
		// The CLI does not delete (ADR 0101). Say where the half-written file is rather than
		// pretending it is not there.
		return fmt.Errorf("下载 %s：%w\n（这次写下的半成品留在 %s，没有删掉——确认后请自行删除。）",
			relpath, err, tempName)
	}
	if err := temp.Close(); err != nil {
		return fmt.Errorf("写入 %s：%w\n（半成品留在 %s，没有删掉——确认后请自行删除。）",
			destination, err, tempName)
	}
	if err := os.Rename(tempName, destination); err != nil {
		return fmt.Errorf("保存到 %s：%w\n（下载完成的内容留在 %s，没有删掉——确认后请自行删除。）",
			destination, err, tempName)
	}

	fmt.Fprintf(os.Stderr, "已保存到 %s（sha256:%s）。\n", destination,
		hex.EncodeToString(digest.Sum(nil)))
	printNote(target)
	return nil
}

// fileRefusal keeps the one file-shaped answer a caller can act on.
func fileRefusal(err error, relpath string) error {
	var server *api.ServerError
	if errors.As(err, &server) && server.Code == "file_not_found" {
		return fmt.Errorf("这个版本没有 %s；用 skmr skill ls 看清单。%w", relpath, err)
	}
	return err
}
