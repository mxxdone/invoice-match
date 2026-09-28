"use client";

import React from "react";
import { useAuth } from "@/types/../lib/auth-context";

interface NavbarProps {
  currentView: "list" | "detail";
  onNavigateList: () => void;
  onOpenNewCaseModal: () => void;
  onLogout?: () => void;
}

export function Navbar({ currentView, onNavigateList, onOpenNewCaseModal, onLogout }: NavbarProps) {
  const { user, logout, hasRole } = useAuth();

  const getRoleLabel = (role: string) => {
    switch (role) {
      case "SUBMITTER": return "제출자 (SUBMITTER)";
      case "APPROVER": return "승인자 (APPROVER)";
      case "OPERATOR": return "운영자 (OPERATOR)";
      default: return role;
    }
  };

  const getRoleClass = (role: string) => {
    switch (role) {
      case "SUBMITTER": return "badge-role badge-role-submitter";
      case "APPROVER": return "badge-role badge-role-approver";
      case "OPERATOR": return "badge-role badge-role-operator";
      default: return "badge-role";
    }
  };

  return (
    <header className="navbar" role="banner">
      <div className="navbar-brand">
        <button
          type="button"
          onClick={onNavigateList}
          style={{ background: "none", border: "none", color: "inherit", font: "inherit", cursor: "pointer", display: "flex", alignItems: "center", gap: "0.5rem", padding: 0 }}
        >
          <span>Invoice Match</span>
        </button>
        <span className="navbar-tag">Phase 1 업무 화면</span>
      </div>

      <div className="navbar-user">
        <div style={{ display: "flex", alignItems: "center", gap: "0.5rem", flexWrap: "wrap" }}>
          <span>사용자: <strong>{user?.username}</strong></span>
          <div style={{ display: "flex", gap: "0.3rem" }}>
            {user?.roles.map((r) => (
              <span key={r} className={getRoleClass(r)}>
                {getRoleLabel(r)}
              </span>
            ))}
          </div>
        </div>

        <div style={{ display: "flex", gap: "0.5rem", alignItems: "center" }}>
          {currentView === "detail" && (
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={onNavigateList}
              aria-label="사건 목록으로 이동"
            >
              ← 목록으로
            </button>
          )}

          {hasRole("SUBMITTER") && (
            <button
              type="button"
              className="btn btn-primary btn-sm"
              onClick={onOpenNewCaseModal}
              id="btn-nav-new-case"
            >
              + 새 사건 작성
            </button>
          )}

          <button
            type="button"
            className="btn btn-secondary btn-sm"
            onClick={onLogout || logout}
            id="btn-logout"
            aria-label="로그아웃"
          >
            로그아웃
          </button>
        </div>
      </div>
    </header>
  );
}
