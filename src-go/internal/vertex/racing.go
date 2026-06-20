package vertex

import (
	"context"
	"fmt"
	"log"
	"sync"
	"sync/atomic"

	"github.com/bsfdsagfadg/vertex/internal/config"
	"github.com/bsfdsagfadg/vertex/internal/nodes"
)

func RunParallel[T any](ctx context.Context, cfg config.AppConfig, op func(ctx context.Context, proxyURI string) (T, error)) (T, error) {
	var zero T
	poolSize := effectiveParallelPoolSize(cfg)
	proxy := configuredSingleProxy(cfg)

	if !cfg.ParallelPoolEnabled && proxy != "" {
		log.Printf("[Vertex] [RunParallel] running with single configured proxy: %s", proxy)
		return op(ctx, proxy)
	}

	cands := nodes.SelectForParallel(poolSize)
	if len(cands) == 0 {
		if proxy != "" {
			log.Printf("[Vertex] [RunParallel] no usable nodes; falling back to configured proxy: %s", proxy)
			return op(ctx, proxy)
		}
		log.Printf("[Vertex] [RunParallel] no usable nodes and no proxy configured; refusing direct empty-proxy request")
		return zero, NewInternalError("no proxy configured: import or enable proxy nodes, choose an active node, or set proxy_url")
	}

	log.Printf("[Vertex] [RunParallel] racing %d proxy node(s)", len(cands))
	for _, c := range cands {
		log.Printf("[Vertex] [RunParallel] candidate node: %s", c.Name)
	}

	ctxRace, cancel := context.WithCancel(ctx)
	defer cancel()

	type result struct {
		uri string
		val T
		err error
	}

	resCh := make(chan result, poolSize)
	var active int32
	var mu sync.Mutex
	activeKeys := make(map[string]bool)
	round := 0

	startNext := func() {
		mu.Lock()
		defer mu.Unlock()
		if cfg.ParallelPoolMaxRounds > 0 && round >= cfg.ParallelPoolMaxRounds {
			return
		}
		roundCands := nodes.SelectForParallel(1)
		for _, c := range roundCands {
			if !activeKeys[c.RawURI] {
				activeKeys[c.RawURI] = true
				atomic.AddInt32(&active, 1)
				go func(u string) {
					v, err := op(ctxRace, u)
					select {
					case resCh <- result{u, v, err}:
					case <-ctxRace.Done():
					}
				}(c.RawURI)
				return
			}
		}
		round++
	}

	for i := 0; i < poolSize && i < len(cands); i++ {
		mu.Lock()
		activeKeys[cands[i].RawURI] = true
		atomic.AddInt32(&active, 1)
		mu.Unlock()
		go func(u string) {
			v, err := op(ctxRace, u)
			select {
			case resCh <- result{u, v, err}:
			case <-ctxRace.Done():
			}
		}(cands[i].RawURI)
	}

	var lastErr error
	for atomic.LoadInt32(&active) > 0 {
		select {
		case res := <-resCh:
			atomic.AddInt32(&active, -1)
			if res.err == nil {
				name := nodeName(cands, res.uri)
				log.Printf("[Vertex] [RunParallel] winning node: %s", name)
				nodes.RecordTest(res.uri, true, 50, "")
				return res.val, nil
			}
			lastErr = res.err
			if ctx.Err() == nil && res.err != context.Canceled {
				name := nodeName(cands, res.uri)
				log.Printf("[Racing] node %s failed: %s", name, res.err.Error())
				nodes.RecordTest(res.uri, false, 0, res.err.Error())
			}
			startNext()
		case <-ctx.Done():
			return zero, ctx.Err()
		}
	}

	if lastErr != nil {
		return zero, lastErr
	}
	return zero, fmt.Errorf("all nodes failed")
}

func StreamParallel(ctx context.Context, cfg config.AppConfig, op func(ctx context.Context, proxyURI string) <-chan StreamChunk, yield func(StreamChunk) bool) {
	poolSize := effectiveParallelPoolSize(cfg)
	proxy := configuredSingleProxy(cfg)

	if !cfg.ParallelPoolEnabled && proxy != "" {
		log.Printf("[Vertex] [StreamParallel] running with single configured proxy: %s", proxy)
		for chunk := range op(ctx, proxy) {
			if !yield(chunk) {
				return
			}
		}
		return
	}

	cands := nodes.SelectForParallel(poolSize)
	if len(cands) == 0 {
		if proxy != "" {
			log.Printf("[Vertex] [StreamParallel] no usable nodes; falling back to configured proxy: %s", proxy)
			for chunk := range op(ctx, proxy) {
				if !yield(chunk) {
					return
				}
			}
			return
		}
		log.Printf("[Vertex] [StreamParallel] no usable nodes and no proxy configured; refusing direct empty-proxy request")
		yield(StreamChunk{Err: NewInternalError("no proxy configured: import or enable proxy nodes, choose an active node, or set proxy_url")})
		return
	}

	log.Printf("[Vertex] [StreamParallel] racing %d proxy node(s)", len(cands))
	for _, c := range cands {
		log.Printf("[Vertex] [StreamParallel] candidate node: %s", c.Name)
	}

	ctxRace, cancel := context.WithCancel(ctx)
	defer cancel()
	type res struct {
		uri   string
		ch    <-chan StreamChunk
		first StreamChunk
		err   error
	}
	resCh := make(chan res, len(cands))
	var active int32
	for _, cand := range cands {
		atomic.AddInt32(&active, 1)
		go func(u string) {
			ch := op(ctxRace, u)
			select {
			case first, ok := <-ch:
				if !ok {
					select {
					case resCh <- res{u, nil, StreamChunk{}, fmt.Errorf("stream closed")}:
					case <-ctxRace.Done():
					}
				} else if first.Err != nil {
					select {
					case resCh <- res{u, nil, StreamChunk{}, first.Err}:
					case <-ctxRace.Done():
					}
				} else {
					select {
					case resCh <- res{u, ch, first, nil}:
					case <-ctxRace.Done():
					}
				}
			case <-ctxRace.Done():
			}
		}(cand.RawURI)
	}

	var winner *res
loop:
	for atomic.LoadInt32(&active) > 0 {
		select {
		case r := <-resCh:
			atomic.AddInt32(&active, -1)
			if r.err == nil {
				winner = &r
				name := nodeName(cands, r.uri)
				log.Printf("[Vertex] [StreamParallel] winning node: %s", name)
				nodes.RecordTest(r.uri, true, 50, "")
				break loop
			}
			if ctx.Err() == nil && r.err != context.Canceled {
				name := nodeName(cands, r.uri)
				log.Printf("[Racing] node %s failed: %s", name, r.err.Error())
				nodes.RecordTest(r.uri, false, 0, r.err.Error())
			}
		case <-ctx.Done():
			return
		}
	}

	if winner != nil {
		if !yield(winner.first) {
			return
		}
		for chunk := range winner.ch {
			if !yield(chunk) {
				return
			}
		}
	} else {
		yield(StreamChunk{Err: NewInternalError("all nodes failed to stream")})
	}
}

func effectiveParallelPoolSize(cfg config.AppConfig) int {
	if cfg.ParallelPoolSize > 0 {
		return cfg.ParallelPoolSize
	}
	if def := config.DefaultConfig().ParallelPoolSize; def > 0 {
		return def
	}
	return 1
}

func configuredSingleProxy(cfg config.AppConfig) string {
	if cfg.ActiveNodeURI != "" {
		return cfg.ActiveNodeURI
	}
	return cfg.ProxyURL
}

func nodeName(cands []nodes.Node, uri string) string {
	for _, c := range cands {
		if c.RawURI == uri {
			return c.Name
		}
	}
	return uri
}
