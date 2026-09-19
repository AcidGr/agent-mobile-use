package com.agent.mobileuse;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.view.accessibility.AccessibilityEvent;

/**
 * A do-nothing accessibility service that exists so apps which gate their accessibility
 * tree behind "is a screen reader attached?" will open it up.
 *
 * WHY THIS EXISTS
 * ---------------
 * WeChat does not expose its node tree by default. Measured on a real device, dumping
 * its chat list with no accessibility service bound gives windows=1, nodes=0 every time
 * (the tool reports tree_blocked), and the content is visible only in a screenshot.
 *
 * With a real accessibility service bound, the same screen yields ~68 nodes including the
 * full chat list. Measured hit rate across three consecutive dumps of one unchanged
 * screen, each after a fresh cold start:
 *
 *     no service bound     0/3   (0, 0, 0 nodes)
 *     TalkBack bound       1/3   (0, 68, 0 nodes)
 *     SelectToSpeak bound  3/3   (68, 68, 68 nodes)
 *
 * So the trigger is not the specific tool but the presence of a bound service, and the
 * exposure is asynchronous: with TalkBack the tree appeared on only one of three reads.
 * The public write-ups on WeChat RPA describe the same thing as "on-demand exposure" --
 * the full tree is built only once a legitimate accessibility client is detected.
 *
 * This class deliberately does nothing with the events it receives. It is not a screen
 * reader, it does not speak, it does not touch focus, it does not change how taps are
 * interpreted (unlike TalkBack, where a single tap only explores and activation needs a
 * double tap). It only needs to be bound and to look like a legitimate client.
 *
 * SelectToSpeak works for the same reason and would be the zero-work option, but it sets
 * requestA11yBtn=true, which puts a floating button on the user's screen. This service
 * uses flagDefault instead, so there is no button and no visual side effect.
 */
public class AgentA11yService extends AccessibilityService {

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        // Declaring the flags in XML was not enough: measurements showed the binding
        // coming up with feedbackType = FEEDBACK_SPOKEN only, i.e. the runtime info did
        // not match what the resource asked for. Setting them here as well removes any
        // doubt about the service's advertised shape at the moment an app inspects it.
        //
        // The bit values are the AccessibilityServiceInfo constants, spelled out because
        // the compiled-in android.jar for this project is API 23 and some of these names
        // postdate it:
        //   FLAG_DEFAULT                        0x00000001
        //   FLAG_INCLUDE_NOT_IMPORTANT_VIEWS    0x00000002
        //   FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY 0x00000008
        //   FLAG_REPORT_VIEW_IDS                0x00000010
        //   FLAG_RETRIEVE_INTERACTIVE_WINDOWS   0x00000040
        //
        // Only the first four are set. FLAG_REQUEST_ACCESSIBILITY_BUTTON is deliberately
        // NOT set: it is what draws the floating button, and a floating button is exactly
        // the user-visible cost this service exists to avoid.
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.eventTypes = 0x401843;
            info.feedbackType = 0x9;
            info.flags |= 0x1 | 0x2 | 0x8 | 0x10 | 0x40;
            info.notificationTimeout = 0;
            setServiceInfo(info);
        }
    }

    /**
     * Intentionally empty. We are not reacting to the UI, we are only making Android --
     * and through it, apps that check for an attached client -- aware that an
     * accessibility service is running.
     *
     * Dropping the event immediately also keeps this off the UI thread's critical path;
     * there is no work here to make an app feel slow.
     */
    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // no-op by design
    }

    @Override
    public void onInterrupt() {
        // no-op: nothing is in progress to interrupt
    }
}
