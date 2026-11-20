package com.pharrlabs.murmur

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.AdapterView
import android.view.View

/** The floating "ask" box behind the Ask widget and the Murmur voice tile: pick agent, type or speak, send. */
class QuickAskActivity : Activity() {
    private lateinit var input: EditText
    private lateinit var spinner: Spinner
    private var agents: List<Agent> = emptyList()
    private var wanted = ""

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        wanted = intent.getStringExtra("agent") ?: Cfg.tileAgent(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
        }
        spinner = Spinner(this)
        col.addView(spinner)
        input = EditText(this).apply {
            hint = "Ask..."
            minLines = 3
            maxLines = 8
            gravity = Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        col.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        row.addView(Button(this).apply { text = "Speak"; isAllCaps = false; setOnClickListener { speak() } })
        row.addView(Button(this).apply { text = "Send"; isAllCaps = false; setOnClickListener { send() } })
        col.addView(row)
        setContentView(col)
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP)

        // Names load from the relay; until then the list is just the wanted agent.
        fillSpinner(listOf(Agent(wanted, wanted.replaceFirstChar { it.uppercase() })))
        loadAgents(this) { list -> agents = list; fillSpinner(list) }

        input.setText(intent.getStringExtra("text") ?: "")
        if (intent.getBooleanExtra("voice", false)) speak()
    }

    private fun fillSpinner(list: List<Agent>) {
        agents = list
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, list.map { it.name })
        val i = list.indexOfFirst { it.id == wanted }
        if (i >= 0) spinner.setSelection(i)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { wanted = agents[pos].id }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
    }

    private fun speak() {
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Ask " + (agents.firstOrNull { it.id == wanted }?.name ?: "Murmur"))
        try { startActivityForResult(i, 1) } catch (e: Exception) { /* no recognizer: type instead */ }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) {
            val said = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() ?: return
            input.setText((input.text.toString() + " " + said).trim())
            input.setSelection(input.text.length)
        }
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra("ask_agent", wanted).putExtra("ask_text", t)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }
}
