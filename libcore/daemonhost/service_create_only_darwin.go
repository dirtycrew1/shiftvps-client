//go:build darwin

package daemonhost

import (
	"bytes"
	"context"
	"errors"
	"io"
	"os"
	"os/exec"
	"path/filepath"
	"time"
)

// ServiceInstallIfAbsent is the first-run path, never a repair or a restart.
func ServiceInstallIfAbsent(workingDir string) error {
	if os.Geteuid() != 0 {
		return os.ErrPermission
	}
	if err := requireDarwinServiceAbsent(); err != nil {
		return err
	}
	if workingDir == "" {
		workingDir = DefaultWorkingDir()
	}
	absDir, err := filepath.Abs(workingDir)
	if err != nil {
		return err
	}
	for _, path := range []string{darwinInstallBin, darwinPlistPath} {
		if _, checkErr := os.Lstat(path); checkErr == nil {
			return errServiceInstallationExists
		} else if !os.IsNotExist(checkErr) {
			return checkErr
		}
	}
	if err = ensureProtectedInstallDirectory(absDir, 0o700); err != nil {
		return err
	}
	// Old snapshots could restore a tunnel as soon as launchd starts the daemon.
	if err = requireFreshServiceDirectory(absDir); err != nil {
		return err
	}
	if err = ensureProtectedInstallDirectory(filepath.Dir(darwinInstallBin), 0o755); err != nil {
		return err
	}
	if err = validateProtectedDirectory(filepath.Dir(darwinPlistPath)); err != nil {
		return err
	}
	executable, err := os.Executable()
	if err != nil {
		return err
	}
	source, err := resolveExecutablePath(executable)
	if err != nil {
		return err
	}
	return installServiceFilesIfAbsent(source, darwinInstallBin, darwinPlistPath,
		[]byte(darwinServicePlist(absDir)), coreLibraries,
		func(core string) error {
			if err := os.Chown(core, 0, 0); err != nil {
				return err
			}
			// Match the existing installer, limited to our new private copy.
			_ = exec.Command("/usr/bin/xattr", "-d", "com.apple.quarantine", core).Run()
			return nil
		},
		func() error {
			// bootstrap refuses an already loaded label; no bootout or forced kickstart.
			return exec.Command("/bin/launchctl", "bootstrap", "system", darwinPlistPath).Run()
		},
	)
}

type boundedServiceInventory struct{ bytes.Buffer }

func (output *boundedServiceInventory) Write(content []byte) (int, error) {
	const maxInventoryBytes = 16 << 20
	if len(content) > maxInventoryBytes-output.Len() {
		return 0, errors.New("system service inventory exceeded limit")
	}
	return output.Buffer.Write(content)
}

func requireDarwinServiceAbsent() error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	command := exec.CommandContext(ctx, "/bin/launchctl", "print", "system")
	var output boundedServiceInventory
	command.Stdout = &output
	command.Stderr = io.Discard
	err := command.Run()
	if !darwinServiceAbsentFromInventory(output.Bytes(), darwinServiceLabel, err == nil) {
		return errors.New("system service exists or its state is unknown; automatic installation refused")
	}
	return nil
}

func ensureProtectedInstallDirectory(path string, mode os.FileMode) error {
	if _, err := os.Lstat(path); err == nil {
		return validateProtectedDirectory(path)
	} else if !os.IsNotExist(err) {
		return err
	}
	parent := filepath.Dir(path)
	if err := ensureProtectedInstallDirectory(parent, 0o755); err != nil {
		return err
	}
	if err := os.Mkdir(path, mode); err != nil && !os.IsExist(err) {
		return err
	}
	return validateProtectedDirectory(path)
}
