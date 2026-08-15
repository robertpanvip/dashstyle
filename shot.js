const { chromium } = require('/tmp/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 900 }, deviceScaleFactor: 2 });
  await page.goto('http://localhost:8090/preview-demo.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(500);
  await page.screenshot({ path: '/workspace/preview-demo.png', fullPage: true });
  console.log('saved /workspace/preview-demo.png');
  await browser.close();
})();