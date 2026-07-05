# Synthetic live-values demo (JavaScript-only, no adapter)

Chronos generates the moving values on the **Dashboard** / **Time Machine** entirely from a flow — no
device adapter required. This is the replacement for the old `SCRIPT_JAVA` demo devices: the sandboxed
**JavaScript Function node** synthesizes the values.

## 1. Create a storage node + tag (Tags view)

- **New node**: `line1`, retention `24` (hours) → **Add node**.
- **New tag**: name `temp`, type `NUMBER` → **Add tag**. This creates the tag `line1.temp`.

## 2. Author the flow (Flows view)

Drag and wire:

```
inject ─▶ function ─▶ tag ─▶ debug
```

- **inject** — interval `2000` (fire every 2 s).
- **function** — sandboxed JavaScript; a gentle random walk around 22 °C:
  ```js
  // pure ECMAScript — Math.random and flow.get/set are all sandbox-safe (no host access)
  let v = flow.get("v");
  if (v === undefined) v = 22;
  v += Math.random() - 0.5;           // random walk
  flow.set("v", v);
  msg.payload = Math.round(v * 100) / 100;
  return msg;
  ```
- **tag** — `line1.temp` (the canonicalKey from step 1).
- **debug** — optional, to watch messages in the Inspector.

## 3. Deploy & watch

- Click **Deploy & run**.
- **Dashboard** → the live-values table shows `line1.temp` updating every 2 s.
- **Time Machine** → pick `line1.temp` to chart its last 24 h.

That's a complete collection pipeline with no adapter and no Java — one sandboxed JavaScript node does
the work. Use **Export** on the Flows toolbar to save/share the flow as a `.flow.json` file.
