'use client';

import type { ReactNode } from 'react';
import type { Credentials } from '../../api/transport';
import { useCaseDocuments } from './use-case-documents';

export function OriginalDocuments({ credentials, sessionId, caseId, onUnauthorized, children, visible = true }: {
  credentials: Credentials; sessionId: number; caseId: string; onUnauthorized: () => void; children?: ReactNode; visible?: boolean;
}) {
  const documents = useCaseDocuments({ credentials, sessionId, caseId, onUnauthorized });
  return <><div className="evidence-workspace" hidden={!visible}>
    <div className="evidence-records">
    <section className="history-content original-documents" aria-label="원본 문서">
    <div className="section-heading"><h2>원본 문서</h2><button className="button" onClick={documents.refresh}>목록 새로고침</button></div>
    <p className="muted-text">접수된 원본 파일입니다. PDF는 표준 뷰어에서 다운로드·인쇄할 수 있습니다.</p>
    {!documents.list ? <p role="status">문서를 불러오는 중입니다.</p>
      : documents.list.error ? <p role="alert">{documents.list.error}</p>
        : documents.list.items.length === 0 ? <p>접수된 원본 문서가 없습니다.</p>
          : <div className="table-scroll"><table className="history-table"><thead><tr><th>파일</th><th>형식</th><th>크기</th><th>원본 보기</th></tr></thead>
            <tbody>{documents.list.items.map((item) => <tr key={item.documentId}>
              <td className="original-file-name">{item.fileName}</td><td>{item.mediaType === 'application/pdf' ? 'PDF' : 'Excel'}</td><td>{(item.sizeBytes / 1024).toFixed(1)} KB</td>
              <td><div className="original-actions">{item.mediaType === 'application/pdf' && <button className="button" disabled={documents.pending} onClick={() => void documents.open(item.documentId, 'inline')}>PDF 미리보기</button>}
                <button className="button" disabled={documents.pending} onClick={() => void documents.open(item.documentId, 'attachment')}>다운로드 링크 받기</button></div></td>
            </tr>)}</tbody></table></div>}
    {documents.list?.hasNext && <button className="button" disabled={documents.loadingMore} onClick={() => void documents.more()}>{documents.loadingMore ? '불러오는 중' : '문서 더 보기'}</button>}
    {documents.pending && <p role="status">원본 접근 링크를 발급하고 있습니다.</p>}
    {documents.error && <p role="alert">{documents.error}</p>}
    {documents.download && <p><a className="button" href={documents.download.url} target="_blank" rel="noopener noreferrer">{documents.download.fileName} 다운로드</a></p>}
    <p className="muted-text">원본 링크는 2분 동안 유효합니다. 만료 후 미리보기 또는 다운로드 링크를 다시 발급하세요.</p>
    </section>
    {children}
    </div>
    </div>
    {documents.preview && <aside className="pdf-preview-drawer" aria-label="PDF 미리보기 영역">
      <div className="pdf-drawer-header">
        <div className="section-heading"><h3 className="text-ink">{documents.preview.fileName}</h3><button className="button" aria-label="PDF 미리보기 닫기" onClick={documents.closePreview}>닫기</button></div>
        <a className="button" href={documents.preview.url} target="_blank" rel="noopener noreferrer">PDF 새 탭에서 열기</a>
        <p className="muted-text">인쇄는 PDF 뷰어에서 할 수 있습니다.</p>
      </div>
      <div className="pdf-drawer-body flex-1 min-h-0 p-3">
      <iframe className="original-pdf-frame" title={`${documents.preview.fileName} 원본 PDF`} src={`${documents.preview.url}#view=Fit&zoom=page-fit`} referrerPolicy="no-referrer" />
      </div>
    </aside>}
  </>;
}
