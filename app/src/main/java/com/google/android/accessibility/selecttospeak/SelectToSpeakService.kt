package com.google.android.accessibility.selecttospeak

import com.jev.probe.capture.WeChatCaptureService

/**
 * The live capture service, registered under this system-style class name so
 * WeChat 8.0.78 exposes its node tree (verified in P1: a plainly-named service
 * is blocked to a single empty node, this class reads the full chat). All logic
 * lives in [WeChatCaptureService]; only the class name differs.
 */
class SelectToSpeakService : WeChatCaptureService()
