package com.jev.probe.capture

import android.app.Activity
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Synthetic chats for device tests. No accounts, network calls, or send controls. */
class DeviceTestActivity : Activity() {
    lateinit var titleView: TextView
    lateinit var input: EditText
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        titleView = TextView(this).apply { text = "Test Alice"; textSize = 24f }
        input = EditText(this).apply { hint = "Test reply input" }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 80, 24, 24)
            addView(titleView)
            addView(TextView(this@DeviceTestActivity).apply { text = "Synthetic message: hello" })
            addView(input)
        })
    }
    fun switchConversation() {
        titleView.text = "Test Bob"
        input.setText("Bob's draft")
    }
}
