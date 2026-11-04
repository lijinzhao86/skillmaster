// The "you have seen this file" record behind `write --overwrite` (ADR 0136).

package main

import (
	"context"
	"fmt"
	"os"
	"path/filepath"
	"time"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
)

func readsFor(server string) (*pins.Reads, error) {
	dir, err := config.PinDir(server)
	if err != nil {
		return nil, err
	}
	return pins.OpenReads(filepath.Join(dir, "reads")), nil
}

// recordFullRead notes that this machine has seen a draft file in full, with the version digest it
// saw. A failure is a note, not a failed read: the read succeeded, and a lost note costs one extra
// `read` before an overwrite.
//
// Only draft pins are recorded: a published version cannot be written, so there is nothing to guard.
func recordFullRead(ctx context.Context, address, relpath string) {
	pin, _, err := pinFor(address)
	if err != nil || pin == "" || !isDraftPin(pin) {
		return
	}
	key := bareAddress(address)
	client, err := session(ctx)
	if err != nil {
		return
	}
	listed, err := client.AuthorFileList(ctx, key, pin)
	if err != nil {
		return
	}
	reads, err := readsFor(config.Server())
	if err != nil {
		return
	}
	if err := reads.Record(key, pin, relpath, listed.ETag,
		time.Now().UTC().Format(time.RFC3339)); err != nil {
		fmt.Fprintf(os.Stderr, "（没能记下这次读取：%v；下次整份覆盖前要重新读一遍。）\n", err)
	}
}

// readProofRefusal is the overwrite guard: the sentence when this machine has no proof for the
// current version of the file, or "" when the overwrite may proceed.
func readProofRefusal(address, pin, path, etag string) string {
	reads, err := readsFor(config.Server())
	if err != nil {
		return fmt.Sprintf("读不到本机的读记录：%v。\n先 skmr skill read %s@%s %s 看一眼再覆盖。",
			err, address, pin, path)
	}
	proof, found, err := reads.Lookup(address, pin, path)
	if err != nil {
		return fmt.Sprintf("本机的读记录坏了：%v。\n先 skmr skill read %s@%s %s 看一眼再覆盖。",
			err, address, pin, path)
	}
	if !found || proof.ETag != etag {
		return fmt.Sprintf("这个版本已经有 %s；你还没读过它，或读过之后这一版变过。\n"+
			"先看一眼：skmr skill read %s@%s %s\n"+
			"以后确实要整份覆盖，再带 --overwrite 跑一次。",
			path, address, pin, path)
	}
	return ""
}
