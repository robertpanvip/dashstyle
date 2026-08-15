const { chromium } = require('/tmp/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 });
  await page.goto('http://localhost:8090/popup-demo.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);

  // 初始状态（justify=center, align=center）
  await page.click('#swFlex');
  await page.waitForTimeout(200);
  await page.screenshot({ path: '/workspace/drag-0-initial.png' });

  // 模拟在画布上向左拖动（改 justify → flex-start）
  const box = await page.locator('#canvas .cell').first().boundingBox();
  const c = await page.locator('#canvas').boundingBox();
  const cx = c.x + c.width, cy = c.y + c.height/2;
  const startX = box.x + box.width/2, startY = box.y + box.height/2;
  await page.mouse.move(startX, startY);
  await page.mouse.down();
  await page.mouse.move(c.x + 10, startY, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(250);
  await page.screenshot({ path: '/workspace/drag-1-flexstart.png' });

  // 模拟向上拖动（改 align → flex-start）
  const box2 = await page.locator('#canvas .cell').first().boundingBox();
  await page.mouse.move(box2.x + box2.width/2, box2.y + box2.height/2);
  await page.mouse.down();
  await page.mouse.move(box2.x + box2.width/2, c.y + 6, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(250);
  await page.screenshot({ path: '/workspace/drag-2-alignflexstart.png' });

  console.log('saved drag screenshots');
  await browser.close();
})();