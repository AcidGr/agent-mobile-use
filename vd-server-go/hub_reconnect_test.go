package main

import (
	"encoding/binary"
	"net"
	"testing"
	"time"
)

// Regression test for the reported bug: the daemon's 3071 listener comes up LATE (after
// the old fixed retry budget of ~4.5s would already have been exhausted). The supervisor
// must keep retrying while a watcher is connected and eventually connect.
func TestHubKeepsRetryingUntilDaemonListenerAppears(t *testing.T) {
	h := &StreamHub{clients: make(map[net.Conn]struct{})}

	// Pretend a browser watcher is connected.
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	h.clients[c1] = struct{}{}

	// Bind 3071 only after a delay far beyond the legacy 30x150ms budget.
	ready := make(chan net.Listener, 1)
	go func() {
		time.Sleep(1500 * time.Millisecond)
		ln, err := net.Listen("tcp", "127.0.0.1:3071")
		if err != nil {
			ready <- nil
			return
		}
		ready <- ln
	}()

	connected := make(chan struct{}, 1)
	go func() {
		// Wrap: poll daemonConn until the supervisor attaches, then signal.
		for i := 0; i < 200; i++ {
			h.mu.Lock()
			c := h.daemonConn
			h.mu.Unlock()
			if c != nil {
				connected <- struct{}{}
				return
			}
			time.Sleep(50 * time.Millisecond)
		}
	}()

	go h.connectDaemonLoop()

	select {
	case <-connected:
		// success: retried past the legacy give-up window and attached.
	case <-time.After(6 * time.Second):
		t.Fatal("supervisor never connected to a late-starting 3071 listener")
	}

	if ln := <-ready; ln != nil {
		ln.Close()
	}
	// Dropping the watcher must let the supervisor exit its loop.
	h.mu.Lock()
	delete(h.clients, c1)
	if h.daemonConn != nil {
		_ = h.daemonConn.Close()
	}
	h.mu.Unlock()
}

// Verifies broadcast does not hold the lock during writes and prunes dead conns.
func TestBroadcastPrunesDead(t *testing.T) {
	h := &StreamHub{clients: make(map[net.Conn]struct{})}
	good, goodPeer := net.Pipe()
	defer good.Close()
	defer goodPeer.Close()
	dead, deadPeer := net.Pipe()
	deadPeer.Close() // make writes fail

	h.clients[good] = struct{}{}
	h.clients[dead] = struct{}{}

	// Drain the good peer so its write can complete.
	go func() {
		buf := make([]byte, 1024)
		for {
			if _, err := goodPeer.Read(buf); err != nil {
				return
			}
		}
	}()
	time.Sleep(20 * time.Millisecond)

	payload := []byte{1, 2, 3, 4}
	h.broadcast(payload)
	time.Sleep(50 * time.Millisecond)

	if h.watcherCount() != 1 {
		t.Fatalf("expected dead conn pruned, got %d watchers", h.watcherCount())
	}
}

// Round-trip a single daemon frame through pumpDaemonFrames to prove the framing contract
// (size/flags/pts + payload) and keyframe/SPS caching still work after the refactor.
func TestPumpDaemonFramesCachesAndBroadcasts(t *testing.T) {
	h := &StreamHub{clients: make(map[net.Conn]struct{})}
	client, clientPeer := net.Pipe()
	defer client.Close()
	defer clientPeer.Close()
	h.clients[client] = struct{}{}

	go func() {
		buf := make([]byte, 64)
		for {
			if _, err := clientPeer.Read(buf); err != nil {
				return
			}
		}
	}()

	daemonSide, pumpSide := net.Pipe()
	go h.pumpDaemonFrames(pumpSide)
	time.Sleep(20 * time.Millisecond)

	writeFrame := func(flags int32, data []byte) {
		var hdr [16]byte
		binary.BigEndian.PutUint32(hdr[0:4], uint32(len(data)))
		binary.BigEndian.PutUint32(hdr[4:8], uint32(flags))
		binary.BigEndian.PutUint64(hdr[8:16], 0)
		_, _ = daemonSide.Write(hdr[:])
		_, _ = daemonSide.Write(data)
	}
	writeFrame(2, []byte{0xAA, 0xBB}) // SPS/PPS
	time.Sleep(30 * time.Millisecond)
	writeFrame(1, []byte{0xCC, 0xDD}) // IDR
	time.Sleep(50 * time.Millisecond)

	h.mu.Lock()
	hasSps := len(h.spsPps) > 0
	hasIdr := len(h.lastIDR) > 0
	h.mu.Unlock()
	if !hasSps || !hasIdr {
		t.Fatalf("expected cached sps/pps and idr, got sps=%v idr=%v", hasSps, hasIdr)
	}
}

// The decisive regression: after a SUCCESSFUL connection, the daemon restarts and drops the
// link. The legacy loop had already returned, so the console stayed black forever. The new
// supervisor must reconnect while a watcher remains.
func TestHubReconnectsAfterMidStreamDaemonRestart(t *testing.T) {
	h := &StreamHub{clients: make(map[net.Conn]struct{})}
	c1, c2 := net.Pipe()
	defer c1.Close()
	defer c2.Close()
	h.clients[c1] = struct{}{}

	// First listener generation.
	ln, err := net.Listen("tcp", "127.0.0.1:3071")
	if err != nil {
		t.Skipf("3071 unavailable: %v", err)
	}

	acceptN := func(gen int, ch chan<- net.Conn) {
		conn, err := ln.Accept()
		if err == nil {
			ch <- conn
		}
	}
	gen1 := make(chan net.Conn, 1)
	go acceptN(1, gen1)

	go h.connectDaemonLoop()

	var first net.Conn
	select {
	case first = <-gen1:
	case <-time.After(3 * time.Second):
		t.Fatal("no initial connection")
	}
	time.Sleep(100 * time.Millisecond)

	// Simulate daemon restart: kill listener + existing conn, reopen on same port.
	_ = first.Close()
	_ = ln.Close()
	time.Sleep(300 * time.Millisecond)
	ln2, err := net.Listen("tcp", "127.0.0.1:3071")
	if err != nil {
		t.Fatalf("could not reopen listener: %v", err)
	}
	defer ln2.Close()
	gen2 := make(chan net.Conn, 1)
	go func() {
		conn, err := ln2.Accept()
		if err == nil {
			gen2 <- conn
		}
	}()

	select {
	case <-gen2:
		// reconnected after restart
	case <-time.After(5 * time.Second):
		t.Fatal("supervisor did not reconnect after mid-stream daemon restart")
	}

	h.mu.Lock()
	delete(h.clients, c1)
	h.mu.Unlock()
}
