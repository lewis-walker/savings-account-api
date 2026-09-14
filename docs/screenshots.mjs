/**
 * Regenerates the screenshots in docs/images from the running stack.
 *
 *   docker compose up -d
 *   cd frontend && npm install --no-save playwright
 *   node docs/screenshots.mjs
 *
 * Playwright is installed on the spot rather than declared, because it is worth nothing
 * to anyone running the application: as a devDependency it would be downloaded on every
 * image build to serve four pictures.
 *
 * Taken at deviceScaleFactor 2 so the text survives being scaled down in a README, and
 * the dark shot emulates prefers-color-scheme rather than clicking the toggle, because
 * following the system is the behaviour worth showing.
 */
import { createRequire } from 'node:module';

// Resolved out of frontend/node_modules rather than imported, because Node resolves an
// import from the importing file's directory and playwright is not installed beside this
// one.
const { chromium } = createRequire(new URL('../frontend/package.json', import.meta.url))('playwright');

const APP = 'http://localhost:3000/';
const OPS = 'http://localhost:8082/';
const OUT = new URL('./images/', import.meta.url).pathname;

const browser = await chromium.launch();

async function open(width, height, colorScheme = 'light') {
  const context = await browser.newContext({
    viewport: { width, height }, deviceScaleFactor: 2, colorScheme,
  });
  return context.newPage();
}

async function signedIn(page) {
  await page.goto(APP);
  await page.getByRole('button', { name: 'Sign in' }).click();
  await page.getByText('Your savings accounts').waitFor();
  await page.waitForTimeout(400);
  return page;
}

// The account list, in each appearance.
await (await signedIn(await open(1000, 620))).screenshot({ path: `${OUT}accounts.png` });
await (await signedIn(await open(1000, 620, 'dark'))).screenshot({ path: `${OUT}dark.png` });

// A refusal, so the problem document and its reference are visible. The same reference
// appears in the ops console shot below, which is the point of taking both.
{
  const page = await signedIn(await open(1000, 700));
  await page.getByPlaceholder('Holiday fund').fill('my badword account');
  await page.getByRole('button', { name: 'Open account' }).click();
  await page.getByText('Nickname not acceptable').waitFor();
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}refusal.png` });
}

{
  const page = await open(1200, 860);
  await page.goto(OPS);
  await page.waitForTimeout(1500);
  await page.screenshot({ path: `${OUT}ops-console.png` });
}

await browser.close();
