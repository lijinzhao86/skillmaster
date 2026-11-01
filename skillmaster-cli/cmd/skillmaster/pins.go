// The local version pin store.

package main

import (
	"errors"
	"fmt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/pins"
	"strings"
)

// pinsFor opens this machine's pin store for one server.
//
// Since v24 that is a directory of one record per skill, plus the pre-v24 whole-table file as a
// read-only fallback (ADR 0101). Both are named here so the store itself stays two paths and no rules.
func pinsFor(server string) (*pins.Store, error) {
	dir, err := config.PinDir(server)
	if err != nil {
		return nil, fmt.Errorf("找不到用户配置目录：%w", err)
	}
	legacy, err := config.PinPath(server)
	if err != nil {
		return nil, fmt.Errorf("找不到用户配置目录：%w", err)
	}
	return pins.Open(dir, legacy), nil
}

// pinsUnreadable rewrites a failure to read or write this machine's pin record.
//
// **What makes it worth its own message is what the file holds**: version names and digests, nothing
// else, so deleting it costs nothing a later `invoke` cannot rebuild — and saying so is the difference
// between a command that fails with a next step and one that fails forever with a path in it. Since
// v24 there is one record per skill, so the message names that one file rather than "the pin file".
//
// **What must not happen is the other repair.** Treating an unreadable file as "no pin" would answer
// about whatever is current while the caller believes they are reading the version they invoked,
// which is the silent substitution this whole design is arranged around (ADR 0035 §后果). `Remember`
// is the one exception — it overwrites a corrupt record with the version just resolved, because that
// version is the answer, not a guess — and it is in the store, not here.
func pinsUnreadable(err error) error {
	var corrupt *pins.CorruptError
	if errors.As(err, &corrupt) {
		return fmt.Errorf("%w\n（这个文件里只有版本名与摘要——整张旧表和一个 skill 的记录都是如此，"+
			"删掉它是安全的：下一次 invoke 会重建它。"+
			"在它读得出来之前，read 与 ls 不会替你猜一个版本。）", err)
	}
	var mismatch *pins.MismatchError
	if errors.As(err, &mismatch) {
		// **A mismatch is not corrupt, and saying "delete it" without saying why would be worse.**
		// The record is somebody else's; `Remember` will not overwrite it, so deleting it is the only
		// repair — and until then the address has no pin rather than the wrong one.
		return fmt.Errorf("%w\n（这个文件里的记录属于另一个地址；删掉它之后，下一次 invoke 会按当前地址"+
			"重建。在它被清掉之前，read 与 ls 不会用它来回答。）", err)
	}
	// Every other way this store fails is I/O — a directory that belongs to somebody else, a full
	// disk — and there the file is fine. "Delete it" would send the operator to repair the wrong
	// thing, so this one goes through as it came, with its own path in it.
	return err
}

// consumerPin is how a loaded version is remembered: by name when the author gave one, by digest
// otherwise. **One definition, because `invoke` and `startup` must agree on what "the version I
// loaded" means** — two spellings would let the journey's `skill read` resolve a different pin than
// the one `startup` wrote, and the traveller would read step three of a version they are not on.
func consumerPin(name, digest string) pins.Pin {
	if name != "" {
		return pins.Pin{Kind: "name", Name: name, Digest: strings.TrimPrefix(digest, "sha256:")}
	}
	return pins.Pin{Kind: "digest", Digest: strings.TrimPrefix(digest, "sha256:")}
}

// rememberPin writes a resolved version down, returning whatever was there before.
func rememberPin(address string, pin pins.Pin) (pins.Pin, bool, error) {
	store, err := pinsFor(config.Server())
	if err != nil {
		return pins.Pin{}, false, err
	}
	previous, had, err := store.Remember(address, pin)
	if err != nil {
		return pins.Pin{}, false, pinsUnreadable(err)
	}
	return previous, had, nil
}

// rememberedPin is the version this address was last invoked at, if any.
func rememberedPin(address string) (pins.Pin, bool, error) {
	store, err := pinsFor(config.Server())
	if err != nil {
		return pins.Pin{}, false, err
	}
	pin, found, err := store.Lookup(address)
	if err != nil {
		return pins.Pin{}, false, pinsUnreadable(err)
	}
	return pin, found, nil
}
