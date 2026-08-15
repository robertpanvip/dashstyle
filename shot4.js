const { chromium } = require('/tmp/node_modules/playwright-core');

(async () => {
  const browser = await chromium.launch({
    executablePath: '/root/.cache/ms-playwright/chromium-1234/chrome-linux64/chrome',
    args: ['--no-sandbox', '--disable-gpu']
  });
  const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
  await page.goto('http://localhost:8090/popup-demo.html', { waitUntil: 'networkidle' });
  await page.waitForTimeout(300);
  await page.click('#swFlex');
  await page.waitForTimeout(200);

  const read = () => page.evaluate(() => {
    const s = JSON.parse(JSON.stringify(state.flex));
    return { justify: s.justify, align: s.align };
  });

  console.log('initial:', JSON.stringify(await read()));

  const c = await page.locator('#canvas').boundingBox();
  const box = await page.locator('#canvas .cell').first().boundingBox();
  // 向左拖动
  await page.mouse.move(box.x + box.width/2, box.y + box.height/2);
  await page.mouse.down();
  await page.mouse.move(c.x + 8, box.y + box.height/2, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(200);
  console.log('after left-drag:', JSON.stringify(await read()));

  // 向上拖动
  const box2 = await page.locator('#canvas .cell').first().boundingBox();
  await page.mouse.move(box2.x + box2.width/2, box2.y + box2.height/2);
  await page.mouse.down();
  await page.mouse.move(box2.x + box2.width/2, c.y + 6, { steps: 10 });
  await page.mouse.up();
  await page.waitForTimeout(200);
  console.log('after up-drag:', JSON.stringify(await read()));

  await browser.close();
})();