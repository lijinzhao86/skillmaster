package main

import (
	"bytes"
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"

	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/config"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/prompt"
	"github.com/lijinzhao86/skillmaster/skillmaster-cli/internal/version"
)

// noteStaleBlock is the one-line notice that the person's file has an older rule block.
//
// **Only the record is compared, never the file.** Reading the instruction file on every command
// would put a file read the person did not ask for in front of every command — and the answer it
// would produce (their edits) is not the question. `prompt status` reads the file; this only says
// "the binary now carries a different block than the one this machine wrote".
//
// **Once per new block.** A person who decides not to update should not be told again on every
// command; the notice marks the digest as seen, and `prompt status` still answers whenever asked.
func noteStaleBlock(args []string) {
	if len(args) == 0 {
		return
	}
	// Not a `switch args[0]`: that shape means "this function dispatches commands", and
	// `TestTheCommandTreeMatchesTheSource` would rightly demand a declaration for it.
	if args[0] == "--version" || args[0] == "help" || args[0] == "prompt" {
		return
	}
	for _, arg := range args {
		if arg == "--help" || arg == "-h" {
			return
		}
	}
	dir, err := config.Dir()
	if err != nil {
		return
	}
	record, has, err := prompt.Load(dir)
	if err != nil || !has {
		return
	}
	digest := prompt.Digest()
	if record.Digest == digest || record.Notified == digest {
		return
	}
	// **A file that is not there has no old block.** One stat, no read: the content is still
	// `prompt status`'s job, but "deleted" and "stale" are different answers and only one of them
	// is worth a line on a command that has nothing to do with the block.
	if _, err := os.Stat(record.File); err != nil {
		return
	}
	record.Notified = digest
	_ = prompt.Save(dir, record)
	fmt.Fprintln(os.Stderr, "（系统提示词里的规则段是旧版：skillmaster prompt status 看差异，prompt update --yes 更新。）")
}

// `prompt` — the rule block that goes into an agent's instruction file.
//
// **The block belongs to this binary** (ADR 0102), so every question about it is local: which text
// it should be, whether the file has it, what the difference is. None of these commands talks to
// the server, needs a credential, or waits for input.
//
// **Nothing here prompts.** `update` without `--yes` prints the difference and exits 1 — the asking
// happens between two invocations, by whoever is driving the CLI. A command that sat waiting for a
// "y" would hang an agent in every sandbox there is (the same lesson v22's A4 recorded about the
// browser login).
func promptVerb(ctx context.Context, args []string) error {
	_ = ctx
	if len(args) == 0 {
		return usagef("%s", usageAt("prompt"))
	}
	switch args[0] {
	case "status":
		return promptStatus(args[1:])
	case "print":
		return promptPrint(args[1:])
	case "install":
		return promptInstall(args[1:])
	case "update":
		return promptUpdate(args[1:])
	default:
		return unknownName("prompt", args[0], childNames("prompt"))
	}
}

// promptStatus answers "is what is in the file what this binary carries".
//
// It exits 1 whenever the answer is not a plain yes — no record, file gone, markers gone, text
// differs — because in every one of those cases there is something for the person to do, and a
// script can tell without parsing the prose.
func promptStatus(args []string) error {
	if len(args) > 0 {
		return unknownFlag("prompt status", args[0])
	}
	fmt.Printf("这个二进制自带：%s\n", prompt.Digest())
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	record, has, err := prompt.Load(dir)
	if err != nil {
		return err
	}
	if !has {
		fmt.Println("本机还没有记录。把它写进你的指令文件：skillmaster prompt install --file <路径>")
		return &exitError{code: 1}
	}
	fmt.Printf("记录的文件：%s\n", record.File)
	if record.Digest != "" {
		fmt.Printf("记录里写的：%s\n", record.Digest)
	}
	if record.Version != "" && record.Version != version.String() {
		fmt.Printf("写它的时候：%s\n", record.Version)
	}
	file, err := os.ReadFile(record.File)
	if err != nil {
		fmt.Printf("读不到这个文件：%v\n", err)
		fmt.Println("重新指定它：skillmaster prompt install --file <路径>")
		return &exitError{code: 1}
	}
	block, _, err := prompt.BlockIn(record.File, file)
	if errors.Is(err, prompt.ErrMissing) {
		fmt.Println("这个文件里没有标记段。重新写入：skillmaster prompt install --file " + record.File)
		return &exitError{code: 1}
	}
	var ambiguous *prompt.AmbiguousError
	if errors.As(err, &ambiguous) {
		fmt.Println(ambiguous.Error())
		return &exitError{code: 1}
	}
	if err != nil {
		return err
	}
	fmt.Printf("文件里现在：%s\n", prompt.DigestOf(block))
	if bytes.Equal(block, prompt.Normalise(prompt.Block())) {
		fmt.Println("一致。")
		// **The file is the truth, and the record follows it.** A record that is behind a file the
		// person fixed by hand would otherwise keep announcing a difference forever, and the next
		// update would still show the right diff but the wrong history.
		if record.Digest != prompt.Digest() {
			record.Digest = prompt.Digest()
			record.Version = version.String()
			record.Notified = ""
			if err := prompt.Save(dir, record); err != nil {
				return err
			}
		}
		return nil
	}
	fmt.Println("不一致——文件里是旧版：")
	printBlockDiff(string(block), string(prompt.Normalise(prompt.Block())))
	fmt.Println("更新它：skillmaster prompt update")
	return &exitError{code: 1}
}

// promptPrint writes the block this binary carries, verbatim.
//
// **The manual escape hatch, and the one that works offline.** A person who would rather paste it
// themselves, or whose sandbox refuses the write, gets exactly the bytes the updater would write.
func promptPrint(args []string) error {
	if len(args) > 0 {
		return unknownFlag("prompt print", args[0])
	}
	os.Stdout.Write(prompt.Block())
	return nil
}

// promptInstall writes the block into a file for the first time, and remembers where.
//
// **The place is chosen once, by the person.** The CLI has no way to know which instruction file an
// agent reads — there are dozens of conventions — and guessing would mean reading files nobody asked
// us to read. `--file` is that one piece of information, and the record is what makes every later
// update a one-word command.
func promptInstall(args []string) error {
	file := ""
	confirmed := false
	for i := 0; i < len(args); i++ {
		switch args[i] {
		case "--file":
			value, err := flagValue(args, i, "--file", "prompt install")
			if err != nil {
				return err
			}
			file = value
			i++
		case "--yes":
			confirmed = true
		default:
			return unknownFlag("prompt install", args[i])
		}
	}
	if file == "" {
		return usagef("prompt install 要用 --file 指定写进哪个文件。%s", usageAt("prompt install"))
	}
	path, err := expandHome(file)
	if err != nil {
		return err
	}
	path, err = filepath.Abs(path)
	if err != nil {
		return fmt.Errorf("解析路径 %s：%w", file, err)
	}

	existing, mode, exists, err := readMaybe(path)
	if err != nil {
		return err
	}
	if !exists {
		// New file: it is the person's instruction file, so it gets an ordinary file's permissions.
		// An existing empty file keeps whatever mode it already had.
		mode = 0o644
	}

	block := prompt.Block()
	region, locateErr := prompt.Locate(path, existing)
	switch {
	case locateErr == nil:
		if bytes.Equal(prompt.Normalise(existing[region.Start:region.End]), prompt.Normalise(block)) {
			// **Already there, and identical.** Record it and touch nothing: a person who pasted the
			// block by hand during the journey should not see their file rewritten to say the same
			// thing (their editor's undo history, their mtime, their trust).
			return saveBlockRecord(path, fmt.Sprintf("已经是最新，只记下位置：%s", path))
		}
		if !confirmed {
			fmt.Printf("%s 里已经有一段，但和这个二进制带的不一样：\n", path)
			printBlockDiff(string(prompt.Normalise(existing[region.Start:region.End])), string(prompt.Normalise(block)))
			fmt.Println("确认要替换它，加上 --yes 重跑。")
			return &exitError{code: 1}
		}
		return writeBlock(path, existing, prompt.Replace(existing, region, block), mode)
	case errors.Is(locateErr, prompt.ErrMissing):
		return writeBlock(path, existing, prompt.Append(existing, block, prompt.UsesCRLF(existing)), mode)
	default:
		return locateErr
	}
}

// promptUpdate refreshes the file the record points at.
func promptUpdate(args []string) error {
	confirmed := false
	for _, arg := range args {
		switch arg {
		case "--yes":
			confirmed = true
		default:
			return unknownFlag("prompt update", arg)
		}
	}
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	record, has, err := prompt.Load(dir)
	if err != nil {
		return err
	}
	if !has {
		return errors.New("本机还没有记录。先跑一次 skillmaster prompt install --file <路径>，" +
			"它会把规则段写进去并记住位置。")
	}
	file, err := os.ReadFile(record.File)
	if err != nil {
		return fmt.Errorf("读不到 %s：%w。重新指定它：skillmaster prompt install --file <路径>", record.File, err)
	}
	region, err := prompt.Locate(record.File, file)
	if errors.Is(err, prompt.ErrMissing) {
		return fmt.Errorf("%s 里没有标记段。重新写入：skillmaster prompt install --file %s", record.File, record.File)
	}
	if err != nil {
		return err
	}
	block := prompt.Block()
	current := prompt.Normalise(file[region.Start:region.End])
	if bytes.Equal(current, prompt.Normalise(block)) {
		if record.Digest != prompt.Digest() {
			record.Digest = prompt.Digest()
			record.Version = version.String()
			record.Notified = ""
			if err := prompt.Save(dir, record); err != nil {
				return err
			}
		}
		fmt.Println("已是最新。")
		return nil
	}
	if !confirmed {
		fmt.Printf("%s 里的是旧版：\n", record.File)
		printBlockDiff(string(current), string(prompt.Normalise(block)))
		fmt.Println("确认要替换它，加上 --yes 重跑。")
		return &exitError{code: 1}
	}
	mode, err := fileMode(record.File)
	if err != nil {
		return err
	}
	return writeBlock(record.File, file, prompt.Replace(file, region, block), mode)
}

// writeBlock writes the file and then records it.
//
// **The write is staged and renamed, never `O_TRUNC` on the person's file.** These commands edit an
// instruction file this CLI does not own; a disk that fills up, or a process killed mid-write, would
// otherwise leave a prefix of the file with no copy anywhere. The credential store already does
// exactly this (`internal/credentials/file.go`), and ADR 0101 allows the rename.
//
// **And it refuses if the file changed since it was read.** An agent editing the same file is the
// ordinary case, not a corner one; writing a result derived from a stale read would delete the
// paragraph somebody saved a moment earlier.
//
// **Record after the write, never before.** A record that names a file the write never reached
// would make the next `update` report a difference that the person already approved.
func writeBlock(path string, original, content []byte, mode os.FileMode) error {
	real, err := realPath(path)
	if err != nil {
		return err
	}
	current, readErr := os.ReadFile(real)
	if readErr != nil && !errors.Is(readErr, os.ErrNotExist) {
		return fmt.Errorf("读 %s：%w", real, readErr)
	}
	if !bytes.Equal(current, original) {
		return errors.New("文件在这期间被改过，没有写。重跑一次。")
	}
	staged := real + ".skillmaster.new"
	if err := os.WriteFile(staged, content, mode); err != nil {
		return fmt.Errorf("写 %s：%w", staged, err)
	}
	if err := os.Chmod(staged, mode); err != nil {
		return fmt.Errorf("设置 %s 的权限：%w", staged, err)
	}
	if err := os.Rename(staged, real); err != nil {
		return fmt.Errorf("替换 %s：%w（留下了 %s，可以自行删除）。"+
			"如果这个文件正在编辑器里打开，关掉它再试", real, err, staged)
	}
	return saveBlockRecord(path, fmt.Sprintf("已写入 %s（%s）", path, prompt.Digest()))
}

// realPath follows a symlink so an update writes through the link instead of replacing it with a
// regular file — the common dotfiles layout would otherwise silently break.
func realPath(path string) (string, error) {
	info, err := os.Lstat(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			return path, nil
		}
		return "", fmt.Errorf("读 %s 的信息：%w", path, err)
	}
	if info.Mode()&os.ModeSymlink == 0 {
		return path, nil
	}
	real, err := filepath.EvalSymlinks(path)
	if err != nil {
		return "", fmt.Errorf("解析 %s 的链接：%w", path, err)
	}
	return real, nil
}

func saveBlockRecord(path, message string) error {
	dir, err := config.Dir()
	if err != nil {
		return err
	}
	if err := prompt.Save(dir, prompt.Record{
		File:    path,
		Digest:  prompt.Digest(),
		Version: version.String(),
	}); err != nil {
		return err
	}
	fmt.Println(message)
	return nil
}

// readMaybe reads a file that may not exist yet.
func readMaybe(path string) ([]byte, os.FileMode, bool, error) {
	blob, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, 0, false, nil
	}
	if err != nil {
		return nil, 0, false, fmt.Errorf("读 %s：%w", path, err)
	}
	mode, err := fileMode(path)
	if err != nil {
		return nil, 0, false, err
	}
	return blob, mode, true, nil
}

func fileMode(path string) (os.FileMode, error) {
	info, err := os.Stat(path)
	if err != nil {
		return 0, fmt.Errorf("读 %s 的信息：%w", path, err)
	}
	return info.Mode().Perm(), nil
}

// printBlockDiff is a line diff, good enough for a five-line block and honest about what changed.
//
// Not a unified diff with hunk headers: the whole block is always shown, because "which three lines
// of a paragraph changed" is the question, and a five-line excerpt with no context answers it worse
// than the paragraph itself.
func printBlockDiff(before, after string) {
	oldLines := splitBlock(before)
	newLines := splitBlock(after)
	common := make([][]int, len(oldLines)+1)
	for i := range common {
		common[i] = make([]int, len(newLines)+1)
	}
	for i := len(oldLines) - 1; i >= 0; i-- {
		for j := len(newLines) - 1; j >= 0; j-- {
			switch {
			case oldLines[i] == newLines[j]:
				common[i][j] = common[i+1][j+1] + 1
			case common[i+1][j] >= common[i][j+1]:
				common[i][j] = common[i+1][j]
			default:
				common[i][j] = common[i][j+1]
			}
		}
	}
	for i, j := 0, 0; i < len(oldLines) || j < len(newLines); {
		switch {
		case i < len(oldLines) && j < len(newLines) && oldLines[i] == newLines[j]:
			fmt.Println("  " + oldLines[i])
			i++
			j++
		case j >= len(newLines) || (i < len(oldLines) && common[i+1][j] >= common[i][j+1]):
			fmt.Println("- " + oldLines[i])
			i++
		default:
			fmt.Println("+ " + newLines[j])
			j++
		}
	}
}

func splitBlock(text string) []string {
	lines := strings.Split(strings.TrimSuffix(text, "\n"), "\n")
	if len(lines) == 1 && lines[0] == "" {
		return nil
	}
	return lines
}
