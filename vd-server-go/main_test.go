package main

import "testing"

// These tests guard the one seam where the Java tool's text and the daemon's JSON
// envelope meet. Both sides were changed together on 2026-09-20 (the observation
// became one flat line per element), and every defect found during that work was in
// THIS function's edge cases rather than in either side's happy path:
//
//   - a failure body is a SINGLE line, so an earlier revision that required a newline
//     classified "the tool threw" as "unparseable output";
//   - the failure message arrives wire-quoted, so stripping only the prefix produced
//     `error="\"...\""` once %q re-quoted it;
//   - the tool's `size=WxH` has to become the width/height keys the rest of the stack
//     reads, or a dump that came back 0x0 leaves the model with coordinates in an
//     unknown space.
func TestSplitAndRebuildSuccess(t *testing.T) {
	body := "ok display=5 size=1272x2800 windows=1 x_extent=0,1272 total=38 returned=38" +
		" act_sent=37 act_total=37 truncated=0\n" +
		"# columns line\n" +
		"3 Button \"发送\" 10,20,30,40 c\n"
	env, rows, ok := splitObservation(body)
	if !ok {
		t.Fatal("success body rejected")
	}
	env["mode"] = "background"
	env["target_display_id"] = 5

	got := observationText(env, rows)
	want := "ok display=5 width=1272 height=2800 windows=1 mode=background target_display_id=5" +
		" total=38 returned=38 act_sent=37 act_total=37 truncated=0 x_extent=0,1272\n" +
		"# columns line\n" +
		"3 Button \"发送\" 10,20,30,40 c\n"
	if got != want {
		t.Fatalf("round trip mismatch:\n got %q\nwant %q", got, want)
	}
	if rows == "" {
		t.Fatal("rows were dropped")
	}
}

func TestFailureBodyIsOneLine(t *testing.T) {
	body := `fail error="java.lang.IllegalStateException: no window"`
	env, rows, ok := splitObservation(body)
	if !ok {
		t.Fatal("a failure body must be accepted, not treated as unparseable output")
	}
	if isOK, _ := env["ok"].(bool); isOK {
		t.Fatal("failure body parsed as a success")
	}
	if rows != "" {
		t.Fatalf("a failure carries no rows, got %q", rows)
	}
	if got := observationText(env, rows); got != body+"\n" {
		t.Fatalf("failure must survive the round trip quoted once, got %q", got)
	}
	if msg := observationStatus(env); msg == "tree <nil> nodes, <nil>/<nil> actionable" {
		t.Fatal("a failed read must not be summarised as an empty tree")
	}
}

func TestHeaderlessBodyIsRefused(t *testing.T) {
	// The daemon must not decorate something it cannot classify: handing the model an
	// envelope with no status would hide whether the read worked at all.
	if _, _, ok := splitObservation("just some text"); ok {
		t.Fatal("a body without a status header was accepted")
	}
}

func TestSizeIsTheOnlySourceOfGeometry(t *testing.T) {
	body := "ok display=5 size=1080x2340 windows=1 total=1 returned=1\n# columns\n1 View \"a\" 0,0,1,1 c\n"
	env, _, ok := splitObservation(body)
	if !ok {
		t.Fatal("body rejected")
	}
	if env["width"] != 1080 || env["height"] != 2340 {
		t.Fatalf("size=WxH did not become width/height: %v x %v", env["width"], env["height"])
	}
}

func TestCountersStayNumeric(t *testing.T) {
	// check-completeness.py subtracts act_total - act_sent. If these came back as
	// strings the comparison would either throw or silently compare garbage.
	body := "ok display=5 size=1x1 total=9 returned=8 act_sent=6 act_total=7 truncated=0\n# c\n"
	env, _, _ := splitObservation(body)
	for _, key := range []string{"total", "returned", "act_sent", "act_total", "truncated"} {
		if _, isInt := env[key].(int); !isInt {
			t.Fatalf("%s must decode as an int, got %T (%v)", key, env[key], env[key])
		}
	}
}
