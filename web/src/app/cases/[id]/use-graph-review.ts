'use client';

// Graph read hooks. They reuse the same Generation/Abort discipline as
// useProposalReview: a late response or a late 401 from a previous
// session/case/run can never replace or sign out the current identity.

import { useEffect, useRef, useState } from 'react';
import { fetchGraph, fetchGraphs } from '../../api/client.ts';
import { ApiRequestError, type Credentials } from '../../api/transport.ts';
import { Generation } from '../../api/generation.ts';
import type { GraphPage, GraphView } from '../../api/contract.ts';
import type { SectionState } from './use-case-detail.ts';

export type GraphLoad = SectionState<GraphPage> | { status: 'loading' };
export type GraphRunLoad = SectionState<GraphView> | { status: 'loading' } | { status: 'idle' };

// The exact server identity of a displayed run: a new run, interrupt, review
// version or saved review is a new human-review target.
export function graphServerIdentity(view: GraphView): string {
  return `${view.run.id}#${view.pending?.interruptId ?? ''}#${view.pending?.reviewVersion ?? ''}#${view.review?.id ?? ''}`;
}

// The identity the action hook invalidates graph intents on. A read that is
// merely loading (view null) must keep the last server identity so an uncertain
// confirm survives a refresh back to the same wait; only a real server target
// change or an explicit history selection change replaces it.
export function useGraphActionIdentity(selection: string | null, view: GraphView | null): string {
  const serverNow = view ? graphServerIdentity(view) : null;
  const [epoch, setEpoch] = useState<{ selection: string | null; server: string }>({ selection, server: serverNow ?? '' });
  if (epoch.selection !== selection) {
    setEpoch({ selection, server: serverNow ?? '' });
  } else if (serverNow !== null && serverNow !== epoch.server) {
    setEpoch({ selection, server: serverNow });
  }
  const server = serverNow ?? (epoch.selection === selection ? epoch.server : '');
  return `${selection ?? 'latest'}#${server}`;
}

export function useGraphReview({ credentials, sessionId, caseId, enabled, reloadToken, onUnauthorized }: {
  credentials: Credentials | null; sessionId: number; caseId: string; enabled: boolean; reloadToken: number; onUnauthorized: () => void;
}): GraphLoad {
  const identity = `${sessionId}#${caseId}#${reloadToken}#${enabled && credentials !== null}`;
  const [state, setState] = useState<{ identity: string; load: GraphLoad } | null>(null);
  const generation = useRef(new Generation());
  const [marker, setMarker] = useState(identity);
  const identityChanged = marker !== identity;
  if (identityChanged) { setMarker(identity); setState(null); }
  useEffect(() => {
    const guard = generation.current; const token = guard.next(); const controller = new AbortController();
    if (!credentials || !enabled) return () => { guard.next(); controller.abort(); };
    const current = () => guard.isCurrent(token) && !controller.signal.aborted;
    fetchGraphs(credentials, caseId, controller.signal).then(data => {
      if (current()) setState({ identity, load: { status: 'ready', data } });
    }).catch(error => {
      if (!current() || error?.name === 'AbortError') return;
      if (error instanceof ApiRequestError && error.status === 401) { onUnauthorized(); return; }
      setState({ identity, load: error instanceof ApiRequestError && error.status === 403 ? { status: 'forbidden' }
        : { status: 'error', message: error instanceof Error ? error.message : '그래프 분석 자료를 불러오지 못했습니다.' } });
    });
    return () => { guard.next(); controller.abort(); };
  }, [credentials, enabled, identity, caseId, onUnauthorized]);
  if (!enabled || !credentials) return { status: 'forbidden' };
  return !identityChanged && state?.identity === identity ? state.load : { status: 'loading' };
}

// Reads one graph run for the history list. The identity includes the selected
// run id, so a late response or 401 for an abandoned selection is discarded.
export function useGraphRun({ credentials, sessionId, caseId, graphId, enabled, reloadToken, onUnauthorized }: {
  credentials: Credentials | null; sessionId: number; caseId: string; graphId: string | null; enabled: boolean; reloadToken: number; onUnauthorized: () => void;
}): GraphRunLoad {
  const identity = `${sessionId}#${caseId}#${graphId ?? ''}#${reloadToken}#${enabled && credentials !== null}`;
  const [state, setState] = useState<{ identity: string; load: GraphRunLoad } | null>(null);
  const generation = useRef(new Generation());
  const [marker, setMarker] = useState(identity);
  const identityChanged = marker !== identity;
  if (identityChanged) { setMarker(identity); setState(null); }
  useEffect(() => {
    const guard = generation.current; const token = guard.next(); const controller = new AbortController();
    if (!credentials || !enabled || !graphId) return () => { guard.next(); controller.abort(); };
    const current = () => guard.isCurrent(token) && !controller.signal.aborted;
    fetchGraph(credentials, caseId, graphId, controller.signal).then(data => {
      if (current()) setState({ identity, load: { status: 'ready', data } });
    }).catch(error => {
      if (!current() || error?.name === 'AbortError') return;
      if (error instanceof ApiRequestError && error.status === 401) { onUnauthorized(); return; }
      setState({ identity, load: error instanceof ApiRequestError && error.status === 403 ? { status: 'forbidden' }
        : { status: 'error', message: error instanceof Error ? error.message : '그래프 분석을 불러오지 못했습니다.' } });
    });
    return () => { guard.next(); controller.abort(); };
  }, [credentials, enabled, identity, caseId, graphId, onUnauthorized]);
  if (!graphId) return { status: 'idle' };
  if (!enabled || !credentials) return { status: 'forbidden' };
  return !identityChanged && state?.identity === identity ? state.load : { status: 'loading' };
}
