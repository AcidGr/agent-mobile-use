package main

import (
	"bufio"
	"crypto/sha1"
	_ "embed"
	"encoding/base64"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"image"
	"image/jpeg"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"regexp"
	"strconv"
	"strings"
	"sync"
	"syscall"
	"time"
)

//go:embed index.html
var indexHTML []byte

const (
	statusFile = "/data/local/tmp/vd_status.json"
	stopSignal = "/data/local/tmp/vd_stop"
)

var reSfDisplay = regexp.MustCompile(`Display\s+([0-9]+).*Agent.*VirtualDisplay`)

type StatusResp struct {
	Status          string `json:"status"`
	DisplayID       int    `json:"display_id"`
	PID             int    `json:"pid"`
	Width           int    `json:"width"`
	Height          int    `json:"height"`
	DPI             int    `json:"dpi"`
	Mode            string `json:"mode"`
	TargetDisplayID int    `json:"target_display_id"`
}

var (
	modeMu      sync.Mutex
	currentMode = "idle" // "idle" (default / unfocused, display -1), "background", or "foreground"

	noticeMu             sync.Mutex
	pendingHandoffNotice string

	questionMu      sync.Mutex
	activeQuestions = make(map[string]chan *QuestionAnswerPayload)
)

type QuestionAnswerPayload struct {
	RequestID string `json:"request_id"`
	Answers   []any  `json:"answers"`
}

func writeWSBinaryFrame(w io.Writer, payload []byte) error {
	n := len(payload)
	var header []byte
	if n < 126 {
		header = []byte{0x82, byte(n)}
	} else if n <= 65535 {
		header = []byte{0x82, 126, byte(n >> 8), byte(n)}
	} else {
		header = make([]byte, 10)
		header[0] = 0x82
		header[1] = 127
		binary.BigEndian.PutUint64(header[2:], uint64(n))
	}
	if _, err := w.Write(header); err != nil {
		return err
	}
	_, err := w.Write(payload)
	return err
}

type StreamHub struct {
	mu         sync.Mutex
	clients    map[net.Conn]struct{}
	daemonConn net.Conn
	spsPps     []byte
	lastIDR    []byte
}

var hub = &StreamHub{
	clients: make(map[net.Conn]struct{}),
}

func (h *StreamHub) register(conn net.Conn) {
	h.mu.Lock()
	h.clients[conn] = struct{}{}
	count := len(h.clients)
	sps := h.spsPps
	idr := h.lastIDR
	h.mu.Unlock()

	fmt.Printf("[StreamHub] Client connected, total watchers: %d\n", count)

	if len(sps) > 0 {
		_ = writeWSBinaryFrame(conn, sps)
	}
	if len(idr) > 0 {
		_ = writeWSBinaryFrame(conn, idr)
	}

	if count == 1 {
		go h.connectDaemonLoop()
	}
}

func (h *StreamHub) unregister(conn net.Conn) {
	h.mu.Lock()
	delete(h.clients, conn)
	_ = conn.Close()
	count := len(h.clients)
	if count == 0 && h.daemonConn != nil {
		_ = h.daemonConn.Close()
		h.daemonConn = nil
		fmt.Printf("[StreamHub] All watchers disconnected, released hardware encoder\n")
	}
	h.mu.Unlock()
	fmt.Printf("[StreamHub] Client disconnected, remaining watchers: %d\n", count)
}

func (h *StreamHub) broadcast(msg []byte) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for c := range h.clients {
		_ = c.SetWriteDeadline(time.Now().Add(250 * time.Millisecond))
		if err := writeWSBinaryFrame(c, msg); err != nil {
			_ = c.Close()
			delete(h.clients, c)
		}
	}
}

func (h *StreamHub) connectDaemonLoop() {
	var conn net.Conn
	var err error
	for i := 0; i < 30; i++ {
		h.mu.Lock()
		clientCount := len(h.clients)
		h.mu.Unlock()
		if clientCount == 0 {
			return
		}
		conn, err = net.DialTimeout("tcp", "127.0.0.1:3071", 300*time.Millisecond)
		if err == nil {
			break
		}
		time.Sleep(150 * time.Millisecond)
	}

	if conn == nil {
		fmt.Printf("[StreamHub] Failed to connect to daemon stream on 127.0.0.1:3071: %v\n", err)
		return
	}

	h.mu.Lock()
	if len(h.clients) == 0 {
		_ = conn.Close()
		h.mu.Unlock()
		return
	}
	h.daemonConn = conn
	h.mu.Unlock()

	fmt.Printf("[StreamHub] Connected to hardware stream on 127.0.0.1:3071\n")
	buf := make([]byte, 1024*1024)

	for {
		var size int32
		var flags int32
		var pts int64

		if err := binary.Read(conn, binary.BigEndian, &size); err != nil {
			break
		}
		if err := binary.Read(conn, binary.BigEndian, &flags); err != nil {
			break
		}
		if err := binary.Read(conn, binary.BigEndian, &pts); err != nil {
			break
		}

		if size <= 0 || int(size) > len(buf) {
			break
		}

		if _, err := io.ReadFull(conn, buf[:size]); err != nil {
			break
		}

		isKey := byte(0)
		if (flags & 3) != 0 {
			isKey = 1
		}

		msg := make([]byte, 1+size)
		msg[0] = isKey
		copy(msg[1:], buf[:size])

		if (flags & 2) != 0 {
			h.mu.Lock()
			h.spsPps = msg
			h.mu.Unlock()
		} else if (flags & 1) != 0 {
			h.mu.Lock()
			h.lastIDR = msg
			h.mu.Unlock()
		}

		h.broadcast(msg)
	}

	h.mu.Lock()
	if h.daemonConn == conn {
		h.daemonConn = nil
	}
	_ = conn.Close()
	h.mu.Unlock()
	fmt.Printf("[StreamHub] Hardware stream loop ended\n")
}

func setPendingHandoffNotice(notice string) {
	noticeMu.Lock()
	defer noticeMu.Unlock()
	pendingHandoffNotice = notice
}

func popPendingHandoffNotice() string {
	noticeMu.Lock()
	defer noticeMu.Unlock()
	n := pendingHandoffNotice
	pendingHandoffNotice = ""
	return n
}

func injectNoticeHeader(w http.ResponseWriter) {
	if n := popPendingHandoffNotice(); n != "" {
		w.Header().Set("X-Agent-Notice", url.QueryEscape(n))
		w.Header().Set("Access-Control-Expose-Headers", "X-Agent-Notice")
	}
}

func getCurrentMode() string {
	modeMu.Lock()
	defer modeMu.Unlock()
	return currentMode
}

func setCurrentMode(m string) string {
	lower := strings.ToLower(strings.TrimSpace(m))
	var mode string
	modeMu.Lock()
	if lower == "foreground" || lower == "fg" || lower == "0" {
		currentMode = "foreground"
	} else if lower == "idle" || lower == "standby" || lower == "none" || lower == "-1" {
		currentMode = "idle"
	} else {
		currentMode = "background"
	}
	mode = currentMode
	modeMu.Unlock()

	go updateCapsuleState()
	return mode
}

var (
	cachedAppUID string
	appUIDMu     sync.Mutex
)

func getAppUID() string {
	appUIDMu.Lock()
	defer appUIDMu.Unlock()
	if cachedAppUID != "" {
		return cachedAppUID
	}
	out, err := exec.Command("/system/bin/cmd", "package", "list", "packages", "-U", "com.agent.mobileuse").Output()
	if err == nil {
		str := string(out)
		if idx := strings.Index(str, "uid:"); idx >= 0 {
			uidStr := strings.TrimSpace(str[idx+4:])
			if fields := strings.Fields(uidStr); len(fields) > 0 {
				cachedAppUID = fields[0]
				return cachedAppUID
			}
		}
	}
	return ""
}

func thawAppProcess() {
	_ = exec.Command("/system/bin/cmd", "activity", "unfreeze", "--sticky", "com.agent.mobileuse").Run()
	if uid := getAppUID(); uid != "" {
		_ = exec.Command("/system/bin/sh", "-c", fmt.Sprintf("echo 0 > /sys/fs/cgroup/apps/uid_%s/cgroup.freeze 2>/dev/null; echo 0 > /sys/fs/cgroup/uid_%s/cgroup.freeze 2>/dev/null", uid, uid)).Run()
	}
}

var (
	viewStateMu             sync.Mutex
	isOverlayForeground     bool
	currentViewingSessionID string

	sessionActiveMu sync.Mutex
	isSessionActive bool

	sessionMetaMu      sync.Mutex
	activeSessionID    string
	activeSessionTitle string

	activeSessionsMu sync.Mutex
	activeSessions   = make(map[string]SessionMeta)

	lastAppliedCapsuleAction   string
	lastAppliedCapsuleActionMu sync.Mutex

	lastCompletedSessionID   string
	lastCompletedSessionIDMu sync.Mutex
)

type SessionMeta struct {
	ID    string
	Title string
}

func setSessionActive(active bool, sid string, title string) {
	activeSessionsMu.Lock()
	if active {
		if sid != "" {
			if title == "" || title == "移动端任务" || title == "闲聊" {
				if existing, ok := activeSessions[sid]; ok && existing.Title != "" {
					title = existing.Title
				} else {
					title = "移动端任务"
				}
			}
			activeSessions[sid] = SessionMeta{ID: sid, Title: title}
		}
	} else {
		if sid != "" {
			delete(activeSessions, sid)
		} else {
			activeSessions = make(map[string]SessionMeta)
		}
	}

	remaining := len(activeSessions)
	var latestSid, latestTitle string
	if remaining > 0 {
		if sid != "" && active {
			latestSid = sid
			latestTitle = title
		} else {
			for _, m := range activeSessions {
				latestSid = m.ID
				latestTitle = m.Title
				break
			}
		}
	}
	activeSessionsMu.Unlock()

	sessionActiveMu.Lock()
	isSessionActive = (remaining > 0)
	sessionActiveMu.Unlock()

	if remaining == 0 {
		modeMu.Lock()
		currentMode = "idle"
		modeMu.Unlock()
		syncA11yWithTargetDisplay(-1)
	}

	sessionMetaMu.Lock()
	if isSessionActive {
		activeSessionID = latestSid
		activeSessionTitle = latestTitle
		if latestSid != "" {
			lastCompletedSessionIDMu.Lock()
			if lastCompletedSessionID != "" && (lastCompletedSessionID == latestSid || strings.Contains(latestSid, lastCompletedSessionID) || strings.Contains(lastCompletedSessionID, latestSid)) {
				lastCompletedSessionID = ""
				lastCompletedSessionIDMu.Unlock()
				go clearCompletedOnDevice()
			} else {
				lastCompletedSessionIDMu.Unlock()
			}
		}
	} else {
		activeSessionID = ""
		activeSessionTitle = ""
	}
	sessionMetaMu.Unlock()

	go updateCapsuleState()
}

func getSessionActive() bool {
	sessionActiveMu.Lock()
	defer sessionActiveMu.Unlock()
	return isSessionActive
}

func getSessionMeta() (string, string) {
	sessionMetaMu.Lock()
	defer sessionMetaMu.Unlock()
	return activeSessionID, activeSessionTitle
}

func isGlowServiceAlive() bool {
	cmd := exec.Command("/system/bin/sh", "-c", `dumpsys activity services com.agent.mobileuse/.GlowService | grep -q "app=ProcessRecord"`)
	return cmd.Run() == nil
}

func resolveCapsuleAction() string {
	if !getSessionActive() {
		return "STOP" // P5: 待机/未运行，彻底注销胶囊与光效
	}
	mode := getCurrentMode()
	if mode == "foreground" {
		return "START_FOREGROUND" // P1: 前台接管中 (blue eye + glow, display 0)
	}
	if mode == "background" {
		return "START_BACKGROUND" // P2: 后台接管 (blue eye + no glow, display > 0)
	}
	return "START_RUNNING" // P3: 会话运行中 (cyber green terminal >_, no glow, display -1)
}

var (
	lastGlowPID             int
	lastAppliedCapsuleTitle string
)

func getGlowPID() int {
	out, err := exec.Command("/system/bin/pidof", "com.agent.mobileuse").Output()
	if err == nil {
		fields := strings.Fields(string(out))
		if len(fields) > 0 {
			if pid, err := strconv.Atoi(fields[0]); err == nil {
				return pid
			}
		}
	}
	return 0
}

func updateCapsuleState() {
	lastAppliedCapsuleActionMu.Lock()
	defer lastAppliedCapsuleActionMu.Unlock()

	action := resolveCapsuleAction()
	curPID := getGlowPID()

	sid, title := getSessionMeta()

	if action == "STOP" {
		if !isGlowServiceAlive() && lastAppliedCapsuleAction == "STOP" {
			return
		}
	} else {
		// Only early-return if action, process AND title are identical!
		if lastAppliedCapsuleAction == action && isGlowServiceAlive() && (curPID > 0 && curPID == lastGlowPID) && (lastAppliedCapsuleTitle == title) {
			return
		}
	}
	lastAppliedCapsuleAction = action
	lastAppliedCapsuleTitle = title
	lastGlowPID = curPID

	thawAppProcess()
	cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am start -f 0x18000000 -n com.agent.mobileuse/.QuestionActivity --es capsule_action "$CAPSULE_ACTION" --es session_id "$CAPSULE_SID" --es session_title "$CAPSULE_TITLE" 2>/dev/null`)
	cmd.Env = append(os.Environ(),
		"CAPSULE_ACTION="+action,
		"CAPSULE_SID="+sid,
		"CAPSULE_TITLE="+title,
	)
	_ = cmd.Run()
}

func cancelQuestionOnDevice(reqID string) {
	thawAppProcess()
	exec.Command("/system/bin/sh", "-c", `/system/bin/am start -f 0x18000000 -n com.agent.mobileuse/.QuestionActivity --ez cancel_question true --es request_id "`+reqID+`" 2>/dev/null`).Run()
}

func clearCompletedOnDevice() {
	thawAppProcess()
	exec.Command("/system/bin/sh", "-c", `/system/bin/am start -f 0x18000000 -n com.agent.mobileuse/.QuestionActivity --ez clear_completed true 2>/dev/null`).Run()
}

func startCapsuleWatchdog() {
	go func() {
		ticker := time.NewTicker(1 * time.Second)
		defer ticker.Stop()

		var failCount int
		for range ticker.C {
			if !getSessionActive() {
				failCount = 0
				continue
			}
			pid := getGlowPID()
			if pid <= 0 || !isGlowServiceAlive() {
				// ponytail: simple 5-strike cooldown ceiling, add exponential backoff if crash-loop occurs
				if failCount >= 5 {
					time.Sleep(5 * time.Second)
					failCount = 0
					continue
				}
				failCount++
				fmt.Printf("[capsule-watchdog] GlowService died while session is active (pid=%d). Resurrecting...\n", pid)
				updateCapsuleState()
			} else {
				failCount = 0
			}
		}
	}()
}

func broadcastTouch(touchType int, x, y, x1, y1, x2, y2, duration int) {
	if getCurrentMode() != "foreground" {
		return
	}
	if touchType == 1 {
		cmd := fmt.Sprintf("am broadcast -a com.agent.mobileuse.ACTION_TOUCH -p com.agent.mobileuse --ei type 1 --ei x %d --ei y %d", x, y)
		go exec.Command("/system/bin/sh", "-c", cmd).Run()
	} else if touchType == 2 {
		cmd := fmt.Sprintf("am broadcast -a com.agent.mobileuse.ACTION_TOUCH -p com.agent.mobileuse --ei type 2 --ei x1 %d --ei y1 %d --ei x2 %d --ei y2 %d --ei duration %d", x1, y1, x2, y2, duration)
		go exec.Command("/system/bin/sh", "-c", cmd).Run()
	}
}

func getTargetDisplayID(st StatusResp) int {
	mode := getCurrentMode()
	if mode == "foreground" {
		return 0
	} else if mode == "idle" {
		return -1
	}
	return st.DisplayID
}

type StackInfo struct {
	StackID   int
	DisplayID int
	UserID    int
	Packages  []string
	TopAct    string
	Visible   bool
}

func getStackList() []StackInfo {
	out, err := exec.Command("/system/bin/cmd", "activity", "stack", "list").Output()
	if err != nil {
		return nil
	}
	lines := strings.Split(string(out), "\n")
	var list []StackInfo
	var cur *StackInfo

	reRoot := regexp.MustCompile(`RootTask id=(\d+).*displayId=(\d+)`)
	reTask := regexp.MustCompile(`taskId=(\d+):\s+([^/]+)/([^\s]+).*visible=(true|false)`)
	reUser := regexp.MustCompile(`userId=(\d+)`)

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if m := reRoot.FindStringSubmatch(line); len(m) > 2 {
			if cur != nil {
				list = append(list, *cur)
			}
			sId, _ := strconv.Atoi(m[1])
			dId, _ := strconv.Atoi(m[2])
			uId := 0
			if mu := reUser.FindStringSubmatch(line); len(mu) > 1 {
				uId, _ = strconv.Atoi(mu[1])
			}
			cur = &StackInfo{
				StackID:   sId,
				DisplayID: dId,
				UserID:    uId,
			}
		} else if cur != nil {
			if cur.UserID == 0 {
				if mu := reUser.FindStringSubmatch(line); len(mu) > 1 {
					cur.UserID, _ = strconv.Atoi(mu[1])
				}
			}
			if m := reTask.FindStringSubmatch(line); len(m) > 4 {
				cur.Packages = append(cur.Packages, m[2])
				if cur.TopAct == "" {
					cur.TopAct = m[2] + "/" + m[3]
				}
				if m[4] == "true" {
					cur.Visible = true
				}
			}
		}
	}
	if cur != nil {
		list = append(list, *cur)
	}
	return list
}

func findStackForPackageAndActivity(pkg, act string, userId int) (StackInfo, bool) {
	stacks := getStackList()
	if act != "" {
		for _, s := range stacks {
			if s.UserID == userId && strings.Contains(s.TopAct, pkg) && strings.Contains(s.TopAct, act) {
				return s, true
			}
		}
	}
	for _, s := range stacks {
		if s.UserID == userId {
			for _, p := range s.Packages {
				if p == pkg {
					return s, true
				}
			}
		}
	}
	return StackInfo{}, false
}

func getTopAppStackOnDisplay(displayId int) (StackInfo, bool) {
	stacks := getStackList()
	for _, s := range stacks {
		if s.DisplayID == displayId {
			// The first stack encountered on this display is its top stack
			for _, p := range s.Packages {
				if strings.Contains(p, "launcher") || strings.Contains(p, "systemui") {
					// Top of display is launcher or systemui
					return StackInfo{}, false
				}
			}
			if len(s.Packages) > 0 {
				return s, true
			}
			return StackInfo{}, false
		}
	}
	return StackInfo{}, false
}

func migrateTopStack(fromDisplayId int, toDisplayId int) (string, int) {
	if fromDisplayId < 0 || toDisplayId < 0 || fromDisplayId == toDisplayId {
		return "", 0
	}
	migratedComponent := ""
	migratedTaskId := 0

	if topStack, ok := getTopAppStackOnDisplay(fromDisplayId); ok {
		migratedComponent = topStack.TopAct
		migratedTaskId = topStack.StackID
	} else if fromDisplayId == 0 {
		out, _ := exec.Command("/system/bin/sh", "-c", `dumpsys activity activities | grep -A 8 "Display #0" | grep "topResumedActivity"`).Output()
		re := regexp.MustCompile(`topResumedActivity=ActivityRecord\{[0-9a-fA-F]+\s+u0\s+([a-zA-Z0-9._]+/[a-zA-Z0-9._]+)\s+t(\d+)`)
		if m := re.FindStringSubmatch(string(out)); len(m) > 2 {
			comp := m[1]
			if !strings.Contains(comp, "launcher") && !strings.Contains(comp, "systemui") {
				migratedComponent = comp
				migratedTaskId, _ = strconv.Atoi(m[2])
			}
		}
	}

	if migratedTaskId > 0 {
		cmd := exec.Command("/system/bin/cmd", "activity", "display", "move-stack", strconv.Itoa(migratedTaskId), strconv.Itoa(toDisplayId))
		out, err := cmd.CombinedOutput()
		if err != nil {
			fmt.Printf("[migrate] move-stack %d from %d to %d error: %v, out: %s\n", migratedTaskId, fromDisplayId, toDisplayId, err, string(out))
		} else {
			fmt.Printf("[migrate] Successfully moved stack %d (%s) from display %d to %d\n", migratedTaskId, migratedComponent, fromDisplayId, toDisplayId)
		}
	}
	return migratedComponent, migratedTaskId
}

func switchModeWithMigration(target string) map[string]interface{} {
	oldMode := getCurrentMode()
	lower := strings.ToLower(strings.TrimSpace(target))
	var newMode string
	if lower == "foreground" || lower == "fg" || lower == "0" {
		newMode = "foreground"
	} else if lower == "idle" || lower == "standby" || lower == "none" || lower == "-1" {
		newMode = "idle"
	} else {
		newMode = "background"
	}

	st := getStatus()
	vdDid := st.DisplayID

	// If switching to background from any mode, ensure virtual display is running
	if newMode == "background" {
		if st.Status != "running" || vdDid <= 0 {
			st = startVirtualDisplay()
			vdDid = st.DisplayID
		}
	}

	migratedComp := ""
	migratedTaskId := 0

	// Migrate app stack ONLY between 0 (foreground) and vd (background); -1 (idle) never migrates
	if oldMode == "foreground" && newMode == "background" && vdDid > 0 {
		migratedComp, migratedTaskId = migrateTopStack(0, vdDid)
		setPendingHandoffNotice("[System Notice: Mode was set to background by user. Keep running silently in the background; do not change display mode.]")
	} else if oldMode == "background" && newMode == "foreground" && vdDid > 0 {
		migratedComp, migratedTaskId = migrateTopStack(vdDid, 0)
	}

	setCurrentMode(newMode)
	st = getStatus()
	targetDid := getTargetDisplayID(st)
	syncA11yWithTargetDisplay(targetDid)

	if newMode == "background" && vdDid > 0 {
		reconcileVdAudio(vdDid)
	} else if newMode == "foreground" || newMode == "idle" {
		unmuteAllAudio()
	}

	msg := fmt.Sprintf("Current mode is %s (Target Display %d)", newMode, targetDid)
	if newMode == "idle" {
		msg = "Current mode is idle (No focused display, Display -1)"
	}

	return map[string]interface{}{
		"success":            true,
		"mode":               newMode,
		"target_display_id":  targetDid,
		"migrated_component": migratedComp,
		"migrated_task_id":   migratedTaskId,
		"message":            msg,
	}
}

func ensureTargetReady() (StatusResp, int, error) {
	mode := getCurrentMode()
	go updateCapsuleState()

	if mode == "idle" {
		return getStatus(), -1, fmt.Errorf("Agent is currently in idle mode (no focused display, display -1). Please switch mode to 'foreground' or 'background' first")
	}
	if mode == "foreground" {
		return getStatus(), 0, nil
	}

	// mode is background
	st := getStatus()
	if st.Status != "running" || st.DisplayID <= 0 {
		st = startVirtualDisplay()
		if st.Status != "running" || st.DisplayID <= 0 {
			return st, -1, fmt.Errorf("Virtual display not running")
		}
	}
	return st, st.DisplayID, nil
}

func getStatus() StatusResp {
	resp := StatusResp{Status: "stopped", DisplayID: -1}
	data, err := os.ReadFile(statusFile)
	if err == nil {
		var parsed StatusResp
		if err := json.Unmarshal(data, &parsed); err == nil {
			resp = parsed
			if resp.Status == "running" && resp.PID > 0 {
				process, err := os.FindProcess(resp.PID)
				if err != nil || process.Signal(syscall.Signal(0)) != nil {
					resp.Status = "stopped"
					resp.DisplayID = -1
				} else {
					cmdline, err := os.ReadFile(fmt.Sprintf("/proc/%d/cmdline", resp.PID))
					if err != nil || !strings.Contains(string(cmdline), "DaemonMain") {
						resp.Status = "stopped"
						resp.DisplayID = -1
					}
				}
			}
		}
	}
	resp.Mode = getCurrentMode()
	resp.TargetDisplayID = getTargetDisplayID(resp)
	return resp
}

// displaySize resolves the pixel size of the display the agent is currently driving.
// For the virtual display the daemon already knows it; for the physical display it is
// parsed from `wm size`, which reports the override or physical resolution.
func displaySize(targetDid int, st StatusResp) (int, int) {
	if targetDid != 0 {
		if st.Width > 0 && st.Height > 0 {
			return st.Width, st.Height
		}
		return 0, 0
	}
	out, err := exec.Command("/system/bin/wm", "size").Output()
	if err != nil {
		return 0, 0
	}
	re := regexp.MustCompile(`([0-9]+)x([0-9]+)`)
	matches := re.FindAllStringSubmatch(string(out), -1)
	if len(matches) == 0 {
		return 0, 0
	}
	// When an override is active `wm size` prints "Override size: WxH" first; the last
	// match is the effective one either way.
	last := matches[len(matches)-1]
	w, _ := strconv.Atoi(last[1])
	h, _ := strconv.Atoi(last[2])
	return w, h
}

func getSfDisplayID() string {
	out, err := exec.Command("/system/bin/dumpsys", "SurfaceFlinger", "--display-id").Output()
	if err != nil {
		return ""
	}
	matches := reSfDisplay.FindStringSubmatch(string(out))
	if len(matches) > 1 {
		return matches[1]
	}
	return ""
}

func startVirtualDisplay() StatusResp {
	st := getStatus()
	if st.Status == "running" {
		return st
	}
	_ = os.Remove(stopSignal)

	runScript := "/data/adb/modules/agent_mobile_use/bin/run_daemon.sh"
	if _, err := os.Stat(runScript); err != nil {
		runScript = "/data/local/tmp/run_daemon.sh"
	}

	logFile, _ := os.OpenFile("/data/local/tmp/daemon.log", os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0644)
	cmd := exec.Command("/system/bin/sh", runScript)
	if logFile != nil {
		cmd.Stdout = logFile
		cmd.Stderr = logFile
	}
	cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true}
	_ = cmd.Start()

	for i := 0; i < 30; i++ {
		time.Sleep(100 * time.Millisecond)
		st = getStatus()
		if st.Status == "running" {
			syncA11yWithTargetDisplay(getTargetDisplayID(st))
			return st
		}
	}
	return getStatus()
}

func stopVirtualDisplay() StatusResp {
	_ = os.WriteFile(stopSignal, []byte("1"), 0644)
	for i := 0; i < 20; i++ {
		time.Sleep(100 * time.Millisecond)
		st := getStatus()
		if st.Status == "stopped" {
			syncA11yWithTargetDisplay(getTargetDisplayID(st))
			return st
		}
	}
	st := getStatus()
	if st.PID > 0 {
		proc, err := os.FindProcess(st.PID)
		if err == nil {
			_ = proc.Kill()
		}
	}
	_ = os.WriteFile(statusFile, []byte(`{"status":"stopped","display_id":-1}`), 0644)
	syncA11yWithTargetDisplay(getTargetDisplayID(getStatus()))
	return getStatus()
}

// ── Accessibility service lifecycle guard ──
// Certain apps (e.g. WeChat) only expose their accessibility node tree while a real
// service is bound. We attach SelectToSpeakService when mode is active (targetDid >= 0)
// and cleanly detach it when entering idle mode (targetDid < 0) or shutting down,
// preserving any other user-enabled accessibility services.
const (
	a11yService    = "com.google.android.marvin.talkback/com.google.android.accessibility.selecttospeak.SelectToSpeakService"
	a11yKey        = "enabled_accessibility_services"
	a11yMarkerFile = "/data/local/tmp/vd_a11y_attached"
)

var (
	a11yAttachedByUs bool
	a11yMu           sync.Mutex
)

func readSecure(key string) string {
	out, err := exec.Command("/system/bin/settings", "get", "secure", key).Output()
	if err != nil {
		return ""
	}
	v := strings.TrimSpace(string(out))
	if v == "null" {
		return ""
	}
	return v
}

func writeSecure(key, value string) {
	_ = exec.Command("/system/bin/settings", "put", "secure", key, value).Run()
}

func deleteSecure(key string) {
	_ = exec.Command("/system/bin/settings", "delete", "secure", key).Run()
}

func ensureA11yServiceAttached() {
	a11yMu.Lock()
	defer a11yMu.Unlock()

	orig := readSecure(a11yKey)
	parts := strings.Split(orig, ":")
	for _, p := range parts {
		if strings.TrimSpace(p) == a11yService {
			return
		}
	}
	merged := a11yService
	if orig != "" {
		merged = orig + ":" + a11yService
	}
	writeSecure(a11yKey, merged)
	a11yAttachedByUs = true
	_ = os.WriteFile(a11yMarkerFile, []byte("1"), 0644)
}

func ensureA11yServiceDetached() {
	a11yMu.Lock()
	defer a11yMu.Unlock()

	if _, err := os.Stat(a11yMarkerFile); !a11yAttachedByUs && err != nil {
		return
	}
	orig := readSecure(a11yKey)
	parts := strings.Split(orig, ":")
	var remaining []string
	for _, p := range parts {
		trimmed := strings.TrimSpace(p)
		if trimmed != "" && trimmed != a11yService {
			remaining = append(remaining, trimmed)
		}
	}
	if len(remaining) == 0 {
		deleteSecure(a11yKey)
	} else {
		writeSecure(a11yKey, strings.Join(remaining, ":"))
	}
	a11yAttachedByUs = false
	_ = os.Remove(a11yMarkerFile)
}

func getToolEnv(dexPath string) []string {
	return append(os.Environ(),
		"ANDROID_ROOT=/system",
		"ANDROID_DATA=/data",
		"ANDROID_ART_ROOT=/apex/com.android.art",
		"ANDROID_I18N_ROOT=/apex/com.android.i18n",
		"ANDROID_TZDATA_ROOT=/apex/com.android.tzdata",
		"BOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.adservices/javalib/framework-adservices.jar:/apex/com.android.adservices/javalib/framework-sdksandbox.jar:/apex/com.android.appsearch/javalib/framework-appsearch.jar:/apex/com.android.configinfrastructure/javalib/framework-configinfrastructure.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar:/apex/com.android.crashrecovery/javalib/framework-crashrecovery.jar:/apex/com.android.devicelock/javalib/framework-devicelock.jar:/apex/com.android.healthfitness/javalib/framework-healthfitness.jar:/apex/com.android.ipsec/javalib/android.net.ipsec.ike.jar:/apex/com.android.media/javalib/updatable-media.jar:/apex/com.android.mediaprovider/javalib/framework-mediaprovider.jar:/apex/com.android.mediaprovider/javalib/framework-pdf.jar:/apex/com.android.mediaprovider/javalib/framework-pdf-v.jar:/apex/com.android.mediaprovider/javalib/framework-photopicker.jar:/apex/com.android.ondevicepersonalization/javalib/framework-ondevicepersonalization.jar:/apex/com.android.os.statsd/javalib/framework-statsd.jar:/apex/com.android.permission/javalib/framework-permission.jar:/apex/com.android.permission/javalib/framework-permission-s.jar:/apex/com.android.profiling/javalib/framework-profiling.jar:/apex/com.android.scheduling/javalib/framework-scheduling.jar:/apex/com.android.sdkext/javalib/framework-sdkextensions.jar:/apex/com.android.tethering/javalib/framework-connectivity.jar:/apex/com.android.tethering/javalib/framework-connectivity-b.jar:/apex/com.android.tethering/javalib/framework-connectivity-t.jar:/apex/com.android.tethering/javalib/framework-tethering.jar:/apex/com.android.uwb/javalib/framework-ranging.jar:/apex/com.android.uwb/javalib/framework-uwb.jar:/apex/com.android.virt/javalib/framework-virtualization.jar:/apex/com.android.wifi/javalib/framework-wifi.jar",
		"DEX2OATBOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar",
		"CLASSPATH="+dexPath,
	)
}

type DumpDaemonManager struct {
	mu     sync.Mutex
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	reader *bufio.Reader
	ready  bool
}

var globalDumpDaemon = &DumpDaemonManager{}

func (d *DumpDaemonManager) isRunningLocked() bool {
	if d.cmd == nil || d.cmd.Process == nil || !d.ready {
		return false
	}
	if err := d.cmd.Process.Signal(syscall.Signal(0)); err != nil {
		return false
	}
	return true
}

func (d *DumpDaemonManager) Start() error {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.startLocked()
}

func (d *DumpDaemonManager) startLocked() error {
	if d.isRunningLocked() {
		return nil
	}
	d.stopLocked()

	dexPath := "/data/adb/modules/agent_mobile_use/bin/agent_tools.dex"
	if _, err := os.Stat(dexPath); err != nil {
		dexPath = "/data/local/tmp/agent_tools.dex"
	}

	cmdArgs := []string{"/system/bin", "com.agent.ToolMain", "daemon"}
	cmd := exec.Command("/system/bin/app_process", cmdArgs...)
	cmd.Env = getToolEnv(dexPath)

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return fmt.Errorf("DumpDaemon StdinPipe: %v", err)
	}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		stdin.Close()
		return fmt.Errorf("DumpDaemon StdoutPipe: %v", err)
	}

	cmd.Stderr = os.Stderr

	if err := cmd.Start(); err != nil {
		stdin.Close()
		stdout.Close()
		return fmt.Errorf("DumpDaemon start: %v", err)
	}

	reader := bufio.NewReader(stdout)

	readyCh := make(chan error, 1)
	go func() {
		line, err := reader.ReadString('\n')
		if err != nil {
			readyCh <- err
			return
		}
		if strings.TrimSpace(line) != "READY" {
			readyCh <- fmt.Errorf("unexpected handshake line: %q", line)
			return
		}
		readyCh <- nil
	}()

	select {
	case err := <-readyCh:
		if err != nil {
			_ = cmd.Process.Kill()
			stdin.Close()
			stdout.Close()
			_ = cmd.Wait()
			return fmt.Errorf("DumpDaemon handshake: %v", err)
		}
	case <-time.After(5 * time.Second):
		_ = cmd.Process.Kill()
		stdin.Close()
		stdout.Close()
		_ = cmd.Wait()
		return fmt.Errorf("DumpDaemon handshake timeout")
	}

	d.cmd = cmd
	d.stdin = stdin
	d.reader = reader
	d.ready = true
	log.Printf("[DumpDaemon] Started successfully (PID %d)", cmd.Process.Pid)
	return nil
}

func (d *DumpDaemonManager) Stop() {
	d.mu.Lock()
	defer d.mu.Unlock()
	d.stopLocked()
}

func (d *DumpDaemonManager) stopLocked() {
	if d.cmd == nil || d.cmd.Process == nil {
		d.ready = false
		d.cmd = nil
		d.stdin = nil
		d.reader = nil
		return
	}

	if d.stdin != nil {
		_, _ = d.stdin.Write([]byte("quit\n"))
		_ = d.stdin.Close()
	}

	done := make(chan error, 1)
	go func(c *exec.Cmd) {
		done <- c.Wait()
	}(d.cmd)

	select {
	case <-done:
	case <-time.After(1 * time.Second):
		_ = d.cmd.Process.Kill()
		<-done
	}

	log.Printf("[DumpDaemon] Stopped.")
	d.cmd = nil
	d.stdin = nil
	d.reader = nil
	d.ready = false
}

func (d *DumpDaemonManager) Request(cmdLine string) (string, error) {
	d.mu.Lock()
	defer d.mu.Unlock()

	if !d.isRunningLocked() {
		if err := d.startLocked(); err != nil {
			return "", err
		}
	}

	if _, err := d.stdin.Write([]byte(cmdLine + "\n")); err != nil {
		d.stopLocked()
		return "", fmt.Errorf("write command failed: %v", err)
	}

	var sb strings.Builder
	for {
		line, err := d.reader.ReadString('\n')
		if err != nil {
			d.stopLocked()
			return "", fmt.Errorf("read response failed: %v", err)
		}
		trimmed := strings.TrimSpace(line)
		if trimmed == "<<<END_OF_DUMP>>>" {
			break
		}
		sb.WriteString(line)
	}

	return sb.String(), nil
}

func syncA11yWithTargetDisplay(targetDid int) {
	if targetDid >= 0 {
		ensureA11yServiceAttached()
		go func() {
			if err := globalDumpDaemon.Start(); err != nil {
				log.Printf("[DumpDaemon] Background start error: %v", err)
			}
		}()
	} else {
		ensureA11yServiceDetached()
		globalDumpDaemon.Stop()
	}
}

func runTool(args ...string) (string, error) {
	dexPath := "/data/adb/modules/agent_mobile_use/bin/agent_tools.dex"
	if _, err := os.Stat(dexPath); err != nil {
		dexPath = "/data/local/tmp/agent_tools.dex"
	}
	cmdArgs := append([]string{"/system/bin", "com.agent.ToolMain"}, args...)
	cmd := exec.Command("/system/bin/app_process", cmdArgs...)
	cmd.Env = getToolEnv(dexPath)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func _old_runTool_unused(args ...string) (string, error) {
	dexPath := "/data/adb/modules/agent_mobile_use/bin/agent_tools.dex"
	if _, err := os.Stat(dexPath); err != nil {
		dexPath = "/data/local/tmp/agent_tools.dex"
	}
	cmdArgs := append([]string{"/system/bin", "com.agent.ToolMain"}, args...)
	cmd := exec.Command("/system/bin/app_process", cmdArgs...)
	cmd.Env = append(os.Environ(),
		"ANDROID_ROOT=/system",
		"ANDROID_DATA=/data",
		"ANDROID_ART_ROOT=/apex/com.android.art",
		"ANDROID_I18N_ROOT=/apex/com.android.i18n",
		"ANDROID_TZDATA_ROOT=/apex/com.android.tzdata",
		"BOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar:/apex/com.android.adservices/javalib/framework-adservices.jar:/apex/com.android.adservices/javalib/framework-sdksandbox.jar:/apex/com.android.appsearch/javalib/framework-appsearch.jar:/apex/com.android.configinfrastructure/javalib/framework-configinfrastructure.jar:/apex/com.android.conscrypt/javalib/conscrypt.jar:/apex/com.android.crashrecovery/javalib/framework-crashrecovery.jar:/apex/com.android.devicelock/javalib/framework-devicelock.jar:/apex/com.android.healthfitness/javalib/framework-healthfitness.jar:/apex/com.android.ipsec/javalib/android.net.ipsec.ike.jar:/apex/com.android.media/javalib/updatable-media.jar:/apex/com.android.mediaprovider/javalib/framework-mediaprovider.jar:/apex/com.android.mediaprovider/javalib/framework-pdf.jar:/apex/com.android.mediaprovider/javalib/framework-pdf-v.jar:/apex/com.android.mediaprovider/javalib/framework-photopicker.jar:/apex/com.android.ondevicepersonalization/javalib/framework-ondevicepersonalization.jar:/apex/com.android.os.statsd/javalib/framework-statsd.jar:/apex/com.android.permission/javalib/framework-permission.jar:/apex/com.android.permission/javalib/framework-permission-s.jar:/apex/com.android.profiling/javalib/framework-profiling.jar:/apex/com.android.scheduling/javalib/framework-scheduling.jar:/apex/com.android.sdkext/javalib/framework-sdkextensions.jar:/apex/com.android.tethering/javalib/framework-connectivity.jar:/apex/com.android.tethering/javalib/framework-connectivity-b.jar:/apex/com.android.tethering/javalib/framework-connectivity-t.jar:/apex/com.android.tethering/javalib/framework-tethering.jar:/apex/com.android.uwb/javalib/framework-ranging.jar:/apex/com.android.uwb/javalib/framework-uwb.jar:/apex/com.android.virt/javalib/framework-virtualization.jar:/apex/com.android.wifi/javalib/framework-wifi.jar",
		"DEX2OATBOOTCLASSPATH=/apex/com.android.art/javalib/core-oj.jar:/apex/com.android.art/javalib/core-libart.jar:/apex/com.android.art/javalib/okhttp.jar:/apex/com.android.art/javalib/bouncycastle.jar:/apex/com.android.art/javalib/apache-xml.jar:/system/framework/framework.jar:/system/framework/framework-graphics.jar:/system/framework/framework-location.jar:/system/framework/ext.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/ims-common.jar:/system/framework/framework-ondeviceintelligence-platform.jar:/system/framework/framework-nfc.jar:/system/framework/tcmiface.jar:/system/framework/qcom.fmradio.jar:/system/framework/QPerformance.jar:/system/framework/UxPerformance.jar:/system/framework/WfdCommon.jar:/system/framework/oplus-framework.jar:/system/framework/subsystem-framework.jar:/apex/com.android.i18n/javalib/core-icu4j.jar",
		"CLASSPATH="+dexPath,
	)
	out, err := cmd.CombinedOutput()
	return string(out), err
}

func parseKeycode(key string) string {
	k := strings.ToUpper(strings.TrimSpace(key))
	switch k {
	case "BACK":
		return "4"
	case "HOME":
		return "3"
	case "ENTER":
		return "66"
	case "TAB":
		return "61"
	case "SPACE":
		return "62"
	case "DEL", "DELETE", "BACKSPACE":
		return "67"
	case "APP_SWITCH", "RECENTS":
		return "187"
	case "PASTE":
		return "279"
	default:
		return key
	}
}

type ActionResponse struct {
	Success bool   `json:"success"`
	Message string `json:"message,omitempty"`
	Data    any    `json:"data,omitempty"`
}

// ─────────────────────────────────────────────────────────────────────────────
// Flat UI observation: header line + column line + one row per element.
//
// ToolMain writes this shape; the daemon only decorates the header. Everything
// below exists so the two halves stay independent: the rows are the model's
// interface and may change freely, while the header is the machine's and must
// keep the keys check-completeness.py and the plugin read.
// ─────────────────────────────────────────────────────────────────────────────

// headerInts are the header keys the callers on the other side parse as numbers.
var headerInts = map[string]bool{
	"display": true, "width": true, "height": true, "windows": true,
	"total": true, "returned": true, "act_sent": true, "act_total": true,
	"truncated": true, "omitted": true, "omitted_top": true, "omitted_min": true,
	"dup": true, "retries": true, "recovered": true, "no_windows": true,
	"tree_blocked": true, "sys_dropped": true,
}

// headerOrder keeps the decorated header in the order ToolMain emits it, so a
// human diffing two dumps is not confused by Go's map iteration order.
var headerOrder = []string{
	"display", "width", "height", "windows", "sys_dropped", "mode", "target_display_id",
	"total", "retries", "recovered", "no_windows", "tree_blocked", "dup",
	"returned", "act_sent", "act_total", "truncated", "omitted", "omitted_top",
	"omitted_min", "x_extent", "y_extent",
}

// splitObservation separates ToolMain's output into its header line and the
// remaining lines (column line + element rows), unchanged.
//
// The tool's own header carries `size=WxH`, which is moved to the width/height
// keys the rest of the stack already reads. A body that does not start with a
// header is rejected rather than passed through: a caller that cannot tell a
// failed dump from an empty screen is worse off than one that gets nothing.
func splitObservation(body string) (map[string]any, string, bool) {
	// A failure is a SINGLE line with no column line and no rows, because there is
	// nothing to tabulate. Requiring a newline here would classify "the tool threw" as
	// "unparseable output", and the model would be told the read failed for the wrong
	// reason.
	if strings.HasPrefix(body, "fail error=") {
		// Store the message, not the wire quoting: observationText re-quotes it with
		// %q, and doing both would deliver `error="\"...\""` to the model.
		message := strings.TrimPrefix(body, "fail error=")
		message = strings.TrimSuffix(strings.TrimPrefix(message, "\""), "\"")
		return map[string]any{
			"ok":    false,
			"error": message,
		}, "", true
	}
	nl := strings.IndexByte(body, '\n')
	if nl < 0 {
		return nil, "", false
	}
	header, rows := body[:nl], body[nl+1:]
	fields := strings.Fields(header)
	if len(fields) == 0 || fields[0] != "ok" {
		return nil, "", false
	}
	env := map[string]any{"ok": true}
	for _, kv := range fields[1:] {
		eq := strings.IndexByte(kv, '=')
		if eq <= 0 {
			continue
		}
		key, value := kv[:eq], kv[eq+1:]
		if key == "size" {
			if x := strings.IndexByte(value, 'x'); x > 0 {
				if w, err := strconv.Atoi(value[:x]); err == nil {
					env["width"] = w
				}
				if h, err := strconv.Atoi(value[x+1:]); err == nil {
					env["height"] = h
				}
			}
			continue
		}
		if headerInts[key] {
			if n, err := strconv.Atoi(value); err == nil {
				env[key] = n
				continue
			}
		}
		env[key] = value
	}
	return env, rows, true
}

// observationText rebuilds the body the model reads, with the daemon's own
// additions folded back into the header line. The rows pass through untouched.
func observationText(env map[string]any, rows string) string {
	var b strings.Builder
	// The first token is the status and it must survive the round trip: a tool that
	// threw gives `fail error="..."`, and rebuilding that as `ok error="..."` would
	// tell the model the read succeeded while handing it an exception message.
	status := "ok"
	if ok, isBool := env["ok"].(bool); isBool && !ok {
		status = "fail"
	}
	b.WriteString(status)
	if status == "fail" {
		if errText, present := env["error"]; present {
			fmt.Fprintf(&b, " error=%q", fmt.Sprint(errText))
		}
		b.WriteString("\n")
		b.WriteString(rows)
		return b.String()
	}
	for _, key := range headerOrder {
		value, present := env[key]
		if !present {
			continue
		}
		fmt.Fprintf(&b, " %s=%v", key, value)
	}
	b.WriteString("\n")
	b.WriteString(rows)
	return b.String()
}

// observationStatus is the one-line summary the plugin shows beside the result. A
// failed read must not be summarised as "0 nodes": that reads exactly like an empty
// screen, which is the distinction the status line exists to preserve.
func observationStatus(env map[string]any) string {
	if ok, isBool := env["ok"].(bool); isBool && !ok {
		return fmt.Sprintf("dump failed: %v", env["error"])
	}
	return fmt.Sprintf("tree %v nodes, %v/%v actionable", env["returned"], env["act_sent"], env["act_total"])
}

var userFlagRe = regexp.MustCompile(`(?i)(?:--user\s+|user\s*[:=]\s*|\|\s*user\s*=\s*)(\d+)`)

func normalizeLaunchTarget(pkg, act string, user *int) (string, string, int) {
	pkg = strings.TrimSpace(pkg)
	act = strings.TrimSpace(act)
	userId := 0
	if user != nil {
		userId = *user
	}

	if m := userFlagRe.FindStringSubmatch(pkg); len(m) > 1 {
		if user == nil {
			if u, err := strconv.Atoi(m[1]); err == nil {
				userId = u
			}
		}
		pkg = strings.TrimSpace(strings.ReplaceAll(pkg, m[0], ""))
		pkg = strings.TrimRight(pkg, "| ")
	}

	if act == "" && strings.Contains(pkg, "/") {
		parts := strings.SplitN(pkg, "/", 2)
		pkg = strings.TrimSpace(parts[0])
		act = strings.TrimSpace(parts[1])
	}
	return pkg, act, userId
}

func main() {
	thawAppProcess()
	startCapsuleWatchdog()
	initAudioGuard()

	// Initial sync of a11y service state with target display
	st := getStatus()
	syncA11yWithTargetDisplay(getTargetDisplayID(st))

	// Clean up a11y service on graceful termination
	sigCh := make(chan os.Signal, 1)
	signal.Notify(sigCh, os.Interrupt, syscall.SIGTERM)
	go func() {
		<-sigCh
		ensureA11yServiceDetached()
		globalDumpDaemon.Stop()
		os.Exit(0)
	}()

	mux := http.NewServeMux()

	mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		w.Write(indexHTML)
	})

	mux.HandleFunc("/api/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(getStatus())
	})

	mux.HandleFunc("/api/start", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(startVirtualDisplay())
	})

	mux.HandleFunc("/api/stop", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		json.NewEncoder(w).Encode(stopVirtualDisplay())
	})

	mux.HandleFunc("/api/mode", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		targetMode := r.URL.Query().Get("mode")
		if r.Method == http.MethodPost && targetMode == "" {
			var p struct {
				Mode string `json:"mode"`
			}
			if err := json.NewDecoder(r.Body).Decode(&p); err == nil {
				targetMode = p.Mode
			}
		}
		if targetMode != "" {
			res := switchModeWithMigration(targetMode)
			json.NewEncoder(w).Encode(res)
			return
		}
		st := getStatus()
		targetDid := getTargetDisplayID(st)
		mode := getCurrentMode()
		msg := fmt.Sprintf("Current mode is %s (Target Display %d)", mode, targetDid)
		if mode == "idle" {
			msg = "Current mode is idle (No focused display, Display -1)"
		}
		json.NewEncoder(w).Encode(map[string]interface{}{
			"success":           true,
			"mode":              mode,
			"target_display_id": targetDid,
			"message":           msg,
		})
	})

	mux.HandleFunc("/api/stream/ws", func(w http.ResponseWriter, r *http.Request) {
		if !strings.EqualFold(r.Header.Get("Upgrade"), "websocket") {
			http.Error(w, "Expected websocket upgrade", http.StatusBadRequest)
			return
		}
		key := r.Header.Get("Sec-WebSocket-Key")
		if key == "" {
			http.Error(w, "Missing Sec-WebSocket-Key", http.StatusBadRequest)
			return
		}

		h := sha1.New()
		h.Write([]byte(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))
		accept := base64.StdEncoding.EncodeToString(h.Sum(nil))

		hj, ok := w.(http.Hijacker)
		if !ok {
			http.Error(w, "Webserver doesn't support hijacking", http.StatusInternalServerError)
			return
		}
		conn, bufrw, err := hj.Hijack()
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}

		resp := "HTTP/1.1 101 Switching Protocols\r\n" +
			"Upgrade: websocket\r\n" +
			"Connection: Upgrade\r\n" +
			"Sec-WebSocket-Accept: " + accept + "\r\n\r\n"
		if _, err := bufrw.WriteString(resp); err != nil {
			_ = conn.Close()
			return
		}
		if err := bufrw.Flush(); err != nil {
			_ = conn.Close()
			return
		}

		hub.register(conn)

		go func() {
			defer hub.unregister(conn)
			b := make([]byte, 512)
			for {
				if _, err := conn.Read(b); err != nil {
					break
				}
			}
		}()
	})

	mux.HandleFunc("/api/screenshot", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)

		_, targetDid, err := ensureTargetReady()
		if err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}

		var args []string
		if targetDid == 0 {
			args = []string{"/system/bin/screencap"}
		} else {
			sfID := getSfDisplayID()
			if sfID != "" {
				args = []string{"/system/bin/screencap", "-d", sfID}
			} else {
				args = []string{"/system/bin/screencap", "-d", strconv.Itoa(targetDid)}
			}
		}

		raw, err := exec.Command(args[0], args[1:]...).Output()
		if err != nil || len(raw) < 16 {
			http.Error(w, "Capture error", http.StatusInternalServerError)
			return
		}

		wPx := binary.LittleEndian.Uint32(raw[0:4])
		hPx := binary.LittleEndian.Uint32(raw[4:8])
		needed := 16 + int(wPx*hPx*4)
		if wPx == 0 || hPx == 0 || len(raw) < needed {
			http.Error(w, "Invalid frame buffer", http.StatusInternalServerError)
			return
		}

		img := &image.RGBA{
			Pix:    raw[16:needed],
			Stride: int(wPx) * 4,
			Rect:   image.Rect(0, 0, int(wPx), int(hPx)),
		}

		w.Header().Set("Content-Type", "image/jpeg")
		w.Header().Set("Cache-Control", "no-store, must-revalidate")
		if err := jpeg.Encode(w, img, &jpeg.Options{Quality: 85}); err != nil {
			http.Error(w, "JPEG encode error", http.StatusInternalServerError)
			return
		}
	})

	mux.HandleFunc("/api/dump_ui", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)
		st, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		did := strconv.Itoa(targetDid)
		// Opt-in: ?no_system_ui=1 drops system-chrome windows (status bar, nav bar,
		// smart sidebar) so physical and virtual displays yield the same tree.
		treeArgs := []string{"tree", did}
		if v := r.URL.Query().Get("no_system_ui"); v == "1" || v == "true" {
			treeArgs = append(treeArgs, "0", "--no-system-ui")
		}
		out, err := globalDumpDaemon.Request(strings.Join(treeArgs, " "))
		if err != nil || strings.TrimSpace(out) == "" {
			out, err = runTool(treeArgs...)
		}
		trimmed := strings.TrimSpace(out)
		if err != nil || trimmed == "" {
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "UI dump failed: the accessibility tree could not be read for this display",
				Data:    trimmed,
			})
			return
		}

		// ToolMain emits a flat observation: a machine-readable header line, a column
		// line, then one line per element. Decode header and decorate with daemon info.
		env, rows, ok := splitObservation(trimmed)
		if !ok {
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "UI dump did not start with a readable status header",
				Data:    trimmed,
			})
			return
		}

		env["mode"] = getCurrentMode()
		env["target_display_id"] = targetDid
		// The daemon is the only party that knows the real display geometry: the tool
		// asks DisplayManager for it and can come back with 0x0 on a fresh virtual
		// display. Without this the model is handed coordinates in an unknown space.
		if wv, okW := env["width"].(int); !okW || wv <= 0 {
			dw, dh := displaySize(targetDid, st)
			env["width"] = dw
			env["height"] = dh
		}
		if r.URL.Query().Get("raw") == "1" {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.Write([]byte(observationText(env, rows)))
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{
			Success: true,
			Message: observationStatus(env),
			Data:    observationText(env, rows),
		})
	})

	mux.HandleFunc("/api/apps", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)

		query := r.URL.Query().Get("query")
		if query == "" {
			query = r.URL.Query().Get("q")
		}
		if r.Method == http.MethodPost && r.Body != nil {
			var p struct {
				Query string `json:"query"`
			}
			if err := json.NewDecoder(r.Body).Decode(&p); err == nil && p.Query != "" {
				query = p.Query
			}
		}

		args := []string{"apps"}
		if query != "" {
			args = append(args, query)
		}

		out, err := runTool(args...)
		trimmed := strings.TrimSpace(out)
		if err != nil && trimmed == "" {
			w.WriteHeader(http.StatusInternalServerError)
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: fmt.Sprintf("Failed to list apps: %v", err),
			})
			return
		}

		if r.URL.Query().Get("raw") == "1" {
			w.Header().Set("Content-Type", "text/plain; charset=utf-8")
			w.Write([]byte(trimmed))
			return
		}

		json.NewEncoder(w).Encode(ActionResponse{
			Success: true,
			Message: "OK",
			Data:    trimmed,
		})
	})

	mux.HandleFunc("/api/click", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			X          int `json:"x"`
			Y          int `json:"y"`
			DurationMs int `json:"duration_ms"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		if targetDid == 0 {
			if p.DurationMs > 0 {
				broadcastTouch(2, 0, 0, p.X, p.Y, p.X, p.Y, p.DurationMs)
			} else {
				broadcastTouch(1, p.X, p.Y, 0, 0, 0, 0, 0)
			}
		}
		var cmd *exec.Cmd
		if p.DurationMs > 0 {
			cmd = exec.Command("/system/bin/input", "-d", did, "swipe",
				strconv.Itoa(p.X), strconv.Itoa(p.Y), strconv.Itoa(p.X), strconv.Itoa(p.Y), strconv.Itoa(p.DurationMs))
		} else {
			cmd = exec.Command("/system/bin/input", "-d", did, "tap", strconv.Itoa(p.X), strconv.Itoa(p.Y))
		}
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true})
	})

	mux.HandleFunc("/api/swipe", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			X1       int `json:"x1"`
			Y1       int `json:"y1"`
			X2       int `json:"x2"`
			Y2       int `json:"y2"`
			Duration int `json:"duration"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		if p.Duration <= 0 {
			p.Duration = 300
		}
		did := strconv.Itoa(targetDid)
		if targetDid == 0 {
			broadcastTouch(2, 0, 0, p.X1, p.Y1, p.X2, p.Y2, p.Duration)
		}
		cmd := exec.Command("/system/bin/input", "-d", did, "swipe",
			strconv.Itoa(p.X1), strconv.Itoa(p.Y1), strconv.Itoa(p.X2), strconv.Itoa(p.Y2), strconv.Itoa(p.Duration))
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true})
	})

	mux.HandleFunc("/api/type", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Text   string      `json:"text"`
			Target interface{} `json:"target"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		targetStr := "focused"
		if p.Target != nil {
			switch v := p.Target.(type) {
			case string:
				if v != "" {
					targetStr = v
				}
			case float64:
				targetStr = strconv.Itoa(int(v))
			case int:
				targetStr = strconv.Itoa(v)
			}
		}

		typeCmd := fmt.Sprintf("type %s %s %s", did, targetStr, p.Text)
		out, err := globalDumpDaemon.Request(typeCmd)
		if err != nil || strings.TrimSpace(out) == "" {
			out, err = runTool("type", did, targetStr, p.Text)
		}
		if err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: out})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: out, Data: out})
	})

	mux.HandleFunc("/api/key", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Key string `json:"key"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		did := strconv.Itoa(targetDid)
		kc := parseKeycode(p.Key)
		cmd := exec.Command("/system/bin/input", "-d", did, "keyevent", kc)
		if err := cmd.Run(); err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true})
	})

	mux.HandleFunc("/api/launch", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		injectNoticeHeader(w)
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Package  string `json:"package"`
			Activity string `json:"activity"`
			User     *int   `json:"user"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}

		pkg, act, userId := normalizeLaunchTarget(p.Package, p.Activity, p.User)
		if pkg == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Missing package name"})
			return
		}
		p.Package = pkg
		p.Activity = act

		// 1. Check if the requested app is already running in an activity stack for target userId
		if existingStack, found := findStackForPackageAndActivity(p.Package, p.Activity, userId); found {
			if existingStack.DisplayID != targetDid {
				// App is running on another display (e.g. Display 0) -> Smoothly reparent to targetDid!
				cmd := exec.Command("/system/bin/cmd", "activity", "display", "move-stack", strconv.Itoa(existingStack.StackID), strconv.Itoa(targetDid))
				out, err := cmd.CombinedOutput()
				if err == nil {
					if targetDid > 0 && isAudioMuteEnabled() {
						setPackageMuted(p.Package, userId, true)
					} else if targetDid == 0 {
						setPackageMuted(p.Package, userId, false)
					}
					userDesc := ""
					if userId != 0 {
						userDesc = fmt.Sprintf(" (user %d)", userId)
					}
					json.NewEncoder(w).Encode(ActionResponse{
						Success: true,
						Message: fmt.Sprintf("Smoothly moved existing stack %d of %s%s to display %d", existingStack.StackID, p.Package, userDesc, targetDid),
						Data:    string(out),
					})
					return
				}
				// If move-stack somehow failed, fall through to am start
			} else {
				// App is already on target display
				if existingStack.Visible && p.Activity == "" {
					userDesc := ""
					if userId != 0 {
						userDesc = fmt.Sprintf(" (user %d)", userId)
					}
					json.NewEncoder(w).Encode(ActionResponse{
						Success: true,
						Message: fmt.Sprintf("App %s%s is already active on display %d", p.Package, userDesc, targetDid),
					})
					return
				}
			}
		}

		// 2. Cold start fallback: launch via am start --display <did> --user <userId>
		did := strconv.Itoa(targetDid)
		uStr := strconv.Itoa(userId)
		args := []string{"start", "--display", did, "--user", uStr}
		if p.Activity != "" {
			args = append(args, "-n", p.Package+"/"+p.Activity)
		} else {
			actBytes, _ := exec.Command("/system/bin/cmd", "package", "resolve-activity", "--brief", "--user", uStr, p.Package).Output()
			actLines := strings.Split(strings.TrimSpace(string(actBytes)), "\n")
			targetAct := ""
			if len(actLines) > 0 && !strings.Contains(actLines[len(actLines)-1], "No activity found") {
				targetAct = actLines[len(actLines)-1]
			}
			if targetAct != "" {
				args = append(args, "-n", targetAct)
			} else {
				args = append(args, p.Package)
			}
		}
		cmd := exec.Command("/system/bin/am", args...)
		out, err := cmd.CombinedOutput()
		if err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: string(out)})
			return
		}
		if targetDid > 0 && isAudioMuteEnabled() {
			setPackageMuted(p.Package, userId, true)
		} else if targetDid == 0 {
			setPackageMuted(p.Package, userId, false)
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(out)})
	})

	mux.HandleFunc("/api/notify", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		var p struct {
			Title       string `json:"title"`
			Subtext     string `json:"subtext"`
			Content     string `json:"content"`
			Tag         string `json:"tag"`
			URL         string `json:"url"`
			SessionID   string `json:"session_id"`
			Session     string `json:"session"`
			Total       int    `json:"total"`
			Completed   int    `json:"completed"`
			IsCompleted bool   `json:"is_completed"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		if p.Tag == "" {
			p.Tag = "dsh_agent"
		}
		if p.Title == "" {
			p.Title = "任务已经完成！"
		}
		if p.URL == "" {
			p.URL = "http://127.0.0.1:3080"
		}
		sid := p.SessionID
		if sid == "" {
			sid = p.Session
		}

		// Ensure subtext is always the genuine session title, never raw user prompt strings
		if p.Subtext == "" {
			_, title := getSessionMeta()
			if title != "" {
				p.Subtext = title
			} else {
				p.Subtext = "移动端任务"
			}
		} else if len(p.Subtext) > 15 || strings.Contains(p.Subtext, "？") || strings.Contains(p.Subtext, "?") || strings.Contains(p.Subtext, "吗") {
			p.Subtext = "移动端任务"
		}

		isCompletedStr := "false"
		if p.IsCompleted || (p.Total > 0 && p.Completed >= p.Total) {
			isCompletedStr = "true"
			setSessionActive(false, sid, p.Subtext)
			if sid != "" {
				lastCompletedSessionIDMu.Lock()
				lastCompletedSessionID = sid
				lastCompletedSessionIDMu.Unlock()
			}
			if p.Title == "" || strings.Contains(p.Title, "完成") {
				p.Title = "已完成"
			}

			// Context Suppression Check:
			// If DemoDialogActivity is in foreground on Display 0 AND viewing this exact session:
			// Silently suppress notification so as not to obstruct the user's view!
			viewStateMu.Lock()
			fg := isOverlayForeground
			viewing := currentViewingSessionID
			viewStateMu.Unlock()

			if fg && viewing != "" && sid != "" && (viewing == sid || strings.Contains(viewing, sid) || strings.Contains(sid, viewing)) {
				// Double-check with dumpsys that DemoDialogActivity is truly resumed
				out, err := exec.Command("/system/bin/sh", "-c", `dumpsys activity activities | grep "topResumedActivity" | head -1`).Output()
				if err == nil && strings.Contains(string(out), "DemoDialogActivity") {
					fmt.Printf("[notify] User is actively viewing completed session %s in foreground. Suppressing completion notification.\n", sid)
					json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: "Suppressed: user is currently viewing this session in foreground"})
					return
				}
			}
		}

		// Ensure app process is unfrozen from ColorOS Hans/Freezer and AMS BroadcastQueue
		thawAppProcess()

		// Direct Activity wake-up trampoline: am start -f 0x18000000
		// Immediately unfreezes process in 0ms, posts notification, and finishes cleanly without delay
		cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am start -f 0x18000000 -n com.agent.mobileuse/.QuestionActivity --ez only_notify true --ez is_completed "$NOTIFY_IS_COMPLETED" --es title "$NOTIFY_TITLE" --es session_title "$NOTIFY_SESSION_TITLE" --es subtext "$NOTIFY_SUBTEXT" --es tag "$NOTIFY_TAG" --es content "$NOTIFY_CONTENT" --es session_id "$NOTIFY_SESSION_ID" 2>/dev/null`)
		cmd.Env = append(os.Environ(),
			"NOTIFY_TITLE="+p.Title,
			"NOTIFY_SESSION_TITLE="+p.Subtext,
			"NOTIFY_SUBTEXT="+p.Subtext,
			"NOTIFY_TAG="+p.Tag,
			"NOTIFY_CONTENT="+p.Content,
			"NOTIFY_SESSION_ID="+sid,
			"NOTIFY_IS_COMPLETED="+isCompletedStr,
		)
		out, err := cmd.CombinedOutput()
		if isCompletedStr == "true" && getSessionActive() {
			go updateCapsuleState()
		}
		if err == nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(out)})
			return
		}

		// Fallback: Use cmd notification post if APK broadcast fails
		flags := "-S bigtext"
		if _, statErr := os.Stat("/data/local/tmp/dsh_whale_icon.png"); statErr == nil {
			flags += " -i file:///data/local/tmp/dsh_whale_icon.png"
		}
		if _, statErr := os.Stat("/data/local/tmp/dsh_whale_avatar.png"); statErr == nil {
			flags += " -I file:///data/local/tmp/dsh_whale_avatar.png"
		}

		fallbackCmd := exec.Command("/system/bin/su", "2000", "-c", `cmd notification post `+flags+` -t "$NOTIFY_TITLE" "$NOTIFY_TAG" "$NOTIFY_CONTENT"`)
		fallbackCmd.Env = append(os.Environ(),
			"NOTIFY_TITLE="+p.Title,
			"NOTIFY_TAG="+p.Tag,
			"NOTIFY_CONTENT="+p.Content,
		)
		fallbackOut, fallbackErr := fallbackCmd.CombinedOutput()
		if fallbackErr != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: fallbackErr.Error(), Data: string(fallbackOut)})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(fallbackOut)})
	})

	mux.HandleFunc("/api/question", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == "OPTIONS" {
			return
		}
		var p struct {
			RequestID string `json:"request_id"`
			Questions []any  `json:"questions"`
			TimeoutMs int    `json:"timeout_ms"`
		}
		bodyBytes, err := io.ReadAll(r.Body)
		if err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Bad request"})
			return
		}
		if err := json.Unmarshal(bodyBytes, &p); err != nil || p.RequestID == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}

		ch := make(chan *QuestionAnswerPayload, 1)
		questionMu.Lock()
		activeQuestions[p.RequestID] = ch
		questionMu.Unlock()

		defer func() {
			questionMu.Lock()
			delete(activeQuestions, p.RequestID)
			questionMu.Unlock()
		}()

		// Wake up process and trigger notification banner via Activity launch
		thawAppProcess()
		st := getStatus()
		onlyNotifyStr := "true"
		if st.Mode == "foreground" {
			onlyNotifyStr = "false"
		}

		cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am start -f 0x18000000 -n com.agent.mobileuse/.QuestionActivity --ez only_notify "$ONLY_NOTIFY" --es request_id "$REQ_ID" --es data "$REQ_DATA" 2>/dev/null`)
		cmd.Env = append(os.Environ(),
			"ONLY_NOTIFY="+onlyNotifyStr,
			"REQ_ID="+p.RequestID,
			"REQ_DATA="+string(bodyBytes),
		)
		cmd.Run()

		timeout := 10 * time.Minute
		if p.TimeoutMs > 0 {
			timeout = time.Duration(p.TimeoutMs) * time.Millisecond
		}

		select {
		case ans := <-ch:
			json.NewEncoder(w).Encode(map[string]any{
				"success":    true,
				"request_id": ans.RequestID,
				"answers":    ans.Answers,
			})
		case <-r.Context().Done():
			cancelQuestionOnDevice(p.RequestID)
		case <-time.After(timeout):
			cancelQuestionOnDevice(p.RequestID)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Timeout waiting for answer"})
		}
	})

	mux.HandleFunc("/api/answer", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == "OPTIONS" {
			return
		}
		var p QuestionAnswerPayload
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil || p.RequestID == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}

		questionMu.Lock()
		ch, ok := activeQuestions[p.RequestID]
		questionMu.Unlock()

		if !ok {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "No active question found for request_id or already expired"})
			return
		}

		cancelQuestionOnDevice(p.RequestID)

		select {
		case ch <- &p:
			json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: "Answer delivered"})
		default:
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Answer already queued"})
		}
	})

	mux.HandleFunc("/api/question/cancel", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == "OPTIONS" {
			return
		}
		var p struct {
			RequestID string `json:"request_id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil || p.RequestID == "" {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		cancelQuestionOnDevice(p.RequestID)
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: "Question cancelled"})
	})

	mux.HandleFunc("/api/view_state", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == "OPTIONS" {
			return
		}
		var p struct {
			Foreground bool   `json:"foreground"`
			SessionID  string `json:"session_id"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err == nil {
			viewStateMu.Lock()
			isOverlayForeground = p.Foreground
			if p.SessionID != "" {
				currentViewingSessionID = p.SessionID
			}
			viewStateMu.Unlock()
			fmt.Printf("[view_state] Overlay foreground: %v, viewing session: %s\n", p.Foreground, p.SessionID)
		}
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})

	mux.HandleFunc("/api/task_event", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == "OPTIONS" {
			return
		}
		var p struct {
			Type         string `json:"type"`
			Status       string `json:"status"`
			SessionID    string `json:"session_id"`
			SessionTitle string `json:"session_title"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err == nil {
			if p.Type == "agent_status" {
				if p.Status == "running" {
					setSessionActive(true, p.SessionID, p.SessionTitle)
				} else if p.Status == "idle" || p.Status == "ready" || p.Status == "stopped" || p.Status == "error" || p.Status == "disposed" {
					setSessionActive(false, p.SessionID, p.SessionTitle)
				}
			}
		}
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})

	mux.HandleFunc("/api/session/watch", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
		sid := r.URL.Query().Get("session_id")
		title := r.URL.Query().Get("session_title")
		if sid == "" {
			w.WriteHeader(http.StatusBadRequest)
			return
		}
		setSessionActive(true, sid, title)
		w.Header().Set("Content-Type", "text/plain")
		w.WriteHeader(http.StatusOK)
		if f, ok := w.(http.Flusher); ok {
			f.Flush()
		}
		// Block until client disconnects or process dies (Linux kernel sends TCP FIN on process termination)
		<-r.Context().Done()
		setSessionActive(false, sid, "")
	})

	mux.HandleFunc("/api/audio/status", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		audioMu.Lock()
		mutedList := make([]string, 0, len(mutedPackages))
		for p := range mutedPackages {
			mutedList = append(mutedList, p)
		}
		enabled := vdAudioMuteEnabled
		audioMu.Unlock()
		json.NewEncoder(w).Encode(map[string]any{
			"enabled": enabled,
			"muted":   mutedList,
		})
	})

	mux.HandleFunc("/api/audio/toggle", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		var p struct {
			Enabled *bool `json:"enabled"`
		}
		_ = json.NewDecoder(r.Body).Decode(&p)
		target := !isAudioMuteEnabled()
		if p.Enabled != nil {
			target = *p.Enabled
		}
		setAudioMuteEnabled(target)
		json.NewEncoder(w).Encode(map[string]any{
			"ok":      true,
			"enabled": target,
		})
	})

	mux.HandleFunc("/api/audio/unmute-all", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		unmuteAllAudio()
		json.NewEncoder(w).Encode(map[string]any{
			"ok": true,
		})
	})

	port := "3070"
	if p := os.Getenv("PORT"); p != "" {
		port = p
	}
	fmt.Printf("[AgentVD-Web-Go] Listening on 0.0.0.0:%s\n", port)
	if err := http.ListenAndServe("0.0.0.0:"+port, mux); err != nil {
		fmt.Fprintf(os.Stderr, "Server error: %v\n", err)
	}
}
