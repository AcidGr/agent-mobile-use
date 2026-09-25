package main

import (
	_ "embed"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
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
	// The newest frame of the virtual display, published by the daemon as JPEG. It
	// already holds the display's output surface, so this costs it one encode per
	// changed frame instead of the ~1.8s of CPU screencap spends in the PNG encoder.
	frameCacheFile = "/data/local/tmp/vd_latest.jpg"
)

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

func getCurrentMode() string {
	modeMu.Lock()
	defer modeMu.Unlock()
	return currentMode
}

func setCurrentMode(m string) string {
	modeMu.Lock()
	defer modeMu.Unlock()
	lower := strings.ToLower(strings.TrimSpace(m))
	if lower == "foreground" || lower == "fg" || lower == "0" {
		currentMode = "foreground"
		go setEdgeGlow(true)
	} else if lower == "idle" || lower == "standby" || lower == "none" || lower == "-1" {
		currentMode = "idle"
		go setEdgeGlow(false)
	} else {
		currentMode = "background"
		go setEdgeGlow(false)
	}
	return currentMode
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
	return "10044"
}

func thawAppProcess() {
	uid := getAppUID()
	_ = exec.Command("/system/bin/cmd", "activity", "unfreeze", "--sticky", "com.agent.mobileuse").Run()
	_ = exec.Command("/system/bin/sh", "-c", fmt.Sprintf("echo 0 > /sys/fs/cgroup/apps/uid_%s/cgroup.freeze 2>/dev/null; echo 0 > /sys/fs/cgroup/uid_%s/cgroup.freeze 2>/dev/null", uid, uid)).Run()
}

func setEdgeGlow(enable bool) {
	if enable {
		exec.Command("/system/bin/sh", "-c", "am force-stop com.agent.mobileuse").Run()
		thawAppProcess()
		cmd := exec.Command("/system/bin/sh", "-c", "am start-foreground-service -a START com.agent.mobileuse/.GlowService")
		out, err := cmd.CombinedOutput()
		if err != nil {
			fmt.Printf("[setEdgeGlow] START error: %v, output: %s\n", err, string(out))
		}
	} else {
		cmd := exec.Command("/system/bin/sh", "-c", "am start-foreground-service -a STOP com.agent.mobileuse/.GlowService")
		_ = cmd.Run()
	}
}

var (
	glowLastRestartMu sync.Mutex
	glowLastRestart   time.Time
)

func isGlowServiceAlive() bool {
	out, err := exec.Command("/system/bin/pidof", "com.agent.mobileuse").Output()
	if err != nil || len(strings.TrimSpace(string(out))) == 0 {
		return false
	}
	cmd := exec.Command("/system/bin/sh", "-c", `dumpsys activity services com.agent.mobileuse/.GlowService | grep -q "app=ProcessRecord"`)
	return cmd.Run() == nil
}

func syncGlowStateWithDisplay(targetDid int) {
	if targetDid == 0 {
		// Target is Display 0: light must be ON!
		if !isGlowServiceAlive() {
			glowLastRestartMu.Lock()
			now := time.Now()
			if now.Sub(glowLastRestart) < 2*time.Second {
				glowLastRestartMu.Unlock()
				return
			}
			glowLastRestart = now
			glowLastRestartMu.Unlock()

			go setEdgeGlow(true)
		}
	} else {
		// Target is NOT Display 0: light must be OFF!
		if isGlowServiceAlive() {
			go setEdgeGlow(false)
		}
	}
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

	for _, line := range lines {
		line = strings.TrimSpace(line)
		if m := reRoot.FindStringSubmatch(line); len(m) > 2 {
			if cur != nil {
				list = append(list, *cur)
			}
			sId, _ := strconv.Atoi(m[1])
			dId, _ := strconv.Atoi(m[2])
			cur = &StackInfo{
				StackID:   sId,
				DisplayID: dId,
			}
		} else if cur != nil {
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

func findStackForPackageAndActivity(pkg, act string) (StackInfo, bool) {
	stacks := getStackList()
	if act != "" {
		for _, s := range stacks {
			if strings.Contains(s.TopAct, pkg) && strings.Contains(s.TopAct, act) {
				return s, true
			}
		}
	}
	for _, s := range stacks {
		for _, p := range s.Packages {
			if p == pkg {
				return s, true
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

func handoffToBackground() map[string]interface{} {
	st := getStatus()
	vdDid := st.DisplayID
	if vdDid <= 0 {
		st = startVirtualDisplay()
		vdDid = st.DisplayID
	}

	migratedComponent := ""
	migratedTaskId := 0

	// 1. Try finding top app stack via getTopAppStackOnDisplay
	if topStack, ok := getTopAppStackOnDisplay(0); ok {
		migratedComponent = topStack.TopAct
		migratedTaskId = topStack.StackID
	} else {
		// Fallback: check topResumedActivity in dumpsys activity activities
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

	// 2. Perform clean stack migration if we found a valid user app on Display 0
	if migratedTaskId > 0 && vdDid > 0 {
		cmd := exec.Command("/system/bin/cmd", "activity", "display", "move-stack", strconv.Itoa(migratedTaskId), strconv.Itoa(vdDid))
		out, err := cmd.CombinedOutput()
		if err != nil {
			fmt.Printf("[handoff] move-stack %d to %d error: %v, out: %s\n", migratedTaskId, vdDid, err, string(out))
		} else {
			fmt.Printf("[handoff] Successfully moved stack %d (%s) to display %d\n", migratedTaskId, migratedComponent, vdDid)
		}
	}

	// 3. 模式设置为 background，并熄灭光效与胶囊
	setCurrentMode("background")

	// 4. 设置一次性消费通知给 LLM，告知后台接力成功且无需中断
	setPendingHandoffNotice("[System Notice: The task was smoothly handed off to the virtual background display by user. The active app has migrated and resumed. No special action required; continue your next step as planned.]")

	return map[string]interface{}{
		"success":            true,
		"mode":               "background",
		"target_display_id":  vdDid,
		"migrated_component": migratedComponent,
		"migrated_task_id":   migratedTaskId,
		"message":            "Successfully handed off to background",
	}
}

func ensureTargetReady() (StatusResp, int, error) {
	st := getStatus()
	targetDid := getTargetDisplayID(st)
	syncGlowStateWithDisplay(targetDid)

	if targetDid == 0 {
		return st, 0, nil
	}
	if targetDid < 0 {
		return st, -1, fmt.Errorf("Agent is currently in idle mode (no focused display, display -1). Please switch mode to 'foreground' or 'background' first")
	}
	if st.Status != "running" {
		st = startVirtualDisplay()
		if st.Status != "running" {
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

func getSfDisplayID() string {	out, err := exec.Command("/system/bin/dumpsys", "SurfaceFlinger", "--display-id").Output()
	if err != nil {
		return ""
	}
	re := regexp.MustCompile(`Display\s+([0-9]+).*Agent.*VirtualDisplay`)
	matches := re.FindStringSubmatch(string(out))
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
	return getStatus()
}

// ── Accessibility service, on only for the duration of one tree read ──
//
// WeChat only exposes its node tree while a genuine accessibility service is bound.
// Measured on this device (2026-09-20):
//
//	enabled=0, service list empty       -> tree_blocked, 0 nodes
//	enabled=1, service list empty       -> tree_blocked, 0 nodes   <- the boolean alone does nothing
//	enabled=0, service list has SelectToSpeak -> 68 nodes, readable
//	enabled=1, service list has it      -> 68 nodes, readable
//
// So the ONLY thing that matters is that a real service is bound. Every tree read is
// therefore wrapped: put the service in the list, run the tool, put the list back.
//
// Timings measured here, which is what this is built on:
//
//	bind    write returns +19..47ms, service bound +51..76ms warm; ~700ms cold
//	        (cold = the TalkBack process has to be started, e.g. after a reboot)
//	unbind  delete returns +21ms, state cleared by +53ms
//	wait    NOT needed: with delays of 0 / 300 / 1000ms before the dump, WeChat read
//	        68 nodes in 9/9 trials, including one where TalkBack was force-stopped
//	        immediately before the write
//	total   enable + dump + restore = 2383ms against a 2218ms plain dump, so +165ms
//
// What the earlier version of this got wrong, and is not repeated here:
//   - it also wrote `accessibility_enabled`; the boolean has no effect on binding, and
//     neither does leaving it as "0" on the way out
//   - it slept 1000ms before every call; the nine trials above say that wait buys nothing
//
// It only appends to whatever the user already had, and only undoes what it itself did:
// if the service is already in the list it is left alone.
const a11yService = "com.google.android.marvin.talkback/com.google.android.accessibility.selecttospeak.SelectToSpeakService"

const a11yKey = "enabled_accessibility_services"

// Serialises the toggle so two concurrent tool calls cannot interleave their
// save/restore and leave the list in a state neither of them intended.
var a11yToggleMu sync.Mutex

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

// toolReadsTree reports whether a tool command reads the accessibility tree.
func toolReadsTree(args []string) bool {
	if len(args) == 0 {
		return false
	}
	switch args[0] {
	case "tree", "dump", "type":
		return true
	}
	return false
}

// withA11yService binds the accessibility service around fn and then restores the
// device's setting exactly as it was.
func withA11yService(fn func() (string, error)) (string, error) {
	a11yToggleMu.Lock()
	defer a11yToggleMu.Unlock()

	orig := readSecure(a11yKey)
	if strings.Contains(orig, a11yService) {
		// Already there — either the user enabled it, or it is left over from an
		// interrupted call. Either way it is not ours to remove.
		return fn()
	}

	merged := a11yService
	if orig != "" {
		merged = orig + ":" + a11yService
	}
	writeSecure(a11yKey, merged)

	out, err := fn()

	if orig == "" {
		deleteSecure(a11yKey)
	} else {
		writeSecure(a11yKey, orig)
	}
	return out, err
}

func runTool(args ...string) (string, error) {
	if toolReadsTree(args) {
		return withA11yService(func() (string, error) { return runToolRaw(args...) })
	}
	return runToolRaw(args...)
}

func runToolRaw(args ...string) (string, error) {
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
	Notice  string `json:"notice,omitempty"`
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

func resolveSessionTitle(sessionID string) string {
	if sessionID == "" {
		return ""
	}
	// Try loading from session_projcache
	sessionPath := fmt.Sprintf("/data/local/ubuntu/root/.dsh/storages/session_projcache/sessions/%s.json", sessionID)
	data, err := os.ReadFile(sessionPath)
	if err == nil {
		var root struct {
			Record struct {
				Rows struct {
					Title struct {
						Val string `json:"val"`
					} `json:"title"`
				} `json:"rows"`
			} `json:"record"`
		}
		if json.Unmarshal(data, &root) == nil {
			title := strings.TrimSpace(root.Record.Rows.Title.Val)
			if title != "" {
				return title
			}
		}
	}

	// Fallback: check workspace.json
	wsData, err := os.ReadFile("/data/local/ubuntu/root/.dsh/storages/workspace.json")
	if err == nil {
		var wsRoot map[string]any
		if json.Unmarshal(wsData, &wsRoot) == nil {
			if wsList, ok := wsRoot["workspaces"].(map[string]any); ok {
				for _, v := range wsList {
					if wsMap, ok := v.(map[string]any); ok {
						if sIds, ok := wsMap["sessionIds"].([]any); ok {
							for _, s := range sIds {
								if sStr, ok := s.(string); ok && sStr == sessionID {
									if t, ok := wsMap["title"].(string); ok && strings.TrimSpace(t) != "" {
										return strings.TrimSpace(t)
									}
								}
							}
						}
					}
				}
			}
		}
	}
	return ""
}

func main() {
	thawAppProcess()
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
		if r.Method == http.MethodPost {
			var p struct {
				Mode string `json:"mode"`
			}
			if err := json.NewDecoder(r.Body).Decode(&p); err == nil && p.Mode != "" {
				setCurrentMode(p.Mode)
			}
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

	mux.HandleFunc("/api/handoff", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		res := handoffToBackground()
		json.NewEncoder(w).Encode(res)
	})

	mux.HandleFunc("/api/screenshot", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")

		// Preferred path: serve the daemon's cached frame. It is the picture the
		// display last produced, already encoded, so this transfers ~271 KB instead of
		// screencap's 2.9 MB and spends no CPU on encoding at all.
		//
		// Only the virtual display is served this way: in foreground mode the request
		// means "the physical screen", which the daemon's cache is not.
		if st := getStatus(); st.Status == "running" && getTargetDisplayID(st) > 0 {
			if data, err := os.ReadFile(frameCacheFile); err == nil && len(data) > 0 {
				w.Header().Set("Content-Type", "image/jpeg")
				w.Header().Set("Cache-Control", "no-store, must-revalidate")
				w.Header().Set("Content-Length", strconv.Itoa(len(data)))
				w.Write(data)
				return
			}
		}

		// Fallback: no daemon, no frame cached yet, or the physical display is the
		// target. Unchanged from before, including the on-demand display start.
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			http.Error(w, err.Error(), http.StatusNotFound)
			return
		}
		var out []byte
		if targetDid == 0 {
			out, err = exec.Command("/system/bin/screencap", "-p").Output()
		} else {
			sfID := getSfDisplayID()
			if sfID != "" {
				out, err = exec.Command("/system/bin/screencap", "-d", sfID, "-p").Output()
			} else {
				out, err = exec.Command("/system/bin/screencap", "-d", strconv.Itoa(targetDid), "-p").Output()
			}
		}

		if err != nil || len(out) == 0 {
			http.Error(w, "Capture error", http.StatusInternalServerError)
			return
		}

		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("Cache-Control", "no-store, must-revalidate")
		w.Header().Set("Content-Length", strconv.Itoa(len(out)))
		w.Write(out)
	})

	mux.HandleFunc("/api/dump_ui", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		st, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		did := strconv.Itoa(targetDid)
		// No paging parameters. The dump budget is sized to deliver a dense screen whole
		// in one call (see ToolMain's MAX_NODES_CHARS), so there is nothing to page to.
		//
		// This used to accept ?y_min=&y_max= as a window, paired with a `next_y` hint the
		// tool emitted so a caller could fetch whatever the budget could not fit. That
		// hint was wrong whenever the budget cut into the node RANKING rather than into
		// the screen: nodes are ordered by usefulness, so the omitted ones are scattered
		// instead of sitting below the last emitted one, and next_y pointed at the bottom
		// of the screen. Measured on Amap with a 3000-char budget: 39/88 controls on page
		// one, next_y=2800, second page empty. A silent dead end is worse than no paging.
		// Opt-in: ?no_system_ui=1 drops system-chrome windows (status bar, nav bar,
		// notification shade, the ColorOS smart sidebar) so the physical display and the
		// virtual display hand the model the SAME tree for the same app screen. Off by
		// default; the un-filtered header is unchanged, and when the filter runs it
		// reports sys_dropped=N so a reader can tell "no chrome here" from "chrome removed".
		treeArgs := []string{"tree", did}
		if v := r.URL.Query().Get("no_system_ui"); v == "1" || v == "true" {
			treeArgs = append(treeArgs, "0", "--no-system-ui")
		}
		out, err := runTool(treeArgs...)
		trimmed := strings.TrimSpace(out)
		notice := popPendingHandoffNotice()
		if err != nil || trimmed == "" {
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "UI dump failed: the accessibility tree could not be read for this display",
				Data:    trimmed,
				Notice:  notice,
			})
			return
		}

		// ToolMain emits a flat observation: a machine-readable header line, a column
		// line, then ONE LINE PER ELEMENT. Decode the header, decorate it with the
		// geometry and mode only the daemon knows, and reply with that whole body as a
		// JSON string plus the fields a machine needs to read without parsing rows.
		//
		// This used to `json.Unmarshal` the dump into a map and re-encode it, because
		// ToolMain used to emit a JSON envelope. It no longer does: repeating `"id"`,
		// `"type"` and `"b"` on every node was 62% of the payload, all of it pure key
		// name tax. The rows are the model's interface; the envelope here is the
		// caller's, and the two must not be entangled again.
		env, rows, ok := splitObservation(trimmed)
		if !ok {
			json.NewEncoder(w).Encode(ActionResponse{
				Success: false,
				Message: "UI dump did not start with a readable status header",
				Data:    trimmed,
				Notice:  notice,
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
		json.NewEncoder(w).Encode(ActionResponse{
			Success: true,
			Message: observationStatus(env),
			Data:    observationText(env, rows),
			Notice:  notice,
		})
	})

	mux.HandleFunc("/api/apps", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Header().Set("Access-Control-Allow-Origin", "*")

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

		json.NewEncoder(w).Encode(ActionResponse{
			Success: true,
			Message: "OK",
			Data:    trimmed,
		})
	})

	mux.HandleFunc("/api/click", func(w http.ResponseWriter, r *http.Request) {		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
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
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Notice: popPendingHandoffNotice()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Notice: popPendingHandoffNotice()})
	})

	mux.HandleFunc("/api/swipe", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
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
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Notice: popPendingHandoffNotice()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Notice: popPendingHandoffNotice()})
	})

	mux.HandleFunc("/api/type", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
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

		out, err := runTool("type", did, targetStr, p.Text)
		if err != nil {
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: out, Notice: popPendingHandoffNotice()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: out, Data: out, Notice: popPendingHandoffNotice()})
	})

	mux.HandleFunc("/api/key", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
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
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Notice: popPendingHandoffNotice()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Notice: popPendingHandoffNotice()})
	})

	mux.HandleFunc("/api/launch", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		_, targetDid, err := ensureTargetReady()
		if err != nil {
			w.WriteHeader(http.StatusNotFound)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error()})
			return
		}
		var p struct {
			Package  string `json:"package"`
			Activity string `json:"activity"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}

		// 1. Check if the requested app is already running in an activity stack
		if existingStack, found := findStackForPackageAndActivity(p.Package, p.Activity); found {
			if existingStack.DisplayID != targetDid {
				// App is running on another display (e.g. Display 0) -> Smoothly reparent to targetDid!
				cmd := exec.Command("/system/bin/cmd", "activity", "display", "move-stack", strconv.Itoa(existingStack.StackID), strconv.Itoa(targetDid))
				out, err := cmd.CombinedOutput()
				if err == nil {
					json.NewEncoder(w).Encode(ActionResponse{
						Success: true,
						Message: fmt.Sprintf("Smoothly moved existing stack %d of %s to display %d", existingStack.StackID, p.Package, targetDid),
						Data:    string(out),
						Notice:  popPendingHandoffNotice(),
					})
					return
				}
				// If move-stack somehow failed, fall through to am start
			} else {
				// App is already on target display
				if existingStack.Visible && p.Activity == "" {
					json.NewEncoder(w).Encode(ActionResponse{
						Success: true,
						Message: fmt.Sprintf("App %s is already active on display %d", p.Package, targetDid),
						Notice:  popPendingHandoffNotice(),
					})
					return
				}
			}
		}

		// 2. Cold start fallback: launch via am start --display
		did := strconv.Itoa(targetDid)
		args := []string{"start", "--display", did}
		if p.Activity != "" {
			args = append(args, "-n", p.Package+"/"+p.Activity)
		} else {
			actBytes, _ := exec.Command("/system/bin/cmd", "package", "resolve-activity", "--brief", p.Package).Output()
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
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: err.Error(), Data: string(out), Notice: popPendingHandoffNotice()})
			return
		}
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: string(out), Notice: popPendingHandoffNotice()})
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
		resolvedTitle := resolveSessionTitle(sid)
		if resolvedTitle != "" {
			p.Subtext = resolvedTitle
		} else if len(p.Subtext) > 15 || strings.Contains(p.Subtext, "？") || strings.Contains(p.Subtext, "?") || strings.Contains(p.Subtext, "吗") {
			p.Subtext = "移动端任务"
		}

		isCompletedStr := "false"
		if p.IsCompleted || (p.Total > 0 && p.Completed >= p.Total) {
			isCompletedStr = "true"
			if p.Title == "" || strings.Contains(p.Title, "完成") {
				p.Title = "已完成"
			}
		}

		// Ensure app process is unfrozen from ColorOS Hans/Freezer and AMS BroadcastQueue
		thawAppProcess()

		// Direct foreground broadcast -> ColorOS Native Fluid Cloud (no Activity trampoline needed)
		cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am broadcast --receiver-foreground -f 0x01000000 -n com.agent.mobileuse/.NotifyReceiver -a com.agent.mobileuse.ACTION_NOTIFY --es title "$NOTIFY_TITLE" --es subtext "$NOTIFY_SUBTEXT" --es tag "$NOTIFY_TAG" --es content "$NOTIFY_CONTENT" --es url "$NOTIFY_URL" --es session_id "$NOTIFY_SESSION_ID" --ei total "$NOTIFY_TOTAL" --ei completed "$NOTIFY_COMPLETED" --ez is_completed "$NOTIFY_IS_COMPLETED"`)
		cmd.Env = append(os.Environ(),
			"NOTIFY_TITLE="+p.Title,
			"NOTIFY_SUBTEXT="+p.Subtext,
			"NOTIFY_TAG="+p.Tag,
			"NOTIFY_CONTENT="+p.Content,
			"NOTIFY_URL="+p.URL,
			"NOTIFY_SESSION_ID="+sid,
			fmt.Sprintf("NOTIFY_TOTAL=%d", p.Total),
			fmt.Sprintf("NOTIFY_COMPLETED=%d", p.Completed),
			"NOTIFY_IS_COMPLETED="+isCompletedStr,
		)
		out, err := cmd.CombinedOutput()
		if err == nil && strings.Contains(string(out), "result=0") {
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

		// Wake up process and trigger notification banner via Activity launch or broadcast
		thawAppProcess()
		st := getStatus()
		if st.Mode == "foreground" {
			// Foreground mode: launch directly with interactive BottomSheet on Display 0
			cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am start -f 0x18000000 -n com.agent.mobileuse/.QuestionActivity --es request_id "$REQ_ID" --es data "$REQ_DATA" 2>/dev/null`)
			cmd.Env = append(os.Environ(),
				"REQ_ID="+p.RequestID,
				"REQ_DATA="+string(bodyBytes),
			)
			cmd.Run()
		} else {
			// Background mode: trigger Question via pure foreground broadcast -> Native Fluid Cloud on status bar!
			cmd := exec.Command("/system/bin/sh", "-c", `/system/bin/am broadcast --receiver-foreground -f 0x01000000 -n com.agent.mobileuse/.QuestionReceiver -a com.agent.mobileuse.ACTION_QUESTION --es request_id "$REQ_ID" --es data "$REQ_DATA" 2>/dev/null`)
			cmd.Env = append(os.Environ(),
				"REQ_ID="+p.RequestID,
				"REQ_DATA="+string(bodyBytes),
			)
			cmd.Run()
		}

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
			// Cancelled from HTTP client
			thawAppProcess()
			exec.Command("/system/bin/sh", "-c", `/system/bin/am broadcast --receiver-foreground -f 0x01000000 -n com.agent.mobileuse/.QuestionReceiver -a com.agent.mobileuse.ACTION_QUESTION_CANCEL --es request_id "`+p.RequestID+`"`).Run()
		case <-time.After(timeout):
			thawAppProcess()
			exec.Command("/system/bin/sh", "-c", `/system/bin/am broadcast --receiver-foreground -f 0x01000000 -n com.agent.mobileuse/.QuestionReceiver -a com.agent.mobileuse.ACTION_QUESTION_CANCEL --es request_id "`+p.RequestID+`"`).Run()
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
		thawAppProcess()
		exec.Command("/system/bin/sh", "-c", `/system/bin/am broadcast --receiver-foreground -f 0x01000000 -n com.agent.mobileuse/.QuestionReceiver -a com.agent.mobileuse.ACTION_QUESTION_CANCEL --es request_id "`+p.RequestID+`"`).Run()
		json.NewEncoder(w).Encode(ActionResponse{Success: true, Message: "Question cancelled"})
	})

	mux.HandleFunc("/api/task_event", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		if r.Method == "OPTIONS" {
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})

	mux.HandleFunc("/api/shell", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Access-Control-Allow-Origin", "*")
		var p struct {
			Command string `json:"command"`
		}
		if err := json.NewDecoder(r.Body).Decode(&p); err != nil {
			w.WriteHeader(http.StatusBadRequest)
			json.NewEncoder(w).Encode(ActionResponse{Success: false, Message: "Invalid parameters"})
			return
		}
		cmd := exec.Command("/system/bin/sh", "-c", p.Command)
		out, err := cmd.CombinedOutput()
		exitCode := 0
		if err != nil {
			if exitErr, ok := err.(*exec.ExitError); ok {
				exitCode = exitErr.ExitCode()
			} else {
				exitCode = 1
			}
		}
		json.NewEncoder(w).Encode(map[string]any{
			"output":    string(out),
			"exit_code": exitCode,
			"success":   exitCode == 0,
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
