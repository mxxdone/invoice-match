import { redirect } from 'next/navigation';

// The root is an entry redirect, not a second design surface: the live work
// list is the only place a case is opened from, so a stale preview cannot be
// mistaken for real data.
export default function Home() {
  redirect('/cases');
}
