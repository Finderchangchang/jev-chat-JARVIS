package com.google.android.accessibility.selecttospeak

import com.jev.probe.capture.ChatCaptureService

/**
 * The live capture service, registered under this system-style class name so
 * WeChat 8.0.78 exposes its node tree (verified in P1: a plainly-named service
 * is blocked to a single empty node, this class reads the full chat). All logic
 * lives in [ChatCaptureService]; only the class name differs.
 *
 * Do not rename this class or its Manifest registration — the disguise is what
 * gets past WeChat's node obfuscation.
 */
class SelectToSpeakService : ChatCaptureService()
