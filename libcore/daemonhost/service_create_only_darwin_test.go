//go:build darwin

package daemonhost

import (
	"bytes"
	"io"
	"os"
	"testing"
)

func TestDarwinCreateOnlyInventoryPipeLimit(t *testing.T) {
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatal("inventory_pipe=unavailable")
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		defer writer.Close()
		_, _ = io.Copy(writer, bytes.NewReader(make([]byte, 17<<20)))
	}()
	var output boundedServiceInventory
	n, copyErr := io.Copy(&output, reader)
	_ = reader.Close()
	<-done
	if copyErr == nil || n != 16<<20 {
		t.Fatalf("inventory_pipe_limit=false copied=%d", n)
	}
}

// The CI runner exercises only the bounded read-only inventory query, never setup.
func TestDarwinCreateOnlyRealSystemInventory(t *testing.T) {
	if err := requireDarwinServiceAbsent(); err != nil {
		t.Fatal("macOS_system_inventory=unavailable_or_existing")
	}
	t.Log("macOS_system_inventory=recognized_absent")
}
