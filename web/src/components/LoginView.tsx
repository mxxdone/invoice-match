"use client";

import React, { useState } from "react";
import { DEMO_ACCOUNTS, useAuth } from "@/lib/auth-context";

export function LoginView() {
  const { login, isLoading, error: authError } = useAuth();
  const [username, setUsername] = useState("submitter");
  const [password, setPassword] = useState("submitter-pass");
  const [formError, setFormError] = useState<string | null>(null);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setFormError(null);

    if (!username.trim() || !password.trim()) {
      setFormError("아이디와 비밀번호를 모두 입력해주세요.");
      return;
    }

    const res = await login({ username: username.trim(), password: password.trim() });
    if (!res.success && res.error) {
      setFormError(res.error);
    }
  };

  const handleSelectDemo = (demoUsername: string) => {
    setUsername(demoUsername);
    setPassword(`${demoUsername}-pass`);
    setFormError(null);
  };

  return (
    <main className="content-area" style={{ maxWidth: "560px", marginTop: "4vh" }}>
      <div className="card" style={{ padding: "2rem" }}>
        <div style={{ textAlign: "center", marginBottom: "1.5rem" }}>
          <h1 style={{ fontSize: "1.6rem", marginBottom: "0.4rem" }}>Invoice Match 로그인</h1>
          <p style={{ color: "var(--color-text-muted)", fontSize: "0.9rem" }}>
            Phase 1 인보이스 매칭 및 의사결정 업무 콘솔
          </p>
        </div>

        {/* Demo Accounts Guidance */}
        <section aria-labelledby="demo-accounts-title" style={{ marginBottom: "1.5rem" }}>
          <h2 id="demo-accounts-title" style={{ fontSize: "0.9rem", color: "var(--color-text-muted)", marginBottom: "0.5rem" }}>
            로컬 데모 계정 바로 선택:
          </h2>
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: "0.5rem" }}>
            {DEMO_ACCOUNTS.map((acc) => (
              <button
                key={acc.username}
                type="button"
                className="btn btn-secondary btn-sm"
                onClick={() => handleSelectDemo(acc.username)}
                style={{
                  display: "flex",
                  flexDirection: "column",
                  alignItems: "flex-start",
                  textAlign: "left",
                  padding: "0.5rem 0.6rem",
                  borderColor: username === acc.username ? "#2563eb" : "var(--color-border)",
                  backgroundColor: username === acc.username ? "#eff6ff" : "#ffffff",
                }}
                id={`demo-btn-${acc.username}`}
              >
                <div style={{ fontWeight: 600, fontSize: "0.85rem", color: "var(--color-text-main)" }}>
                  {acc.label}
                </div>
                <div style={{ fontSize: "0.75rem", color: "var(--color-text-muted)" }}>
                  {acc.description}
                </div>
              </button>
            ))}
          </div>
        </section>

        {(formError || authError) && (
          <div className="alert alert-danger" role="alert" aria-live="polite">
            <span className="alert-title">로그인 실패</span>
            <span>{formError || authError}</span>
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <fieldset style={{ border: "none", padding: 0, margin: 0 }}>
            <legend className="sr-only" style={{ display: "none" }}>계정 자격증명 입력</legend>

            <div className="form-group">
              <label htmlFor="username-input" className="form-label">
                아이디 (Username)
              </label>
              <input
                id="username-input"
                name="username"
                type="text"
                autoComplete="username"
                className="form-input"
                value={username}
                onChange={(e) => setUsername(e.target.value)}
                required
                disabled={isLoading}
              />
            </div>

            <div className="form-group">
              <label htmlFor="password-input" className="form-label">
                비밀번호 (Password)
              </label>
              <input
                id="password-input"
                name="password"
                type="password"
                autoComplete="current-password"
                className="form-input"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                required
                disabled={isLoading}
              />
            </div>

            <button
              type="submit"
              className="btn btn-primary btn-lg"
              style={{ width: "100%", marginTop: "0.5rem" }}
              disabled={isLoading}
              id="btn-login-submit"
            >
              {isLoading ? (
                <>
                  <span className="spinner" aria-hidden="true" />
                  <span>인증 확인 중 (/api/me)...</span>
                </>
              ) : (
                "로그인"
              )}
            </button>
          </fieldset>
        </form>

        <footer style={{ marginTop: "1.5rem", borderTop: "1px solid var(--color-border-light)", paddingTop: "0.75rem", fontSize: "0.75rem", color: "var(--color-text-dim)", textAlign: "center" }}>
          보안 안내: 자격증명은 브라우저 세션 메모리에서만 유지되며 영구 저장되지 않습니다.
        </footer>
      </div>
    </main>
  );
}
