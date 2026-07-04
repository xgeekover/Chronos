import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "./client";

function mockFetch(status: number, body: unknown) {
  return vi.fn(async () => ({
    ok: status >= 200 && status < 300,
    status,
    json: async () => body,
  })) as unknown as typeof fetch;
}

const AUTH = JSON.stringify({ token: "T", username: "admin", role: "ADMIN" });

afterEach(() => {
  localStorage.clear();
  vi.restoreAllMocks();
});

describe("api client", () => {
  it("prefixes /api, attaches the bearer token, and parses JSON", async () => {
    localStorage.setItem("chronos-auth", AUTH);
    const f = mockFetch(200, [{ id: "d1" }]);
    vi.stubGlobal("fetch", f);
    const out = await api.listDevices();
    expect(out).toEqual([{ id: "d1" }]);
    const [url, init] = (f as unknown as { mock: { calls: unknown[][] } }).mock
      .calls[0] as [string, RequestInit];
    expect(url).toBe("/api/devices");
    expect((init.headers as Record<string, string>).Authorization).toBe(
      "Bearer T",
    );
  });

  it("maps a non-ok response to ApiError carrying status + body.error", async () => {
    vi.stubGlobal("fetch", mockFetch(409, { error: "conflict" }));
    await expect(api.listDevices()).rejects.toMatchObject({
      status: 409,
      message: "conflict",
    });
  });

  it("on 401 clears auth and dispatches chronos-unauthorized", async () => {
    localStorage.setItem("chronos-auth", AUTH);
    vi.stubGlobal("fetch", mockFetch(401, {}));
    const onUnauth = vi.fn();
    window.addEventListener("chronos-unauthorized", onUnauth);
    await expect(api.listDevices()).rejects.toThrow("session expired");
    expect(localStorage.getItem("chronos-auth")).toBeNull();
    expect(onUnauth).toHaveBeenCalled();
    window.removeEventListener("chronos-unauthorized", onUnauth);
  });
});
