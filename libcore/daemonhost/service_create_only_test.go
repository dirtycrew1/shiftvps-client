package daemonhost

import (
	"bytes"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
)

func createOnlyFixture(t *testing.T) (string, string, string) {
	t.Helper()
	root := t.TempDir()
	source := filepath.Join(root, "source", "core")
	destination := filepath.Join(root, "installed", "core")
	plist := filepath.Join(root, "installed", "service.plist")
	for _, path := range []string{source, destination} {
		if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
			t.Fatal(err)
		}
	}
	if err := os.WriteFile(source, []byte("complete core"), 0o755); err != nil {
		t.Fatal(err)
	}
	return source, destination, plist
}

func requireBytes(t *testing.T, path string, expected string) {
	t.Helper()
	content, err := os.ReadFile(path)
	if err != nil || string(content) != expected {
		t.Fatalf("unexpected bytes at %s: %q, %v", filepath.Base(path), content, err)
	}
}

func requireAbsent(t *testing.T, paths ...string) {
	t.Helper()
	for _, path := range paths {
		if _, err := os.Lstat(path); !os.IsNotExist(err) {
			t.Fatalf("expected absent %s: %v", filepath.Base(path), err)
		}
	}
}

func TestCreateOnlyCompleteCoreAndLibrariesPrecedeBootstrap(t *testing.T) {
	source, destination, plist := createOnlyFixture(t)
	library := "library.dylib"
	if err := os.WriteFile(filepath.Join(filepath.Dir(source), library), []byte("library"), 0o644); err != nil {
		t.Fatal(err)
	}
	prepared := false
	bootstrapped := false
	err := installServiceFilesIfAbsent(source, destination, plist, []byte("plist"), []string{library}, func(path string) error {
		requireBytes(t, path, "complete core")
		requireBytes(t, filepath.Join(filepath.Dir(path), library), "library")
		requireAbsent(t, plist)
		prepared = true
		return nil
	}, func() error {
		requireBytes(t, destination, "complete core")
		requireBytes(t, plist, "plist")
		if !prepared {
			t.Fatal("bootstrap before preparation")
		}
		bootstrapped = true
		return nil
	})
	if err != nil || !bootstrapped {
		t.Fatalf("create-only install: %v", err)
	}
}

func TestCreateOnlyExistingCorePlistOrLibraryNeverChanges(t *testing.T) {
	for _, existing := range []string{"core", "service.plist", "library.dylib"} {
		t.Run(existing, func(t *testing.T) {
			source, destination, plist := createOnlyFixture(t)
			path := filepath.Join(filepath.Dir(destination), existing)
			if err := os.WriteFile(path, []byte("foreign"), 0o600); err != nil {
				t.Fatal(err)
			}
			before, _ := os.Lstat(path)
			forbidden := func() error { t.Fatal("must not bootstrap existing installation"); return nil }
			err := installServiceFilesIfAbsent(source, destination, plist, []byte("new"), []string{"library.dylib"}, func(string) error {
				t.Fatal("must not prepare existing installation")
				return nil
			}, forbidden)
			if !errors.Is(err, errServiceInstallationExists) {
				t.Fatalf("expected existing installation refusal: %v", err)
			}
			after, _ := os.Lstat(path)
			if !os.SameFile(before, after) {
				t.Fatal("replaced foreign inode")
			}
			requireBytes(t, path, "foreign")
		})
	}
}

func TestCreateOnlyPreBootstrapFailureCleansOnlyOwnFilesAndRetries(t *testing.T) {
	source, destination, plist := createOnlyFixture(t)
	err := installServiceFilesIfAbsent(source, destination, plist, []byte("plist"), nil, func(string) error {
		return errors.New("preparation failed")
	}, func() error { t.Fatal("must not bootstrap"); return nil })
	if err == nil {
		t.Fatal("expected failure")
	}
	requireAbsent(t, destination, plist)
	err = installServiceFilesIfAbsent(source, destination, plist, []byte("plist"), nil, func(string) error { return nil }, func() error { return nil })
	if err != nil {
		t.Fatalf("clean retry: %v", err)
	}
}

func TestCreateOnlyRaceAtPlistPreservesForeignAndRemovesOwnCore(t *testing.T) {
	source, destination, plist := createOnlyFixture(t)
	err := installServiceFilesIfAbsent(source, destination, plist, []byte("ours"), nil, func(string) error {
		return os.WriteFile(plist, []byte("foreign appeared during prompt"), 0o600)
	}, func() error { t.Fatal("must not bootstrap competing service"); return nil })
	if err == nil {
		t.Fatal("expected exclusive publication refusal")
	}
	requireAbsent(t, destination)
	requireBytes(t, plist, "foreign appeared during prompt")
}

func TestCreateOnlyCleanupDoesNotRemoveReplacementInode(t *testing.T) {
	_, destination, _ := createOnlyFixture(t)
	created, err := publishServiceFile(destination, 0o755, func(output io.Writer) error {
		_, err := output.Write([]byte("ours"))
		return err
	})
	if err != nil {
		t.Fatal(err)
	}
	if err = os.Rename(destination, destination+".old"); err != nil {
		t.Fatal(err)
	}
	if err = os.WriteFile(destination, []byte("foreign replacement"), 0o600); err != nil {
		t.Fatal(err)
	}
	removeOwnedFile(created)
	requireBytes(t, destination, "foreign replacement")
}

func TestCreateOnlyPreparationUsesUnpublishedPrivateFile(t *testing.T) {
	source, destination, plist := createOnlyFixture(t)
	err := installServiceFilesIfAbsent(source, destination, plist, []byte("plist"), nil, func(privatePath string) error {
		if privatePath == destination {
			t.Fatal("preparation must not operate on final public path")
		}
		requireAbsent(t, destination, plist)
		requireBytes(t, privatePath, "complete core")
		return nil
	}, func() error { return nil })
	if err != nil {
		t.Fatal(err)
	}
}

func TestDarwinCreateOnlyInventoryFailsClosed(t *testing.T) {
	label := "ru.shiftvps.client.daemon"
	for _, output := range []string{
		"", "permission denied", "system = { services = {", "system = { services = { }", "system = { unknown format }",
		"system = { services = { " + label + " = running } }",
		"system = { services = { other } disabled services = { " + label + " = true } }",
	} {
		if darwinServiceAbsentFromInventory([]byte(output), label, true) {
			t.Fatalf("must not infer absent from %q", output)
		}
	}
	valid := []byte("system = { services = { com.apple.example = running } }")
	if !darwinServiceAbsentFromInventory(valid, label, true) {
		t.Fatal("complete system inventory without the label must permit fresh setup")
	}
	if darwinServiceAbsentFromInventory(valid, label, false) {
		t.Fatal("failed query must not infer absence from partial or stale output")
	}
	if !darwinServiceAbsentFromInventory([]byte("system = { services = { } }"), label, true) {
		t.Fatal("known empty inventory must permit fresh setup")
	}
}

func TestCreateOnlyRefusesHistoricalDaemonStateWithoutRemovingIt(t *testing.T) {
	root := t.TempDir()
	if err := os.Mkdir(filepath.Join(root, "bin"), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := requireFreshServiceDirectory(root); err != nil {
		t.Fatalf("empty pre-bootstrap directories may retry: %v", err)
	}
	snapshot := filepath.Join(root, "snapshot.json")
	if err := os.WriteFile(snapshot, []byte("historical owner configuration"), 0o600); err != nil {
		t.Fatal(err)
	}
	if !errors.Is(requireFreshServiceDirectory(root), errServiceInstallationExists) {
		t.Fatal("old daemon state must not be restored by automatic setup")
	}
	requireBytes(t, snapshot, "historical owner configuration")
}

func TestCreateOnlyUncertainBootstrapPreservesFilesAndRefusesReplay(t *testing.T) {
	source, destination, plist := createOnlyFixture(t)
	boots := 0
	bootstrap := func() error { boots++; return errors.New("lost acknowledgement") }
	err := installServiceFilesIfAbsent(source, destination, plist, []byte("plist"), nil, func(string) error { return nil }, bootstrap)
	if err == nil {
		t.Fatal("expected uncertain outcome")
	}
	requireBytes(t, destination, "complete core")
	requireBytes(t, plist, "plist")
	err = installServiceFilesIfAbsent(source, destination, plist, []byte("new"), nil, func(string) error { return nil }, bootstrap)
	if !errors.Is(err, errServiceInstallationExists) || boots != 1 {
		t.Fatalf("uncertain install must not replay: boots=%d err=%v", boots, err)
	}
}

func TestPublishServiceFileFailureNeverPublishesPartialBytes(t *testing.T) {
	path := filepath.Join(t.TempDir(), "core")
	_, err := publishServiceFile(path, 0o755, func(output io.Writer) error {
		_, _ = output.Write([]byte("partial"))
		return errors.New("read interrupted")
	})
	if err == nil {
		t.Fatal("expected interrupted copy")
	}
	requireAbsent(t, path)
	files, _ := os.ReadDir(filepath.Dir(path))
	if len(files) != 0 {
		t.Fatal("temporary files not cleaned")
	}
}

func TestPublishServiceFileConcurrentCopiesHaveExactlyOneWinner(t *testing.T) {
	path := filepath.Join(t.TempDir(), "core")
	var winners atomic.Int32
	var workers sync.WaitGroup
	for i := 0; i < 12; i++ {
		workers.Add(1)
		go func() {
			defer workers.Done()
			_, err := publishServiceFile(path, 0o755, func(output io.Writer) error {
				_, err := io.Copy(output, bytes.NewBufferString("complete"))
				return err
			})
			if err == nil {
				winners.Add(1)
			}
		}()
	}
	workers.Wait()
	if winners.Load() != 1 {
		t.Fatalf("expected one exclusive winner, got %d", winners.Load())
	}
	requireBytes(t, path, "complete")
}
