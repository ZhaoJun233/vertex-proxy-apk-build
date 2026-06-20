package dnsfix

import (
	"context"
	"log"
	"net"
	"runtime"
	"sync/atomic"
	"time"
)

// Install works around Android builds that expose a broken localhost DNS
// resolver such as [::1]:53 to Go's net package.
func Install() {
	if runtime.GOOS != "android" {
		return
	}

	servers := []string{
		"8.8.8.8:53",
		"1.1.1.1:53",
		"9.9.9.9:53",
		"223.5.5.5:53",
		"119.29.29.29:53",
	}
	var next atomic.Uint32
	dialer := &net.Dialer{Timeout: 5 * time.Second}

	net.DefaultResolver = &net.Resolver{
		PreferGo: true,
		Dial: func(ctx context.Context, network, address string) (net.Conn, error) {
			start := int(next.Add(1)-1) % len(servers)
			var lastErr error
			for i := 0; i < len(servers); i++ {
				server := servers[(start+i)%len(servers)]
				dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
				conn, err := dialer.DialContext(dialCtx, network, server)
				cancel()
				if err == nil {
					return conn, nil
				}
				lastErr = err
			}
			log.Printf("[DNS] Android fallback DNS failed for %s via %s: %v", address, network, lastErr)
			return nil, lastErr
		},
	}

	log.Printf("[DNS] installed Android fallback DNS resolver: %v", servers)
}
