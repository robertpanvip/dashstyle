const { chromium } = require('/tmp/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 });
  await page.goto('http://localhost:8090/popup-demo.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);
  await page.screenshot({ path: '/workspace/popup-demo-flex.png' });
  await page.click('#swGrid');
  await page.waitForTimeout(300);
  await page.screenshot({ path: '/workspace/popup-demo-grid.png' });
  console.log('re-saved');
  await browser.close();
})();