import { type FormEvent, useState } from "react";
import { login } from "../../api/client";
import { ErrorNote, inputClass } from "../../ui/kit";

/** Sign-in screen — exchanges username/password for a JWT (§11). */
export function Login({ onSuccess }: { onSuccess: () => void }) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("admin");
  const [error, setError] = useState<unknown>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await login(username, password);
      onSuccess();
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="flex min-h-screen items-center justify-center bg-zinc-950 text-zinc-100">
      <form
        onSubmit={submit}
        className="w-80 rounded-xl border border-white/10 bg-zinc-900/70 p-6 shadow-2xl"
      >
        <div className="mb-5 flex items-center gap-2">
          <div className="grid h-9 w-9 place-items-center rounded-lg bg-indigo-600 font-bold text-white">
            C
          </div>
          <div>
            <div className="font-semibold">Chronos</div>
            <div className="text-[11px] text-zinc-500">IoT Historian</div>
          </div>
        </div>
        <label className="mb-3 block">
          <span className="mb-1 block text-[11px] uppercase tracking-wider text-zinc-500">
            username
          </span>
          <input
            className={`${inputClass} w-full`}
            value={username}
            onChange={(e) => setUsername(e.target.value)}
          />
        </label>
        <label className="mb-4 block">
          <span className="mb-1 block text-[11px] uppercase tracking-wider text-zinc-500">
            password
          </span>
          <input
            type="password"
            className={`${inputClass} w-full`}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error ? (
          <div className="mb-3">
            <ErrorNote error={error} />
          </div>
        ) : null}
        <button
          type="submit"
          disabled={busy}
          className="w-full rounded-lg bg-indigo-600 px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-500 disabled:opacity-50"
        >
          {busy ? "signing in…" : "Sign in"}
        </button>
        <p className="mt-4 text-center text-[11px] leading-relaxed text-zinc-500">
          demo accounts: admin/admin · operator/operator · viewer/viewer
        </p>
      </form>
    </div>
  );
}
