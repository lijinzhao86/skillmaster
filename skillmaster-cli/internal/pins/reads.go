// The local record of "this file was read, at this version digest".
//
// It exists for one guard: `write --overwrite` refuses to replace a file the caller has not seen
// (ADR 0136). It is deliberately **not** part of the pin record: a read may name an explicit
// version without ever invoking one, and storing it beside the pin would mean inventing a pin the
// caller never set. One file per address in a `reads/` directory beside the pins keeps the two
// facts apart.
package pins

import (
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
)

// maxReadProofs bounds one address's record. It is a cache, not a history: the oldest entries go
// first once the limit is reached.
const maxReadProofs = 200

// ReadProof is one file read at one version digest.
type ReadProof struct {
	ETag string `json:"etag"`
	At   string `json:"at"`
}

// readsRecord is the shape of one address's file.
type readsRecord struct {
	Address string                          `json:"address"`
	Reads   map[string]map[string]ReadProof `json:"reads"`
}

// Reads is one server's read proofs: one file per address, in place, no temporary files (ADR 0101).
type Reads struct {
	dir string
}

// OpenReads names the directory. Nothing is read or created until one of the methods runs.
func OpenReads(dir string) *Reads {
	return &Reads{dir: dir}
}

func (s *Reads) path(address string) string {
	sum := sha256.Sum256([]byte(address))
	return filepath.Join(s.dir, fmt.Sprintf("%x.json", sum[:8]))
}

// Lookup reports the proof for one (address, pin, path), if this machine recorded one.
func (s *Reads) Lookup(address, pin, path string) (ReadProof, bool, error) {
	rec, err := s.read(address)
	if err != nil {
		return ReadProof{}, false, err
	}
	if rec == nil {
		return ReadProof{}, false, nil
	}
	proof, found := rec.Reads[pin][path]
	return proof, found, nil
}

// Record writes one proof, repairing a corrupt file the way `Remember` repairs a corrupt pin.
func (s *Reads) Record(address, pin, path, etag, at string) error {
	rec, err := s.read(address)
	if err != nil {
		var corrupt *CorruptError
		if !errors.As(err, &corrupt) {
			return err
		}
		rec = nil
	}
	if rec == nil {
		rec = &readsRecord{Address: address, Reads: map[string]map[string]ReadProof{}}
	}
	if rec.Reads == nil {
		rec.Reads = map[string]map[string]ReadProof{}
	}
	if rec.Reads[pin] == nil {
		rec.Reads[pin] = map[string]ReadProof{}
	}
	rec.Reads[pin][path] = ReadProof{ETag: etag, At: at}
	pruneReads(rec)
	return s.write(address, rec)
}

func (s *Reads) read(address string) (*readsRecord, error) {
	path := s.path(address)
	blob, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, fmt.Errorf("读取 %s：%w", path, err)
	}
	var rec readsRecord
	if err := json.Unmarshal(blob, &rec); err != nil {
		return nil, &CorruptError{Path: path, Err: err}
	}
	if rec.Address == "" {
		return nil, &CorruptError{Path: path, Err: errors.New("记录里没有 address")}
	}
	if rec.Address != address {
		return nil, &MismatchError{Path: path, Address: rec.Address, Wanted: address}
	}
	return &rec, nil
}

func (s *Reads) write(address string, rec *readsRecord) error {
	blob, err := json.Marshal(rec)
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

// pruneReads drops the oldest entries once one address has more than the cap.
//
// `At` is RFC 3339, so lexical order is time order. Ties are broken by pin and path so the result
// is deterministic; two reads in the same nanosecond are not a case worth a finer clock.
func pruneReads(rec *readsRecord) {
	type entry struct {
		pin, path string
		proof     ReadProof
	}
	var all []entry
	for pin, byPath := range rec.Reads {
		for path, proof := range byPath {
			all = append(all, entry{pin, path, proof})
		}
	}
	if len(all) <= maxReadProofs {
		return
	}
	sort.Slice(all, func(i, j int) bool {
		if all[i].proof.At != all[j].proof.At {
			return all[i].proof.At < all[j].proof.At
		}
		if all[i].pin != all[j].pin {
			return all[i].pin < all[j].pin
		}
		return all[i].path < all[j].path
	})
	for _, drop := range all[:len(all)-maxReadProofs] {
		delete(rec.Reads[drop.pin], drop.path)
		if len(rec.Reads[drop.pin]) == 0 {
			delete(rec.Reads, drop.pin)
		}
	}
}
