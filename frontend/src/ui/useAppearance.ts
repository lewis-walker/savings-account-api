import { useEffect, useState } from 'react';

export type Appearance = 'light' | 'dark';

const CHOICE = 'savings.appearance';
const DARK = '(prefers-color-scheme: dark)';

/**
 * Which appearance to render in: what the operating system asks for, until someone says
 * otherwise on this device.
 *
 * <p>Every call guards {@code matchMedia}. jsdom does not implement it, so a component
 * that assumes it fails in tests rather than in a browser.
 */
export function useAppearance() {
  const [chosen, setChosen] = useState<Appearance | null>(() => read());
  const [system, setSystem] = useState<Appearance>(preferred);

  useEffect(() => {
    const media = window.matchMedia?.(DARK);
    if (!media) return;
    // The system preference can change while the page is open - some desktops switch it
    // on a schedule - so this follows rather than reading once at mount.
    const follow = (event: MediaQueryListEvent) => setSystem(event.matches ? 'dark' : 'light');
    media.addEventListener('change', follow);
    return () => media.removeEventListener('change', follow);
  }, []);

  const appearance = chosen ?? system;

  function toggle() {
    const next: Appearance = appearance === 'dark' ? 'light' : 'dark';
    setChosen(next);
    try {
      localStorage.setItem(CHOICE, next);
    } catch {
      // Private browsing refuses to store. The choice still holds for this page.
    }
  }

  return { appearance, toggle };
}

function preferred(): Appearance {
  return window.matchMedia?.(DARK).matches ? 'dark' : 'light';
}

function read(): Appearance | null {
  try {
    const stored = localStorage.getItem(CHOICE);
    return stored === 'light' || stored === 'dark' ? stored : null;
  } catch {
    return null;
  }
}
