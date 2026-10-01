(function() {
  if (window.__DSH_MOBILE_OVERLAY_INITIALIZED__) return;
  window.__DSH_MOBILE_OVERLAY_INITIALIZED__ = true;

  document.documentElement.setAttribute("data-dsh-overlay", "true");
  window.__DSH_OVERLAY__ = true;

  var WHALE_ICON = "data:image/png;base64,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";

  // Prevent unwanted auto-focus on input fields when switching sessions or clicking home.
  // Soft keyboard should ONLY pop up when user explicitly touches/clicks the input box!
  var userTouchingInputRecently = false;
  var userTouchingInputTimer = null;
  var recordUserTouch = function (e) {
    var target = e.target instanceof Element ? e.target : (e.target && e.target.parentElement);
    var isInputArea = target && target.closest && target.closest(
      'input, textarea, [contenteditable="true"], [role="textbox"], [data-lexical-editor], [data-composer-input], [class*="_input"]'
    );
    if (isInputArea) {
      userTouchingInputRecently = true;
      if (userTouchingInputTimer) clearTimeout(userTouchingInputTimer);
      userTouchingInputTimer = setTimeout(function () {
        userTouchingInputRecently = false;
      }, 350);
    } else {
      userTouchingInputRecently = false;
      if (userTouchingInputTimer) clearTimeout(userTouchingInputTimer);
    }
  };
  document.addEventListener("pointerdown", recordUserTouch, true);
  document.addEventListener("touchstart", recordUserTouch, true);
  document.addEventListener("mousedown", recordUserTouch, true);

  var origFocus = HTMLElement.prototype.focus;
  HTMLElement.prototype.focus = function (opts) {
    var isInputLike = this.matches && this.matches('input:not([type="file"]):not([type="checkbox"]):not([type="radio"]):not([type="button"]):not([type="submit"]), textarea, [contenteditable="true"], [role="textbox"], [data-lexical-editor], [data-composer-input]');
    if (isInputLike && !userTouchingInputRecently) {
      return;
    }
    return origFocus.apply(this, arguments);
  };

  // Target session deep link & hot-switching support
  var getTargetSession = function () {
    try {
      var search = window.location && window.location.search;
      if (search) {
        var params = new URLSearchParams(search);
        var s = params.get("session") || params.get("session_id") || params.get("s");
        if (s) return s;
      }
      var hash = window.location && window.location.hash;
      if (hash && hash.indexOf("session-") !== -1) {
        var m = hash.match(/(session-[a-z0-9-]+)/);
        if (m) return m[1];
      }
    } catch (e) {}
    return null;
  };

  var targetSessionFromUrl = getTargetSession();
  if (targetSessionFromUrl) {
    try {
      localStorage.setItem("dsh.sessions.current", JSON.stringify({ sessionId: targetSessionFromUrl }));
    } catch (e) {}
  }

  // Global hot-switch interface for Android WebView bridge
  window.DSH_SWITCH_SESSION = function (sessionId) {
    if (!sessionId) return false;
    try {
      localStorage.setItem("dsh.sessions.current", JSON.stringify({ sessionId: sessionId }));
      if (location.search.indexOf("session=") >= 0) {
        location.search = location.search.replace(/session=[^&]+/, "session=" + sessionId);
      } else {
        location.search += (location.search ? "&" : "?") + "session=" + sessionId;
      }
      if (window.DSHOverlayBridge && window.DSHOverlayBridge.reportSession) {
        window.DSHOverlayBridge.reportSession(sessionId);
      }
      return true;
    } catch (e) {
      console.warn("[overlay] DSH_SWITCH_SESSION failed:", e);
    }
    return false;
  };

  function initUIElements() {
    if (!document.body) {
      setTimeout(initUIElements, 50);
      return;
    }

    // Auto-scroll chat bubbles to bottom when soft keyboard pops up or composer is focused
    var scrollChatToBottom = function () {
      var scroller = document.querySelector('[data-conversation-scroll], [class*="_scrollBody"], [class*="_scroll"]');
      if (scroller) {
        scroller.scrollTop = scroller.scrollHeight;
      }
    };

    var switchToChat = function () {
      try {
        var chatTab = Array.from(document.querySelectorAll('[data-conversation-tabs] button, [role="tab"]')).find(function (b) {
          var txt = b.textContent || "";
          return txt.indexOf("对话") !== -1 || txt.indexOf("Chat") !== -1;
        });
        if (chatTab) {
          chatTab.click();
          return true;
        }
        var firstTab = document.querySelector('[data-conversation-tabs] button:first-child');
        if (firstTab) {
          firstTab.click();
          return true;
        }
      } catch (e) {}
      return false;
    };

    var cfg = window.__DSH_MOBILE_CONFIG__ || {};

    var sidebarToggleSelector = '[data-slot="sidebar-toggle"], [aria-label*="sidebar" i], [aria-label*="侧边栏"], button[class*="toggleSidebar"], button[class*="_toggle"]';

    var collapseSidebarOnce = function () {
      var frame = document.querySelector('[class*="_frame"]');
      if (!frame) return false;
      if (frame.hasAttribute("data-sidebar-collapsed")) return true;
      var toggleBtn = document.querySelector(sidebarToggleSelector);
      if (!toggleBtn) return false;
      toggleBtn.click();
      return true;
    };

    // 1. Whale Button (Toggle Sidebar)
    var existingWhale = document.querySelector(".dsh-overlay-whale-btn");
    if (cfg.enableWhale === false) {
      if (existingWhale) existingWhale.remove();
    } else if (!existingWhale) {
      var whaleBtn = document.createElement("button");
      whaleBtn.type = "button";
      whaleBtn.className = "dsh-overlay-whale-btn";
      whaleBtn.setAttribute("aria-label", "Toggle Sidebar");
      whaleBtn.innerHTML = '<img src="' + WHALE_ICON + '" alt="Whale Logo" />';
      whaleBtn.addEventListener("click", function (e) {
        e.preventDefault();
        e.stopPropagation();
        var toggleBtn = document.querySelector(sidebarToggleSelector);
        if (toggleBtn) {
          toggleBtn.click();
        }
      });
      document.body.appendChild(whaleBtn);
    }

    // 2. Home Button (New Session & Clear)
    var existingHome = document.querySelector(".dsh-overlay-home-btn");
    if (cfg.enableWhale === false) {
      if (existingHome) existingHome.remove();
    } else if (!existingHome) {
      var homeBtn = document.createElement("button");
      homeBtn.type = "button";
      homeBtn.className = "dsh-overlay-home-btn";
      homeBtn.setAttribute("aria-label", "New Blank Session");
      homeBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><path d="M3 10.25L12 3l9 7.25V20a1 1 0 01-1 1h-5v-6h-6v6H4a1 1 0 01-1-1v-9.75z"></path></svg>';
      homeBtn.addEventListener("click", function (e) {
        e.preventDefault();
        e.stopPropagation();

        try {
          if (document.activeElement && typeof document.activeElement.blur === "function") {
            document.activeElement.blur();
          }
          if (window.DSHOverlayBridge && typeof window.DSHOverlayBridge.hideSoftInput === "function") {
            window.DSHOverlayBridge.hideSoftInput();
          }
        } catch (err) {}

        try {
          var closeDialog = document.querySelector(
            '[role="dialog"] button[aria-label*="close" i], ' +
            '[role="dialog"] button[aria-label*="关闭"], ' +
            '[role="dialog"] button[class*="close"]'
          );
          if (closeDialog) closeDialog.click();
        } catch (err) {}

        switchToChat();

        var newBtn = document.querySelector(
          'button[class*="newSession"], button[class*="_newSession"], ' +
          'button[aria-label*="新会话"], button[aria-label*="新建会话"], ' +
          'button[aria-label*="New session" i], button[aria-label*="new chat" i], ' +
          '[data-slot="sidebar"] button[aria-label*="会话"]'
        );
        if (newBtn) {
          newBtn.click();
        }

        setTimeout(function () {
          switchToChat();
          scrollChatToBottom();
        }, 150);
      });
      document.body.appendChild(homeBtn);
    }

    // 3. Back to Chat Button
    var existingBack = document.querySelector(".dsh-overlay-back-chat-btn");
    if (cfg.enableWhale === false) {
      if (existingBack) existingBack.remove();
    } else if (!existingBack) {
      var backToChatBtn = document.createElement("button");
      backToChatBtn.type = "button";
      backToChatBtn.className = "dsh-overlay-back-chat-btn";
      backToChatBtn.setAttribute("aria-label", "Back to Chat");
      backToChatBtn.innerHTML = '<svg viewBox="0 0 24 24" fill="none"><path d="M19 12H5M12 19l-7-7 7-7"/></svg><span>返回对话</span>';
      backToChatBtn.addEventListener("click", function (e) {
        e.preventDefault();
        e.stopPropagation();
        switchToChat();
      });
      document.body.appendChild(backToChatBtn);
    }

    // 4. Auto-collapse sidebar when a session row is tapped
    var onSidebarItemClick = function (event) {
      var target = event.target instanceof Element ? event.target : (event.target && event.target.parentElement);
      if (!target) return;
      var frame = document.querySelector('[class*="_frame"]');
      if (!frame || frame.hasAttribute("data-sidebar-collapsed")) return;

      var autoCollapseTarget = target.closest && target.closest(
        '[class*="_sessionRow"], [class*="_searchResultRow"], ' +
        '[class*="_panelRow"], nav[class*="_panelList"] button, ' +
        'button[class*="_panelRow"], button[aria-label*="插件"], button[aria-label*="Plugin" i], ' +
        'button[class*="_newSession"], button[aria-label*="新会话"], button[aria-label*="新建会话"]'
      );
      if (autoCollapseTarget) {
        setTimeout(function () {
          var currentFrame = document.querySelector('[class*="_frame"]');
          if (currentFrame && !currentFrame.hasAttribute("data-sidebar-collapsed")) {
            var toggleBtn = document.querySelector('[data-slot="sidebar-toggle"], [aria-label*="sidebar" i], [aria-label*="侧边栏"], button[class*="toggleSidebar"], button[class*="_toggle"]');
            if (toggleBtn) toggleBtn.click();
          }
        }, 80);
      }
    };
    document.addEventListener("click", onSidebarItemClick, false);

    // 5. Scroll chat to bottom on composer focus
    var onComposerFocus = function (e) {
      var currentCfg = window.__DSH_MOBILE_CONFIG__ || {};
      if (currentCfg.enableKeyboardAssist === false) return;
      var target = e.target;
      if (target && (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.isContentEditable || (target.closest && target.closest('[data-composer-card], [class*="_card"]')))) {
        scrollChatToBottom();
        setTimeout(scrollChatToBottom, 100);
        setTimeout(scrollChatToBottom, 250);
        setTimeout(scrollChatToBottom, 400);
      }
    };
    document.addEventListener("focusin", onComposerFocus, true);

    // 6. Viewport resize listener
    var onViewportResize = function () {
      var currentCfg = window.__DSH_MOBILE_CONFIG__ || {};
      if (currentCfg.enableKeyboardAssist === false) return;
      var active = document.activeElement;
      if (active && (active.tagName === "INPUT" || active.tagName === "TEXTAREA" || active.isContentEditable || (active.closest && active.closest('[data-composer-card], [class*="_card"]')))) {
        scrollChatToBottom();
        setTimeout(scrollChatToBottom, 100);
        setTimeout(scrollChatToBottom, 250);
      }
    };
    if (window.visualViewport) {
      window.visualViewport.addEventListener("resize", onViewportResize);
    }
    window.addEventListener("resize", onViewportResize);

    // Keep the overlay out of a persisted right-sidebar state. Reopening the
    // Android overlay should start on the conversation Home surface.
    var resetRightSidebarOnOverlayOpen = function () {
      var attempts = 0;
      var closeWhenReady = function () {
        var panel = document.querySelector('[data-sidebar-right-session][data-sidebar-right-open]');
        if (panel) {
          var toggle = panel.querySelector('[data-sidebar-right-toggle]') ||
            document.querySelector('[data-sidebar-right-toggle]');
          if (toggle) {
            toggle.click();
            return;
          }
        }
        if (attempts++ < 20) setTimeout(closeWhenReady, 100);
      };
      setTimeout(closeWhenReady, 0);
    };
    resetRightSidebarOnOverlayOpen();

    // 7. Finger-tracked horizontal drawer gestures.
    // Home stays full-size while the fixed sidebars translate over it.
    var swipeStartX = 0;
    var swipeStartY = 0;
    var swipeLastX = 0;
    var swipeStartTime = 0;
    var swipeTracking = false;
    var swipeMode = null;
    var swipePanel = null;
    var swipeWidth = 0;
    var SWIPE_LOCK_X = 10;
    var SWIPE_DOMINANCE = 1.05;
    var SWIPE_COMMIT_RATIO = 0.24;
    var SWIPE_FLING_VELOCITY = 0.45;

    var leftSidebarIsOpen = function () {
      var frame = document.querySelector('[class*="_frame"]');
      return !!(frame && !frame.hasAttribute("data-sidebar-collapsed"));
    };

    var rightSidebarIsOpen = function () {
      return !!document.querySelector('[data-sidebar-right-session][data-sidebar-right-open]');
    };

    var getLeftDrawer = function () {
      return document.querySelector('[class*="_sidebarCol"]');
    };

    var getRightDrawer = function () {
      return document.querySelector('[class*="_rightbarCol"]');
    };

    var openLeftSidebar = function () {
      if (rightSidebarIsOpen()) return false;
      var frame = document.querySelector('[class*="_frame"]');
      if (!frame || !frame.hasAttribute("data-sidebar-collapsed")) return false;
      var toggleBtn = document.querySelector(sidebarToggleSelector);
      if (!toggleBtn) return false;
      toggleBtn.click();
      return true;
    };

    var closeLeftSidebar = function () {
      if (!leftSidebarIsOpen()) return false;
      return collapseSidebarOnce();
    };

    var findRightSidebarExpand = function () {
      return document.querySelector('[data-sidebar-right-expand]');
    };

    var openRightSidebar = function () {
      if (leftSidebarIsOpen() || rightSidebarIsOpen()) return false;
      var expand = findRightSidebarExpand();
      if (!expand) return false;
      expand.click();
      return true;
    };

    var closeRightSidebar = function () {
      var panel = document.querySelector('[data-sidebar-right-session][data-sidebar-right-open]');
      if (!panel) return false;
      var toggle = panel.querySelector('[data-sidebar-right-toggle]') ||
        document.querySelector('[data-sidebar-right-toggle]');
      if (!toggle) return false;
      toggle.click();
      return true;
    };

    var gestureIgnoredTarget = function (target) {
      return !!(target && target.closest && target.closest(
        'input, textarea, select, [contenteditable="true"], [role="textbox"], ' +
        '[data-lexical-editor], [data-composer-input], ' +
        '[class*="_resizer"], [class*="_resizeHandle"]'
      ));
    };

    var clearDrawerInlineTransform = function () {
      if (!swipePanel) return;
      var panel = swipePanel;
      document.documentElement.removeAttribute("data-dsh-sidebar-dragging");
      requestAnimationFrame(function () {
        requestAnimationFrame(function () {
          panel.style.removeProperty("transform");
          panel.style.removeProperty("pointer-events");
        });
      });
    };

    var setDrawerPosition = function (x) {
      if (!swipePanel) return;
      swipePanel.style.setProperty("transform", "translate3d(" + x + "px,0,0)", "important");
      swipePanel.style.setProperty("pointer-events", "auto", "important");
    };

    var beginDrawerMode = function (mode) {
      swipeMode = mode;
      swipeWidth = Math.max(1, document.documentElement.clientWidth || window.innerWidth || 1);

      if (mode === "open-left") {
        swipePanel = getLeftDrawer();
        if (!swipePanel || !openLeftSidebar()) return false;
        setDrawerPosition(-swipeWidth);
      } else if (mode === "close-left") {
        swipePanel = getLeftDrawer();
        if (!swipePanel) return false;
        setDrawerPosition(0);
      } else if (mode === "open-right") {
        swipePanel = getRightDrawer();
        if (!swipePanel || !openRightSidebar()) return false;
        setDrawerPosition(swipeWidth);
      } else if (mode === "close-right") {
        swipePanel = getRightDrawer();
        if (!swipePanel) return false;
        setDrawerPosition(0);
      } else {
        return false;
      }

      document.documentElement.setAttribute("data-dsh-sidebar-dragging", mode);
      return true;
    };

    var updateDrawerDrag = function (dx) {
      if (!swipeMode || !swipePanel) return;
      if (swipeMode === "open-left") {
        setDrawerPosition(Math.min(0, Math.max(-swipeWidth, -swipeWidth + Math.max(0, dx))));
      } else if (swipeMode === "close-left") {
        setDrawerPosition(Math.min(0, Math.max(-swipeWidth, Math.min(0, dx))));
      } else if (swipeMode === "open-right") {
        setDrawerPosition(Math.max(0, Math.min(swipeWidth, swipeWidth + Math.min(0, dx))));
      } else if (swipeMode === "close-right") {
        setDrawerPosition(Math.max(0, Math.min(swipeWidth, Math.max(0, dx))));
      }
    };

    var finishDrawerDrag = function (dx, elapsed) {
      if (!swipeMode || !swipePanel) return;
      var progress = Math.min(1, Math.abs(dx) / Math.max(1, swipeWidth));
      var velocity = Math.abs(dx) / Math.max(1, elapsed);
      var commit = progress >= SWIPE_COMMIT_RATIO || velocity >= SWIPE_FLING_VELOCITY;
      var mode = swipeMode;

      if (mode === "open-left" && !commit) closeLeftSidebar();
      if (mode === "close-left" && commit) closeLeftSidebar();
      if (mode === "open-right" && !commit) closeRightSidebar();
      if (mode === "close-right" && commit) closeRightSidebar();

      clearDrawerInlineTransform();
      swipeMode = null;
      swipePanel = null;
      swipeWidth = 0;
    };

    var onSwipeStart = function (event) {
      if (!event.touches || event.touches.length !== 1) {
        swipeTracking = false;
        return;
      }
      var target = event.target instanceof Element ? event.target : null;
      if (gestureIgnoredTarget(target)) {
        swipeTracking = false;
        return;
      }
      swipeStartX = swipeLastX = event.touches[0].clientX;
      swipeStartY = event.touches[0].clientY;
      swipeStartTime = performance.now();
      swipeTracking = true;
      swipeMode = null;
      swipePanel = null;
    };

    var onSwipeMove = function (event) {
      if (!swipeTracking || !event.touches || event.touches.length !== 1) return;

      var x = event.touches[0].clientX;
      var y = event.touches[0].clientY;
      var dx = x - swipeStartX;
      var dy = y - swipeStartY;
      var absX = Math.abs(dx);
      var absY = Math.abs(dy);
      swipeLastX = x;

      if (!swipeMode) {
        if (absX < SWIPE_LOCK_X) return;
        if (absX < absY * SWIPE_DOMINANCE) {
          swipeTracking = false;
          return;
        }

        var mode;
        if (leftSidebarIsOpen()) {
          if (dx >= 0) { swipeTracking = false; return; }
          mode = "close-left";
        } else if (rightSidebarIsOpen()) {
          if (dx <= 0) { swipeTracking = false; return; }
          mode = "close-right";
        } else {
          mode = dx > 0 ? "open-left" : "open-right";
        }

        if (!beginDrawerMode(mode)) {
          swipeTracking = false;
          return;
        }
      }

      if (event.cancelable) event.preventDefault();
      updateDrawerDrag(dx);
    };

    var onSwipeEnd = function (event) {
      if (!swipeTracking) return;
      swipeTracking = false;
      if (!swipeMode) return;

      var endX = swipeLastX;
      if (event.changedTouches && event.changedTouches.length === 1) {
        endX = event.changedTouches[0].clientX;
      }
      finishDrawerDrag(endX - swipeStartX, performance.now() - swipeStartTime);
    };

    var onSwipeCancel = function () {
      if (!swipeTracking) return;
      swipeTracking = false;
      if (!swipeMode) return;

      if (swipeMode === "open-left") closeLeftSidebar();
      if (swipeMode === "open-right") closeRightSidebar();
      clearDrawerInlineTransform();
      swipeMode = null;
      swipePanel = null;
      swipeWidth = 0;
    };

    var rightSwipeEdge = document.querySelector(".dsh-overlay-right-swipe-edge");
    if (!rightSwipeEdge) {
      rightSwipeEdge = document.createElement("div");
      rightSwipeEdge.className = "dsh-overlay-right-swipe-edge";
      rightSwipeEdge.setAttribute("aria-hidden", "true");
      document.body.appendChild(rightSwipeEdge);
    }

    document.addEventListener("touchstart", onSwipeStart, { capture: true, passive: true });
    document.addEventListener("touchmove", onSwipeMove, { capture: true, passive: false });
    document.addEventListener("touchend", onSwipeEnd, { capture: true, passive: true });
    document.addEventListener("touchcancel", onSwipeCancel, { capture: true, passive: true });

    window.DSH_OVERLAY_OPEN_LEFT_SIDEBAR = openLeftSidebar;
    window.DSH_OVERLAY_CLOSE_LEFT_SIDEBAR = closeLeftSidebar;
    window.DSH_OVERLAY_OPEN_RIGHT_SIDEBAR = openRightSidebar;
    window.DSH_OVERLAY_CLOSE_RIGHT_SIDEBAR = closeRightSidebar;

    // 8. Periodic session reporting to OverlayBridge
    setInterval(function() {
      try {
        var raw = localStorage.getItem('dsh.sessions.current');
        if (raw) {
          var obj = JSON.parse(raw);
          if (obj && obj.sessionId && window.DSHOverlayBridge && window.DSHOverlayBridge.reportSession) {
            window.DSHOverlayBridge.reportSession(obj.sessionId);
          }
        }
      } catch(e) {}
    }, 1000);
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", initUIElements);
  } else {
    initUIElements();
  }
})();
