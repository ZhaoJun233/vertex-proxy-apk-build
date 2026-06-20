package vertex

import (
	"context"
	"strings"
	"testing"

	"github.com/bsfdsagfadg/vertex/internal/config"
	"github.com/bsfdsagfadg/vertex/internal/nodes"
)

func TestRunParallelUsesNodesWhenPoolSizeZero(t *testing.T) {
	t.Setenv("VPROXY_CONFIG_DIR", t.TempDir())
	clearTestNodes()
	nodes.MergeNodes([]nodes.Node{{Name: "node1", RawURI: "vless://uuid@example.com:443"}})
	defer clearTestNodes()

	cfg := config.DefaultConfig()
	cfg.ProxyURL = ""
	cfg.ActiveNodeURI = ""
	cfg.ParallelPoolEnabled = true
	cfg.ParallelPoolSize = 0

	got, err := RunParallel(context.Background(), cfg, func(_ context.Context, proxyURI string) (string, error) {
		if proxyURI == "" {
			t.Fatal("operation was called with an empty proxy")
		}
		return proxyURI, nil
	})
	if err != nil {
		t.Fatalf("RunParallel returned error: %v", err)
	}
	if got != "vless://uuid@example.com:443" {
		t.Fatalf("RunParallel used proxy %q, want imported node", got)
	}
}

func TestStreamParallelRejectsEmptyProxy(t *testing.T) {
	t.Setenv("VPROXY_CONFIG_DIR", t.TempDir())
	clearTestNodes()
	defer clearTestNodes()

	cfg := config.DefaultConfig()
	cfg.ProxyURL = ""
	cfg.ActiveNodeURI = ""
	cfg.ParallelPoolEnabled = false

	called := false
	var gotErr *VertexError
	StreamParallel(context.Background(), cfg, func(_ context.Context, proxyURI string) <-chan StreamChunk {
		called = true
		ch := make(chan StreamChunk, 1)
		ch <- StreamChunk{Err: NewInternalError("called with proxy " + proxyURI)}
		close(ch)
		return ch
	}, func(chunk StreamChunk) bool {
		gotErr = chunk.Err
		return true
	})

	if called {
		t.Fatal("StreamParallel should not call op with an empty proxy")
	}
	if gotErr == nil || !strings.Contains(gotErr.Message, "no proxy configured") {
		t.Fatalf("got error %v, want no proxy configured", gotErr)
	}
}

func clearTestNodes() {
	list := nodes.LoadNodes()
	uris := make([]string, 0, len(list))
	for _, n := range list {
		uris = append(uris, n.RawURI)
	}
	if len(uris) > 0 {
		nodes.BatchDeleteNodes(uris)
	}
}
