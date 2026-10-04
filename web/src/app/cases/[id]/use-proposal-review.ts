'use client';
import { useEffect, useRef, useState } from 'react';
import { fetchProposals } from '../../api/client.ts';
import { ApiRequestError, type Credentials } from '../../api/transport.ts';
import { Generation } from '../../api/generation.ts';
import type { ProposalPage } from '../../api/contract.ts';
import type { SectionState } from './use-case-detail.ts';

export type ProposalLoad = SectionState<ProposalPage> | { status: 'loading' };
export function useProposalReview({ credentials, sessionId, caseId, enabled, reloadToken, onUnauthorized }: {
  credentials: Credentials | null; sessionId: number; caseId: string; enabled: boolean; reloadToken: number; onUnauthorized: () => void;
}): ProposalLoad {
  const identity = `${sessionId}#${caseId}#${reloadToken}#${enabled && credentials !== null}`;
  const [state, setState] = useState<{ identity: string; load: ProposalLoad } | null>(null);
  const generation = useRef(new Generation());
  const [marker, setMarker] = useState(identity);
  const identityChanged = marker !== identity;
  if (identityChanged) { setMarker(identity); setState(null); }
  useEffect(() => {
    const guard = generation.current; const token = guard.next(); const controller = new AbortController();
    if (!credentials || !enabled) return () => { guard.next(); controller.abort(); };
    const current = () => guard.isCurrent(token) && !controller.signal.aborted;
    fetchProposals(credentials, caseId, controller.signal).then(data => {
      if (current()) setState({ identity, load: { status: 'ready', data } });
    }).catch(error => {
      if (!current() || error?.name === 'AbortError') return;
      if (error instanceof ApiRequestError && error.status === 401) { onUnauthorized(); return; }
      setState({ identity, load: error instanceof ApiRequestError && error.status === 403 ? { status: 'forbidden' }
        : { status: 'error', message: error instanceof Error ? error.message : '분석 자료를 불러오지 못했습니다.' } });
    });
    return () => { guard.next(); controller.abort(); };
  }, [credentials, enabled, identity, caseId, onUnauthorized]);
  if (!enabled || !credentials) return { status: 'forbidden' };
  return !identityChanged && state?.identity === identity ? state.load : { status: 'loading' };
}
