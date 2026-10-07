import type { Metadata } from 'next';
import { AuthProvider } from './auth';
import './tailwind.css';

export const metadata: Metadata = {
  title: 'Invoice Match',
  description: '청구·발주·검수를 비교하고 검토 결정과 ERP 인계 상태를 확인하는 업무 화면.',
};

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return <html lang="ko"><body className="bg-background font-sans text-sm text-foreground antialiased break-keep text-pretty"><AuthProvider>{children}</AuthProvider></body></html>;
}
