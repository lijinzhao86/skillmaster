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

// recordFullRead notes that this machine has seen a draft file in full, with the content hash it
// saw. A failure is a note, not a failed read: the read succeeded, and a lost note costs one extra
// `read` before an overwrite.
//
// **The hash comes from the listing, not from the bytes just printed.** The overwrite compares
// against the same listing, so the two sides have to read one source; hashing locally would be a
// second definition that could disagree after the server's text normalisation.
//
// A path that is not in the listing (or carries no hash) records nothing: there is no current
// content to have seen.
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
	sha256 := ""
	for _, file := range listed.Files {
		if file.Relpath == relpath {
			sha256 = file.SHA256
			break
		}
	}
	if sha256 == "" {
		return
	}
	reads, err := readsFor(config.Server())
	if err != nil {
		return
	}
	if err := reads.Record(key, pin, relpath, sha256,
		time.Now().UTC().Format(time.RFC3339)); err != nil {
		fmt.Fprintf(os.Stderr, "（没能记下这次读取：%v；下次整份覆盖前要重新读一遍。）\n", err)
	}
}

// recordWriteProof notes that this machine has just put this content there.
//
// **A write is a read of what the caller supplied.** After a successful PUT the server holds the
// bytes the caller just sent, and the batch's own next step is "rerun the same command" — that rerun
// meets files this machine wrote in the previous attempt. Without this record those files would be
// refused as "you never read this", which is exactly what the batch message tells the caller to do.
// The hash comes from the listing taken after the write, the same source the guard compares against.
func recordWriteProof(address, pin, relpath, sha256 string) {
	if pin == "" || sha256 == "" || !isDraftPin(pin) {
		return
	}
	reads, err := readsFor(config.Server())
	if err != nil {
		return
	}
	if err := reads.Record(bareAddress(address), pin, relpath, sha256,
		time.Now().UTC().Format(time.RFC3339)); err != nil {
		fmt.Fprintf(os.Stderr, "（没能记下这次写入：%v；下次整份覆盖前要重新读一遍。）\n", err)
	}
}

// readProofRefusal is the overwrite guard: the sentence when this machine has no proof for the
// current content of the file, or "" when the overwrite may proceed.
//
// `sha256` is the file's hash in the listing the write is about to use as its baseline — the same
// value recordFullRead stored. A sibling file having changed does not move it (ADR 0177).
//
// **Empty means refused, on either side.** A server that sends no hash, or a record written before
// v57 (which only had `etag`), must fail closed: "no proof" is the safe answer, and the guard exists
// to stop the write, not to let it through on a field nobody filled in.
func readProofRefusal(address, pin, path, sha256 string) string {
	if sha256 == "" {
		return fmt.Sprintf("这个版本已经有 %s，但清单里没有它的内容摘要，没法确认你读过的是当前这份。\n"+
			"先看一眼：skmr skill read %s@%s %s", path, address, pin, path)
	}
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
	if !found || proof.SHA256 == "" || proof.SHA256 != sha256 {
		return fmt.Sprintf("这个版本已经有 %s；你还没读过它，或读过之后它被改过。\n"+
			"先看一眼：skmr skill read %s@%s %s\n"+
			"以后确实要整份覆盖，再带 --overwrite 跑一次。",
			path, address, pin, path)
	}
	return ""
}
