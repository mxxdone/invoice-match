import type { Metadata } from 'next';
import './globals.css';
import './screens.css';

export const metadata: Metadata = {
  title: 'Invoice Match · 디자인 시안',
  description: 'Ramp 레퍼런스를 적용한 청구 상세 디자인 시안. 가상 데이터이며 실제 API와 연결되지 않았습니다.',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body>{children}</body></html>;
}
