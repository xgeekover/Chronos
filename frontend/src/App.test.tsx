import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it } from "vitest";
import { App } from "./App";

describe("App", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the login screen when signed out", () => {
    render(<App />);
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
  });

  it("renders the Chronos shell when authenticated", () => {
    localStorage.setItem(
      "chronos-auth",
      JSON.stringify({ token: "test", username: "admin", role: "ADMIN" }),
    );
    render(<App />);
    expect(screen.getByText("Chronos")).toBeInTheDocument();
    expect(
      screen.getByRole("heading", { name: "Dashboard" }),
    ).toBeInTheDocument();
  });
});
