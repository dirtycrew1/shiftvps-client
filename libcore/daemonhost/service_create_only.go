package daemonhost

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

var errServiceInstallationExists = errors.New("service installation already exists; automatic replacement refused")

func requireFreshServiceDirectory(directory string) error {
	entries, err := os.ReadDir(directory)
	if err != nil {
		return err
	}
	for _, entry := range entries {
		// A failed pre-bootstrap copy may leave our empty root-owned bin directory.
		if entry.Name() != "bin" || !entry.IsDir() || entry.Type()&os.ModeSymlink != 0 {
			return errServiceInstallationExists
		}
		children, readErr := os.ReadDir(filepath.Join(directory, entry.Name()))
		if readErr != nil {
			return readErr
		}
		if len(children) != 0 {
			return errServiceInstallationExists
		}
	}
	return nil
}

type createdServiceFile struct {
	path string
	info os.FileInfo
}

// removeOwnedFile never removes a path that another installation has replaced.
func removeOwnedFile(file createdServiceFile) {
	current, err := os.Lstat(file.path)
	if err == nil && current.Mode()&os.ModeSymlink == 0 && os.SameFile(file.info, current) {
		_ = os.Remove(file.path)
	}
}

// publishServiceFile makes complete bytes visible without replacing any inode.
func publishServiceFile(path string, mode os.FileMode, write func(io.Writer) error) (createdServiceFile, error) {
	return publishPreparedServiceFile(path, mode, write, nil)
}

func publishPreparedServiceFile(path string, mode os.FileMode, write func(io.Writer) error, prepare func(string) error) (createdServiceFile, error) {
	file, err := os.CreateTemp(filepath.Dir(path), ".shiftvps-install-*")
	if err != nil {
		return createdServiceFile{}, err
	}
	info, err := file.Stat()
	if err != nil {
		_ = file.Close()
		return createdServiceFile{}, err
	}
	temporary := createdServiceFile{path: file.Name(), info: info}
	defer removeOwnedFile(temporary)
	if err = file.Chmod(mode); err == nil {
		err = write(file)
	}
	if err == nil && prepare != nil {
		err = prepare(temporary.path)
	}
	if err == nil {
		err = file.Sync()
	}
	closeErr := file.Close()
	if err != nil {
		return createdServiceFile{}, err
	}
	if closeErr != nil {
		return createdServiceFile{}, closeErr
	}
	current, err := os.Lstat(temporary.path)
	if err != nil || current.Mode()&os.ModeSymlink != 0 || !os.SameFile(info, current) {
		return createdServiceFile{}, errors.New("private installation file changed before publication")
	}
	if err = os.Link(temporary.path, path); err != nil {
		return createdServiceFile{}, err
	}
	return createdServiceFile{path: path, info: info}, nil
}

// Only failures before bootstrap are safe to undo. An uncertain bootstrap may
// already be using these files; a subsequent attempt must not replace them.
func installServiceFilesIfAbsent(source, destination, plistPath string, plist []byte, libraries []string, prepare func(string) error, bootstrap func() error) (err error) {
	targets := []string{destination, plistPath}
	for _, library := range libraries {
		if filepath.Base(library) != library || library == "." {
			return errors.New("invalid core library name")
		}
		targets = append(targets, filepath.Join(filepath.Dir(destination), library))
	}
	for _, target := range targets {
		if _, checkErr := os.Lstat(target); checkErr == nil {
			return errServiceInstallationExists
		} else if !os.IsNotExist(checkErr) {
			return checkErr
		}
	}
	var owned []createdServiceFile
	bootstrapAttempted := false
	defer func() {
		if err != nil && !bootstrapAttempted {
			for i := len(owned) - 1; i >= 0; i-- {
				removeOwnedFile(owned[i])
			}
		}
	}()
	copySource := func(from, target string, mode os.FileMode, beforePublish func(string) error) error {
		input, openErr := os.Open(from)
		if openErr != nil {
			return openErr
		}
		defer input.Close()
		created, copyErr := publishPreparedServiceFile(target, mode, func(output io.Writer) error {
			_, copyErr := io.Copy(output, input)
			return copyErr
		}, beforePublish)
		if copyErr == nil {
			owned = append(owned, created)
		}
		return copyErr
	}
	for _, library := range libraries {
		if err = copySource(filepath.Join(filepath.Dir(source), library), filepath.Join(filepath.Dir(destination), library), 0o644, nil); err != nil {
			return err
		}
	}
	if err = copySource(source, destination, 0o755, prepare); err != nil {
		return err
	}
	created, err := publishServiceFile(plistPath, 0o644, func(output io.Writer) error {
		_, writeErr := output.Write(plist)
		return writeErr
	})
	if err != nil {
		return err
	}
	owned = append(owned, created)
	bootstrapAttempted = true
	if err = bootstrap(); err != nil {
		return fmt.Errorf("service bootstrap outcome uncertain; automatic replacement refused: %w", err)
	}
	return nil
}

// Unknown or incomplete launchctl output is never treated as proof of absence.
func darwinServiceAbsentFromInventory(output []byte, label string, querySucceeded bool) bool {
	text := strings.TrimSpace(string(output))
	return querySucceeded && strings.HasPrefix(text, "system = {") && strings.HasSuffix(text, "}") &&
		strings.Count(text, "{") == strings.Count(text, "}") &&
		strings.Contains(text, "services = {") && !strings.Contains(text, label)
}
