// Package pins remembers, on this machine, which version of which skill was last invoked.
//
// It is the client half of ADR 0035. The server is still stateless and the pin is still carried by the
// client, exactly as ADR 0012 requires — what changed is *where* on the client it lives. It used to
// travel in the caller's own text, spelled into the address, which meant whoever was asking had to
// reproduce a version name (or, for a version its author left unnamed, 64 hex characters) as part of
// every command. Now `invoke` resolves it once and writes it here, and `read` sends it as a header; no
// version is ever typed by a model again.
//
// What this buys is paid for in visibility: a pin that arrives from a file cannot be seen in the
// request the way a pin written into an address can. So the commands that use this store print the
// version they used or moved, and those lines are not decoration — they are the only thing standing
// between "the pin is wrong" and an answer that is confidently about the wrong version.
//
// **One record per address, in its own file** (v24, ADR 0101). The store used to keep one table per
// server and rewrite it whole on every invoke: two agents invoking *different* skills could lose each
// other's record, and the write showed up in every sandbox as a write. A record is now read and
// written on its own, in place — no temporary file, because a temporary file has to be cleaned up and
// the CLI does not delete (ADR 0101). The trade is recorded there: two concurrent invokes of the *same*
// skill can now interleave and leave a record that does not parse; that is loud on the next read and
// repaired by the next `invoke`.
package pins

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// Pin is the version one address was last invoked at.
//
// **Four fields for two facts**, and the split is ADR 0063's: a version may be addressed by its row
// id (the version code), by its name, or by its digest, and `Kind` records which was used so that a
// later read sends the same spelling. `Code` is kept even when the address used a name, because it
// is what the console shows as 「版本码」 and what a write's address needs — but it never decides
// addressing on its own: an `@1.2.3` pin stays `@1.2.3` even though its row has a code.
//
// `Digest` is always present (the version's identity) and is what a write's response updates; it is
// **not** the If-Match baseline — the write commands read the file list's ETag first, which is the
// digest the server will compare against. Old files have no `Kind`/`Code`: they read back as a name
// or digest pin, which is what they were.
type Pin struct {
	Kind   string `json:"kind,omitempty"` // "code" | "name" | "digest"
	Name   string `json:"name"`
	Digest string `json:"digest"`
	Code   string `json:"code,omitempty"`
}

// Suffix is how this pin is written after the address's `@`.
//
// The digest is returned whole: on the wire an unnamed version's suffix already carries its
// `sha256:` prefix, and prefixing it again produced `sha256:sha256:…` — an address that resolves to
// nothing, which is the failure the pin store exists to prevent.
func (p Pin) Suffix() string {
	switch p.Kind {
	case "code":
		if p.Code != "" {
			return p.Code
		}
	case "name":
		if p.Name != "" {
			return p.Name
		}
	case "digest":
		if p.Digest != "" {
			// One stored form: bare hex. The wire's spelling carries the prefix, and adding it here
			// (rather than at every call site) is what keeps old files with the prefix readable.
			if strings.HasPrefix(p.Digest, "sha256:") {
				return p.Digest
			}
			return "sha256:" + p.Digest
		}
	}
	// No kind: a file written before v7. A name wins over a digest, which is exactly what the old
	// store's precedence was. The digest still gets the wire prefix, whatever a hand-edited file
	// holds.
	if p.Name != "" {
		return p.Name
	}
	if p.Digest != "" && !strings.HasPrefix(p.Digest, "sha256:") {
		return "sha256:" + p.Digest
	}
	return p.Digest
}

// UsesAuthorPlane reports whether this pin is a row id — the only spelling that selects a version
// on the author plane (ADR 0063).
func (p Pin) UsesAuthorPlane() bool {
	return p.Kind == "code" || (p.Kind == "" && p.Code != "" && p.Name == "" && p.Digest == "")
}

// record is what one file holds: the address it belongs to, beside the pin.
//
// **The address is inside the file, not only in its name**, because the name is a hash. Reading it
// back is how a record that belongs to some other address — a hand copy, a collision — is refused
// instead of being served as this address's version. The fields of `Pin` are inlined, so the file
// stays one flat object.
type record struct {
	Address string `json:"address"`
	Pin
}

// Store is one server's pins: one file per address, inside a directory.
//
// **`legacy` is read-only, forever** (ADR 0101). It names the pre-v24 whole-table file; it is
// consulted only when the address has no record of its own yet, so upgrading does not lose a pin. It
// is never written and never deleted.
type Store struct {
	dir    string
	legacy string
}

// Open names the store. Nothing is read or created until one of the methods below runs.
//
// `dir` is the directory that will hold the records; `legacy` is the pre-v24 table, or "" when there
// is none.
func Open(dir, legacy string) *Store {
	return &Store{dir: dir, legacy: legacy}
}

// path is the record for one bare address.
//
// The address is hashed rather than used as a filename: it contains a slash, may contain non-ASCII,
// and macOS's filesystem is case-insensitive by default — a name built from it would collide one day.
// Sixteen hex characters of SHA-256 is far past the point where a collision between the addresses one
// machine invokes is a thing that happens; if one ever did, the address inside the file turns it into
// a refusal rather than a wrong pin.
func (s *Store) path(address string) string {
	sum := sha256.Sum256([]byte(address))
	return filepath.Join(s.dir, fmt.Sprintf("%x.json", sum[:8]))
}

// Lookup is the version this address was last invoked at, or false when there is none.
//
// The two failures are kept apart the way the credential store keeps them, and for the same reason: a
// missing file means "nothing remembered", while a file that exists and does not parse is an error.
// Treating a corrupt file as "nothing remembered" would quietly send the next read to whatever is
// current — a different version — and leave the broken file in place to do it again.
func (s *Store) Lookup(address string) (Pin, bool, error) {
	pin, found, err := s.readNew(address)
	if err != nil {
		return Pin{}, false, err
	}
	if found {
		return pin, true, nil
	}
	return s.readLegacy(address)
}

// Remember records one address's version, and returns whatever it displaced.
//
// The displaced value comes back from here rather than from a separate Lookup because the caller has
// exactly one use for it: `invoke` re-resolves on every call, and when the answer differs from what
// was remembered it has to say so. A pin that moves silently mid-task is the failure this whole design
// is arranged around: the body a model is holding would describe one version while the files it reads
// come from another, and nothing would report it.
//
// **The same version is not written again — when the record of its own already says so.** An `invoke`
// that resolves to the version already on file changes nothing on disk, which is what keeps a
// repeated read out of a sandbox's write approval (v24 PRD 做 #4).
//
// **A pin known only to the pre-v24 table is still written.** That is the migration, and it is not a
// rewrite: the address has no record of its own yet, so the first `invoke` that touches it moves the
// pin into the new store even when the version has not moved. Without that, a machine whose skills
// are all on their pinned version would keep reading the whole-table file forever, and the upgrade
// would never actually happen.
//
// **A record that will not parse is overwritten rather than reported** — this is the one path where a
// corrupt file is repaired instead of fatal. The new pin is authoritative for the address, so nothing
// is being guessed; `Lookup` keeps reporting the corruption until the overwrite happens.
func (s *Store) Remember(address string, pin Pin) (Pin, bool, error) {
	previous, had, fromRecord, err := s.readForWrite(address)
	if err != nil {
		return Pin{}, false, err
	}
	if had && fromRecord && previous == pin {
		return previous, true, nil
	}
	if err := s.write(address, pin); err != nil {
		return Pin{}, false, err
	}
	return previous, had, nil
}

// readNew reads this address's own record. `found` is false when the file is not there.
func (s *Store) readNew(address string) (Pin, bool, error) {
	path := s.path(address)
	blob, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return Pin{}, false, nil
	}
	if err != nil {
		return Pin{}, false, fmt.Errorf("读取 %s：%w", path, err)
	}
	var rec record
	if err := json.Unmarshal(blob, &rec); err != nil {
		return Pin{}, false, &CorruptError{Path: path, Err: err}
	}
	// **A document that parses and says nothing is not somebody else's record.** `null` and `{}` both
	// unmarshal into a zero value without an error; read as a mismatch, the empty address would name
	// nobody and the file could never be repaired (`Remember` refuses to overwrite a mismatch).
	if rec.Address == "" {
		return Pin{}, false, &CorruptError{Path: path, Err: errors.New("记录里没有 address")}
	}
	if rec.Address != address {
		return Pin{}, false, &MismatchError{Path: path, Address: rec.Address, Wanted: address}
	}
	// **A record with no version is not a pin.** Its `Suffix()` would be empty, and an empty version
	// header is exactly "whatever is current" — the silent substitution ADR 0035 exists to prevent.
	if rec.Pin.Suffix() == "" {
		return Pin{}, false, &CorruptError{Path: path, Err: errors.New("记录里没有版本")}
	}
	return rec.Pin, true, nil
}

// readForWrite is readNew with the repair rule: a record that will not parse counts as absent here,
// because the write that follows replaces it. A record belonging to another address is still refused —
// overwriting it would destroy that address's pin, and a mismatch is a state nobody intended, not one
// to paper over.
//
// **`fromRecord` is what tells Remember whether "the same version" means a record that already says
// so.** A value that came from the pre-v24 table has had no record written for it yet, so the caller
// must write one even when the pin has not moved — that write is the migration. A corrupt legacy
// table counts as absent here for the same reason a corrupt record does: the write is the repair.
func (s *Store) readForWrite(address string) (previous Pin, had, fromRecord bool, err error) {
	pin, found, err := s.readNew(address)
	switch {
	case err == nil && found:
		return pin, true, true, nil
	case err == nil:
		previous, had, legacyErr := s.readLegacy(address)
		if legacyErr != nil {
			var corrupt *CorruptError
			if errors.As(legacyErr, &corrupt) {
				return Pin{}, false, false, nil
			}
			return Pin{}, false, false, legacyErr
		}
		return previous, had, false, nil
	}
	var corrupt *CorruptError
	if errors.As(err, &corrupt) {
		return Pin{}, false, false, nil
	}
	return Pin{}, false, false, err
}

// readLegacy answers from the pre-v24 whole-table file, which is never written.
//
// A missing file is "nothing remembered". A file that exists and does not parse is the same error a
// corrupt record is — the caller's repair is the same, and staying silent about it would send a read
// to the wrong version.
func (s *Store) readLegacy(address string) (Pin, bool, error) {
	if s.legacy == "" {
		return Pin{}, false, nil
	}
	blob, err := os.ReadFile(s.legacy)
	if errors.Is(err, fs.ErrNotExist) {
		return Pin{}, false, nil
	}
	if err != nil {
		return Pin{}, false, fmt.Errorf("读取 %s：%w", s.legacy, err)
	}
	all := map[string]Pin{}
	if err := json.Unmarshal(blob, &all); err != nil {
		return Pin{}, false, &CorruptError{Path: s.legacy, Err: err}
	}
	if all == nil {
		// **A `null` document, and it is not a harmless empty one.** `null` is valid JSON, and
		// unmarshalling it into a map leaves the map *nil* rather than empty — no error, so it would
		// slip past the check above. Read as "no pins", the next `read` would answer about whatever is
		// current, which is the silent substitution this store exists to prevent. A file that is not a
		// set of records is the same answer as any other file that is not one.
		return Pin{}, false, &CorruptError{Path: s.legacy, Err: errors.New("文件里是 null，不是一份记录")}
	}
	pin, found := all[address]
	if !found {
		return Pin{}, false, nil
	}
	// The same rule as `readNew`: a row with no version would send an empty version header.
	if pin.Suffix() == "" {
		return Pin{}, false, &CorruptError{Path: s.legacy,
			Err: fmt.Errorf("%s 的记录里没有版本", address)}
	}
	return pin, true, nil
}

// write replaces this address's record, in place.
//
// **No temporary file, and that is the point** (ADR 0101): a temporary file would have to be renamed
// into place and cleaned up, and the CLI does not delete. The record is small, the write is one
// `OpenFile` with `O_TRUNC`, and a torn write is a parse error that the next `invoke` repairs by
// overwriting — loud, and repaired by the ordinary path.
func (s *Store) write(address string, pin Pin) error {
	blob, err := json.Marshal(record{Address: address, Pin: pin})
	if err != nil {
		return fmt.Errorf("编码 %s：%w", s.path(address), err)
	}
	if err := os.MkdirAll(s.dir, 0o700); err != nil {
		return fmt.Errorf("创建 %s：%w", s.dir, err)
	}
	path := s.path(address)
	file, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return fmt.Errorf("写 %s：%w", path, err)
	}
	// 0600 on the file and 0700 on the directory: not because a pin is a secret, but because nothing
	// in this directory is anybody else's business, and a rule with exceptions is one that gets
	// forgotten. The mode argument above applies only when the file is created; the explicit chmod
	// keeps an existing file at 0600 whatever the umask once said.
	if err := file.Chmod(0o600); err != nil {
		file.Close()
		return fmt.Errorf("设置 %s 的权限：%w", path, err)
	}
	if _, err := file.Write(blob); err != nil {
		file.Close()
		return fmt.Errorf("写 %s：%w", path, err)
	}
	if err := file.Close(); err != nil {
		return fmt.Errorf("关闭 %s：%w", path, err)
	}
	return nil
}

// CorruptError is a record that is there and is not one.
//
// **Its own type because of what callers say about it.** The only repair is to remove the file — it
// is a cache of version names and digests, and the next write rebuilds it — while the store's
// *other* failure mode is I/O (a directory somebody else owns, a full disk), where the file is
// perfectly good and that advice sends the operator to fix the wrong thing. Without a way to tell them
// apart, one message has to serve both and is wrong for one of them.
type CorruptError struct {
	Path string
	Err  error
}

func (e *CorruptError) Error() string {
	return fmt.Sprintf("解析 %s：%v", e.Path, e.Err)
}

func (e *CorruptError) Unwrap() error { return e.Err }

// MismatchError is a record that parses and belongs to some other address.
//
// **Neither a corrupt file nor an I/O failure.** The file is fine and it is somebody else's; serving
// its pin would answer about a version this address was never at, and overwriting it would erase the
// other address's pin. The only safe answer is to refuse and say whose file it is. In practice this
// means a hand-edited or hand-copied file; the address inside the record is what makes it visible
// instead of a wrong answer.
type MismatchError struct {
	Path    string
	Address string // what the record says it belongs to
	Wanted  string // the address that was asked for
}

func (e *MismatchError) Error() string {
	return fmt.Sprintf("%s 里的记录属于 %s，不是 %s", e.Path, e.Address, e.Wanted)
}
