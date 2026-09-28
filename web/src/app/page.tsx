"use client";

import React, { useState } from "react";
import { AuthProvider, useAuth } from "@/lib/auth-context";
import { Navbar } from "@/components/Navbar";
import { LoginView } from "@/components/LoginView";
import { CaseListView } from "@/components/CaseListView";
import { CaseDetailView } from "@/components/CaseDetailView";
import { NewCaseModal } from "@/components/NewCaseModal";
import { InvoiceCaseDetail } from "@/types/api";

function AppContent() {
  const { isAuthenticated, logout } = useAuth();
  const [selectedCaseId, setSelectedCaseId] = useState<string | null>(null);
  const [isNewCaseModalOpen, setIsNewCaseModalOpen] = useState(false);

  const handleLogout = () => {
    setSelectedCaseId(null);
    logout();
  };

  if (!isAuthenticated) {
    return <LoginView />;
  }

  const handleSelectCase = (caseId: string) => {
    setSelectedCaseId(caseId);
  };

  const handleNavigateList = () => {
    setSelectedCaseId(null);
  };

  const handleCaseCreated = (newCase: InvoiceCaseDetail) => {
    setSelectedCaseId(newCase.id);
  };

  return (
    <div className="app-container">
      <Navbar
        currentView={selectedCaseId ? "detail" : "list"}
        onNavigateList={handleNavigateList}
        onOpenNewCaseModal={() => setIsNewCaseModalOpen(true)}
        onLogout={handleLogout}
      />

      {selectedCaseId ? (
        <CaseDetailView
          caseId={selectedCaseId}
          onBack={handleNavigateList}
        />
      ) : (
        <CaseListView
          onSelectCase={handleSelectCase}
          onOpenNewCaseModal={() => setIsNewCaseModalOpen(true)}
        />
      )}

      <NewCaseModal
        isOpen={isNewCaseModalOpen}
        onClose={() => setIsNewCaseModalOpen(false)}
        onSuccess={handleCaseCreated}
      />
    </div>
  );
}

export default function Home() {
  return (
    <AuthProvider>
      <AppContent />
    </AuthProvider>
  );
}
