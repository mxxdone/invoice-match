import { useLayoutEffect, useRef, useState } from 'react';
import { retryAnalysis, type AnalysisJob, type AnalysisRetryIntent } from '../../api/client.ts';
import { ApiRequestError, type Credentials } from '../../api/transport.ts';

type State = { session: number; intent: AnalysisRetryIntent | null; pending: boolean; uncertain: boolean; message: string };
export function useAnalysisRetry(credentials: Credentials | null, sessionId: number, enabled: boolean,
  onConfirmed: () => void, onUnauthorized: () => void) {
  const [state, setState] = useState<State>({ session: sessionId, intent: null, pending: false, uncertain: false, message: '' });
  const current = useRef({ sessionId, credentials, enabled });
  const request = useRef<AbortController | null>(null);
  const admitted = useRef(false);
  useLayoutEffect(() => { current.current = { sessionId, credentials, enabled }; return () => { request.current?.abort(); admitted.current = false; }; }, [sessionId, credentials, enabled]);
  const shown = state.session === sessionId ? state : { session: sessionId, intent: null, pending: false, uncertain: false, message: '' };
  async function execute(job: AnalysisJob, reason: string) {
    if (!credentials || !enabled || admitted.current || shown.pending) return;
    const intent = shown.intent ?? { runId: job.runId, requestId: crypto.randomUUID(), expectedExecutionAttempt: job.executionAttempt, reason };
    if (!shown.intent && (job.status !== 'DEAD_LETTERED' || !reason.trim() || reason.length > 300)) return;
    admitted.current = true;
    const controller = new AbortController();request.current = controller;
    setState({ session: sessionId, intent, pending: true, uncertain: false, message: '' });
    const active = () => !controller.signal.aborted && current.current.sessionId === sessionId && current.current.credentials === credentials && current.current.enabled;
    try {
      await retryAnalysis(credentials, intent, controller.signal);
      if (!active()) return;
      setState({ session: sessionId, intent: null, pending: false, uncertain: false, message: '재처리를 예약했습니다.' });
      onConfirmed();
    } catch (error) {
      if (!active()) return;
      if (error instanceof ApiRequestError && error.status === 401) { onUnauthorized(); return; }
      const uncertain = !(error instanceof ApiRequestError) || error.status === 0 || error.status >= 500;
      setState({ session: sessionId, intent: uncertain ? intent : null, pending: false, uncertain,
        message: uncertain ? '처리 결과를 확인하지 못했습니다. 같은 요청으로 확인하세요.' : error.message });
    } finally {
      if (request.current === controller) { admitted.current = false; request.current = null; }
    }
  }
  return { ...shown, execute };
}
