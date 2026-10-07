'use client';

import type { ReactNode } from 'react';
import { Button } from '@/components/ui/button';
import { Table } from '@/components/ui/table';
import type { Credentials } from '../../api/transport';
import { useCaseDocuments } from './use-case-documents';

const TH = 'border-b border-border p-3 text-left text-label font-normal text-muted-foreground';
const TD = 'border-b border-border px-3 py-5';

export function OriginalDocuments({ credentials, sessionId, caseId, onUnauthorized, children, visible = true }: {
  credentials: Credentials; sessionId: number; caseId: string; onUnauthorized: () => void; children?: ReactNode; visible?: boolean;
}) {
  const documents = useCaseDocuments({ credentials, sessionId, caseId, onUnauthorized });
  return <><div className="evidence-workspace px-11 py-6 max-[1200px]:px-7.5 max-[760px]:px-5" hidden={!visible}>
    <div className="evidence-records min-w-0 [&>.history-content]:px-0">
    <section className="history-content original-documents min-w-0 mb-6" aria-label="원본 문서">
    <div className="section-heading mb-6 flex flex-wrap items-center justify-between gap-3"><h2 className="text-lg font-medium">원본 문서</h2><Button variant="outline" className="button" onClick={documents.refresh}>목록 새로고침</Button></div>
    <p className="muted-text text-label text-muted-foreground">접수된 원본 파일입니다. PDF는 표준 뷰어에서 다운로드·인쇄할 수 있습니다.</p>
    {!documents.list ? <p role="status">문서를 불러오는 중입니다.</p>
      : documents.list.error ? <p role="alert">{documents.list.error}</p>
        : documents.list.items.length === 0 ? <p>접수된 원본 문서가 없습니다.</p>
          : <Table className="history-table table-scroll"><thead><tr><th className={TH}>파일</th><th className={TH}>형식</th><th className={TH}>크기</th><th className={TH}>원본 보기</th></tr></thead>
            <tbody>{documents.list.items.map((item) => <tr key={item.documentId}>
              <td className={`${TD} original-file-name max-w-md break-words`}>{item.fileName}</td><td className={TD}>{item.mediaType === 'application/pdf' ? 'PDF' : 'Excel'}</td><td className={TD}>{(item.sizeBytes / 1024).toFixed(1)} KB</td>
              <td className={TD}><div className="original-actions flex flex-wrap gap-2">{item.mediaType === 'application/pdf' && <Button variant="outline" className="button" disabled={documents.pending} onClick={() => void documents.open(item.documentId, 'inline')}>PDF 미리보기</Button>}
                <Button variant="outline" className="button" disabled={documents.pending} onClick={() => void documents.open(item.documentId, 'attachment')}>다운로드 링크 받기</Button></div></td>
            </tr>)}</tbody></Table>}
    {documents.list?.hasNext && <Button variant="outline" className="button" disabled={documents.loadingMore} onClick={() => void documents.more()}>{documents.loadingMore ? '불러오는 중' : '문서 더 보기'}</Button>}
    {documents.pending && <p role="status">원본 접근 링크를 발급하고 있습니다.</p>}
    {documents.error && <p role="alert">{documents.error}</p>}
    {documents.download && <p><Button asChild variant="outline" className="button"><a href={documents.download.url} target="_blank" rel="noopener noreferrer">{documents.download.fileName} 다운로드</a></Button></p>}
    <p className="muted-text text-label text-muted-foreground">원본 링크는 2분 동안 유효합니다. 만료 후 미리보기 또는 다운로드 링크를 다시 발급하세요.</p>
    </section>
    {children}
    </div>
    </div>
    {documents.preview && <aside className="pdf-preview-drawer fixed inset-y-0 right-0 z-10 flex h-[100dvh] min-h-0 w-[var(--pdf-drawer-width,clamp(420px,34vw,760px))] flex-col border-l border-border bg-background shadow-drawer max-[1000px]:w-[min(100vw,640px)] motion-reduce:animate-none" aria-label="PDF 미리보기 영역">
      <div className="pdf-drawer-header shrink-0 border-b border-border p-5">
        <div className="section-heading flex flex-wrap items-center justify-between gap-3">
          <Button asChild variant="outline" className="button"><a href={documents.preview.url} target="_blank" rel="noopener noreferrer">PDF 새 탭에서 열기</a></Button>
          <Button variant="outline" className="button" aria-label="PDF 미리보기 닫기" onClick={documents.closePreview}>닫기</Button>
        </div>
        <p className="muted-text mt-3 text-label text-muted-foreground">인쇄는 PDF 뷰어에서 할 수 있습니다.</p>
      </div>
      <div className="pdf-drawer-body flex-1 min-h-0 p-3">
      <iframe className="original-pdf-frame block h-full min-h-0 w-full rounded-md border border-border bg-white" title={`${documents.preview.fileName} 원본 PDF`} src={`${documents.preview.url}#view=Fit&zoom=page-fit`} referrerPolicy="no-referrer" />
      </div>
    </aside>}
  </>;
}
