import type { SVGProps } from 'react';

type P = SVGProps<SVGSVGElement> & { size?: number };

function base({ size = 18, ...rest }: P, children: React.ReactNode) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" {...rest}>
      {children}
    </svg>
  );
}

export const IconCopy = (p: P) => base(p, <><rect x="9" y="9" width="11" height="11" rx="2.5" /><path d="M5 15V6.5A2.5 2.5 0 0 1 7.5 4H15" /></>);
export const IconCheck = (p: P) => base(p, <path d="M5 12.5l4.2 4.2L19 7" />);
export const IconPin = (p: P) => base(p, <><path d="M9 4h6l-1 5 3 3v2H7v-2l3-3-1-5z" /><path d="M12 14v6" /></>);
export const IconTrash = (p: P) => base(p, <><path d="M4 7h16" /><path d="M9 7V4.5h6V7" /><path d="M6.5 7l1 12.5h9l1-12.5" /></>);
export const IconShare = (p: P) => base(p, <><path d="M12 15V4" /><path d="M8 8l4-4 4 4" /><path d="M5 13v5.5A1.5 1.5 0 0 0 6.5 20h11a1.5 1.5 0 0 0 1.5-1.5V13" /></>);
export const IconSettings = (p: P) => base(p, <><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.8l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.8-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 0 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.8.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.8 1.7 1.7 0 0 0-1.5-1H3a2 2 0 0 1 0-4h.1a1.7 1.7 0 0 0 1.5-1.1 1.7 1.7 0 0 0-.3-1.8l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.8.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 0 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.8-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.8V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 0 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z" /></>);
export const IconBack = (p: P) => base(p, <path d="M15 5l-7 7 7 7" />);
export const IconSearch = (p: P) => base(p, <><circle cx="11" cy="11" r="6.5" /><path d="M20 20l-4.2-4.2" /></>);
export const IconRefresh = (p: P) => base(p, <><path d="M20 11a8 8 0 1 0-2.3 5.7" /><path d="M20 5v6h-6" /></>);
export const IconSend = (p: P) => base(p, <><path d="M5 12h13" /><path d="M13 6l6 6-6 6" /></>);
export const IconDownload = (p: P) => base(p, <><path d="M12 4v11" /><path d="M7 10l5 5 5-5" /><path d="M5 20h14" /></>);
export const IconX = (p: P) => base(p, <><path d="M6 6l12 12" /><path d="M18 6L6 18" /></>);
export const IconImage = (p: P) => base(p, <><rect x="3.5" y="4.5" width="17" height="15" rx="2.5" /><circle cx="9" cy="10" r="1.6" /><path d="M20 16l-4.5-4.5L7 20" /></>);
export const IconPhone = (p: P) => base(p, <><rect x="7" y="3" width="10" height="18" rx="2.5" /><path d="M11 18h2" /></>);
export const IconLaptop = (p: P) => base(p, <><rect x="5" y="5" width="14" height="10" rx="1.5" /><path d="M3 19h18" /></>);
export const IconBolt = (p: P) => base(p, <path d="M13 3L5 13h6l-1 8 8-10h-6l1-8z" />);

export function Logo({ size = 22 }: { size?: number }) {
  // Clipverse mark: a solid page in front of an outlined one (same artwork as the app icon).
  return (
    <svg width={size} height={size} viewBox="0 0 32 32" aria-hidden="true">
      <rect width="32" height="32" rx="7.5" fill="var(--accent)" />
      <path d="M19.04,10.72 L19.04,8.96 A1.76,1.76 0 0,0 17.28,7.2 L9.92,7.2 A1.76,1.76 0 0,0 8.16,8.96 L8.16,18.24 A1.76,1.76 0 0,0 9.92,20.0 L11.68,20.0" stroke="#fff" strokeWidth="1.54" fill="none" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M14.72,12.0 L22.08,12.0 A1.76,1.76 0 0,1 23.84,13.76 L23.84,23.04 A1.76,1.76 0 0,1 22.08,24.8 L14.72,24.8 A1.76,1.76 0 0,1 12.96,23.04 L12.96,13.76 A1.76,1.76 0 0,1 14.72,12.0 Z" fill="#fff" />
    </svg>
  );
}
