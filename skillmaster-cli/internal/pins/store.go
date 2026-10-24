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
package pins

import (
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

// Store is one server's pin file: `namespace/name` mapped to the version it was last invoked at.
//
// **One file per server, and it is not secret.** Nothing here is a credential — a version name and a
// digest — so the reason `config.PinPath` still hashes the server is not confidentiality: it is that a
// shared file would let one server's pin be sent to another, where it resolves to nothing or, if the
// names happen to collide, to some other skill's version. A wrong answer arriving without a complaint
// is the failure this project spends the most effort on, and one hash avoids a class of it.
//
// There is no locking. Two `invoke`s of the same skill from two agents on one machine are the case
// this design does not solve: the file holds one version per address, so the later one wins and the
// earlier one's next `read` follows the other agent's pin. That is rare, and it is visible — every
// command that uses the store says which version it used.
type Store struct {
	path string
}

// Open names the file to use. Nothing is read or created until one of the methods below runs.
func Open(path string) *Store {
	return &Store{path: path}
}

// Lookup is the version this address was last invoked at, or false when there is none.
//
// The two failures are kept apart the way the credential store keeps them, and for the same reason: a
// missing file means "nothing remembered", while a file that exists and does not parse is an error.
// Treating a corrupt file as "nothing remembered" would quietly send the next read to whatever is
// current — a different version — and leave the broken file in place to do it again.
func (s *Store) Lookup(address string) (Pin, bool, error) {
	all, err := s.load()
	if err != nil {
		return Pin{}, false, err
	}
	pin, found := all[address]
	return pin, found, nil
}

// Remember records one address's version, and returns whatever it displaced.
//
// The displaced value comes back from here rather than from a separate Lookup because the caller has
// exactly one use for it: `invoke` re-resolves on every call, and when the answer differs from what
// was remembered it has to say so. A pin that moves silently mid-task is the failure this whole design
// is arranged around — the body a model is holding would describe one version while the files it reads
// come from another, and nothing would report it.
func (s *Store) Remember(address string, pin Pin) (Pin, bool, error) {
	all, err := s.load()
	if err != nil {
		return Pin{}, false, err
	}
	previous, had := all[address]
	all[address] = pin
	if err := s.save(all); err != nil {
		return Pin{}, false, err
	}
	return previous, had, nil
}

// CorruptError is a store file that is there and is not a pins file.
//
// **Its own type because of what callers say about it.** The only repair for this one is to remove the
// file — it is a cache of version names and digests, and the next write rebuilds it — while the store's
// *other* failure mode is I/O (a directory somebody else owns, a full disk), where the file is
// perfectly good and that advice sends the operator to fix the wrong thing. Without a way to tell them
// apart, a caller can only say something true of half its failures.
type CorruptError struct {
	Path string
	Err  error
}

func (e *CorruptError) Error() string {
	return fmt.Sprintf("解析 %s：%v", e.Path, e.Err)
}

func (e *CorruptError) Unwrap() error { return e.Err }

// load reads the whole map, treating "no file" as an empty one.
//
// The map is read and rewritten whole because that is what the file is: one small document, not a
// table. Sizing that up would be work for a case that does not exist — a machine has as many entries
// as it has skills somebody invoked.
func (s *Store) load() (map[string]Pin, error) {
	blob, err := os.ReadFile(s.path)
	if errors.Is(err, fs.ErrNotExist) {
		return map[string]Pin{}, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读取 %s：%w", s.path, err)
	}
	all := map[string]Pin{}
	if err := json.Unmarshal(blob, &all); err != nil {
		return nil, &CorruptError{Path: s.path, Err: err}
	}
	if all == nil {
		// **A `null` document, and it is not a harmless empty one.** `null` is valid JSON, and
		// unmarshalling it into a map leaves the map *nil* rather than empty — no error, so it would
		// slip past the check above. Read as "no pins", the next `read` would answer about whatever is
		// current, which is the silent substitution this store exists to prevent; and the next write
		// would be an assignment into a nil map, which panics. A file that is not a set of records is
		// the same answer as any other file that is not one.
		return nil, &CorruptError{Path: s.path, Err: errors.New("文件里是 null，不是一份记录")}
	}
	return all, nil
}

// save replaces the file, whole and atomically.
//
// Written to a neighbour and renamed, the same way the credential fallback is: an interrupted write
// must not leave half a document where a set of pins used to be, because the next run would then fail
// to parse it — and, per Lookup, failing to parse is an error rather than an empty store.
func (s *Store) save(all map[string]Pin) error {
	blob, err := json.Marshal(all)
	if err != nil {
		return fmt.Errorf("编码 %s：%w", s.path, err)
	}
	dir := filepath.Dir(s.path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return fmt.Errorf("创建 %s：%w", dir, err)
	}

	tmp, err := os.CreateTemp(dir, ".pins-*")
	if err != nil {
		return fmt.Errorf("在 %s 里写临时文件：%w", dir, err)
	}
	tmpName := tmp.Name()
	defer func() {
		// Only reached when something below returned early; after a successful rename the name is
		// gone and this is a no-op.
		_ = os.Remove(tmpName)
	}()

	// 0600 on the file and 0700 on the directory: not because a pin is a secret, but because nothing
	// in this directory is anybody else's business, and a rule with exceptions is one that gets
	// forgotten. `os.CreateTemp` already makes it 0600; the explicit chmod keeps that true if the
	// process umask ever says otherwise.
	if err := tmp.Chmod(0o600); err != nil {
		tmp.Close()
		return fmt.Errorf("设置 %s 的权限：%w", tmpName, err)
	}
	if _, err := tmp.Write(blob); err != nil {
		tmp.Close()
		return fmt.Errorf("写 %s：%w", tmpName, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("关闭 %s：%w", tmpName, err)
	}
	if err := os.Rename(tmpName, s.path); err != nil {
		return fmt.Errorf("替换 %s：%w", s.path, err)
	}
	return nil
}
