// The pairing page served at http://127.0.0.1:<port>/pair (this computer only).

const esc = (s) => String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c])

export function pairHtml({ link, code, name, relay, murmur }) {
  return `<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Pair your phone</title>
<style>
  body{font:16px/1.5 system-ui,sans-serif;max-width:640px;margin:2rem auto;padding:0 1rem;color:#1b1b1f}
  h1{font-size:1.4rem} code,textarea{font:13px ui-monospace,monospace}
  textarea{width:100%;height:7.5rem;padding:.5rem;box-sizing:border-box;word-break:break-all}
  button{font:inherit;padding:.5rem 1rem;border-radius:.5rem;border:1px solid #888;background:#f3f3f6;cursor:pointer}
  .box{border:1px solid #ccc;border-radius:.75rem;padding:1rem;margin:1rem 0}
  #qr{display:inline-block;background:#fff;padding:.5rem;line-height:0} .warn{color:#8a4b00}
  dl{margin:0} dt{font-weight:600;margin-top:.5rem} dd{margin:0;word-break:break-all}
</style></head><body>
<h1>Pair your phone with ${esc(name)}</h1>
<p>On the phone, open <b>Murmur Companion</b>. On the first screen choose <b>Paste pairing code</b> and paste the code below. Scanning the picture also works if your camera app can open links.</p>
<div class="box">
  <div id="qr" aria-label="Pairing QR code"></div>
</div>
<div class="box">
  <p><b>Pairing code</b></p>
  <textarea id="code" readonly>${esc(code)}</textarea>
  <p><button id="copy">Copy code</button> <span id="ok" hidden>Copied.</span></p>
</div>
<div class="box"><dl>
  <dt>Relay address (phone to this computer)</dt><dd>${esc(relay)}</dd>
  <dt>Murmur address (what the phone opens)</dt><dd>${esc(murmur)}</dd>
</dl></div>
<p class="warn"><b>Treat this code like a password.</b> Anyone who has it can read your notifications and answer approvals. It never leaves this computer except when you paste it into your own phone. To revoke it, delete <code>data/relay.json</code> and restart the relay; every phone must then be paired again.</p>
<script src="/pair/qr.js"></script>
<script>
  try { var q = qrcode(0, 'L'); q.addData(${JSON.stringify(link)}); q.make(); document.getElementById('qr').innerHTML = q.createSvgTag({ cellSize: 4, margin: 0 }) }
  catch (e) { document.getElementById('qr').textContent = 'The code is too long for a picture here. Copy the code instead.' }
  document.getElementById('copy').onclick = function () {
    var t = document.getElementById('code'); t.select()
    var done = function () { document.getElementById('ok').hidden = false }
    if (navigator.clipboard) navigator.clipboard.writeText(t.value).then(done, function () { document.execCommand('copy'); done() }); else { document.execCommand('copy'); done() }
  }
</script>
</body></html>`
}
