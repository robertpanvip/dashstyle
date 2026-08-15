const { chromium } = require('/tmp/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 2 });
  await page.goto('http://localhost:8090/popup-demo.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(400);

  // 切到 grid
  await page.click('#swGrid');
  await page.waitForTimeout(200);
  const read = () => page.evaluate(() => ({ justifyContent: state.grid.justifyContent, alignContent: state.grid.alignContent }));

  const c = await page.locator('#canvas').boundingBox();
  const box = await page.locator('#canvas .cell').first().boundingBox();
  console.log('grid initial:', JSON.stringify(await read()));

  // 向右拖动（改 justify-content → end）
  await page.mouse.move(box.x + box.width/2, box.y + box.height/2);
  await page.mouse.down();
  await page.mouse.move(c.x + c.width - 8, box.y + box.height/2, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(250);
  console.log('after right-drag:', JSON.stringify(await read()));
  await page.screenshot({ path: '/workspace/grid-drag-1.png' });

  // 向下拖动（改 align-content → end）
  const box2 = await page.locator('#canvas .cell').first().boundingBox();
  await page.mouse.move(box2.x + box2.width/2, box2.y + box2.height/2);
  await page.mouse.down();
  await page.mouse.move(box2.x + box2.width/2, c.y + c.height - 8, { steps: 12 });
  await page.mouse.up();
  await page.waitForTimeout(250);
  console.log('after down-drag:', JSON.stringify(await read()));
  await page.screenshot({ path: '/workspace/grid-drag-2.png' });

  await browser.close();
})();