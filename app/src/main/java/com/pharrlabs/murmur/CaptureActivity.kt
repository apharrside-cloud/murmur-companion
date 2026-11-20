package com.pharrlabs.murmur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognizerIntent
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Voice dump. Opens straight into the speech recogniser, files what you said as a task or note, and shows where it went with an Undo.
 * Say it the way you would to a person: "remind me to print the youth lesson tomorrow", "idea: sermon series on the minor prophets".
 */
class CaptureActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var undo: Button
    private lateinit var file: Button
    private lateinit var speak: Button
    private var appendNext = false
    private val handler = Handler(Looper.getMainLooper())
    private val closer = Runnable { finish() }
    private var lastId = ""
    private var busy = false

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(20), dp(20), dp(16)) }
        status = Ui.text(this, "Listening...", 16f, bold = true)
        col.addView(status)
        input = EditText(this).apply {
            hint = "or type it"
            minLines = 2; maxLines = 6; gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        col.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        // Nothing is filed until you press Send: speech only fills the box.
        row.addView(Button(this).apply { text = "Cancel"; isAllCaps = false; setOnClickListener { finish() } })
        undo = Button(this).apply { text = "Undo"; isAllCaps = false; visibility = View.GONE; setOnClickListener { doUndo() } }
        row.addView(undo)
        speak = Button(this).apply { text = "Speak"; isAllCaps = false; setOnClickListener { stopTimer(); appendNext = !input.text.isNullOrBlank(); listen() } }
        row.addView(speak)
        file = Button(this).apply { text = "Send"; isAllCaps = false; setOnClickListener { submit(input.text.toString()) } }
        row.addView(file)
        col.addView(row)
        setContentView(col)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP)
        input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                stopTimer(); file.isEnabled = !busy && !s.isNullOrBlank()
                speak.text = if (s.isNullOrBlank()) "Speak" else "Keep talking"
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        file.isEnabled = false
        Thread { Capture.flush(this) }.start()

        val pre = intent.getStringExtra("text")
        if (!pre.isNullOrBlank()) { input.setText(pre); submit(pre) } else listen()
    }

    private fun listen() {
        status.text = "Listening..."
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, Cfg.assistant(this).let { n -> if (Cfg.caps(this).tasks || Cfg.caps(this).notes) "Task or note, or start with $n" else "What should $n do?" })
            // Best effort: the system recogniser may ignore these, which is why speech never files by itself.
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
        try { startActivityForResult(i, 1) } catch (e: Exception) { status.text = "No speech recogniser. Type it." }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 1) return
        val said = if (res == RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
        if (said.isNullOrBlank()) {
            if (input.text.isNullOrBlank()) finish() else status.text = "Edit it, then Send."
            return
        }
        val prev = input.text.toString().trim()
        val full = if (appendNext && prev.isNotEmpty()) "$prev $said" else said
        appendNext = false
        input.setText(full)
        input.setSelection(full.length)
        status.text = "Check it, then Send. Keep talking adds to it."
    }

    private fun submit(text: String) {
        val t = text.trim()
        if (t.isEmpty() || busy) return
        busy = true
        file.isEnabled = false
        undo.visibility = View.GONE
        status.text = "Filing..."
        Thread {
            val r = Capture.file(this, t)
            runOnUiThread {
                busy = false
                status.text = r.line
                if (r.ok) {
                    lastId = r.id
                    if (!r.queued && r.canUndo) { undo.visibility = View.VISIBLE; undo.isEnabled = true }
                    Widgets.refreshAll(this)
                    handler.postDelayed(closer, if (r.queued) 3500 else 6000)
                } else {
                    file.isEnabled = true
                }
            }
        }.start()
    }

    private fun doUndo() {
        stopTimer()
        undo.isEnabled = false
        val id = lastId
        Thread {
            val ok = Capture.undo(this, id)
            runOnUiThread {
                status.text = if (ok) "Removed." else "Couldn't remove it. Delete it in Todoist."
                undo.visibility = View.GONE
                if (ok) { Widgets.refreshAll(this); handler.postDelayed(closer, 1200) }
            }
        }.start()
    }

    private fun stopTimer() = handler.removeCallbacks(closer)

    override fun onDestroy() {
        stopTimer()
        super.onDestroy()
    }
}
