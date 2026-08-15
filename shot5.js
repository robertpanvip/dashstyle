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

  const read = () => page.evaluate(() => ({ justify: state.flex.justify, align: state.flex.align }));
  console.log('initial:', JSON.stringify(await read()));

  // 只做向上拖动（不先左右拖），避免轴缓存干扰
  const c = await page.locator('#canvas').boundingBox();
  const box = await page.locator('#canvas .cell').first().boundingBox();
  console.log('cell box:', JSON.stringify(box), 'canvas:', JSON.stringify(c));
  await page.mouse.move(box.x + box.width/2, box.y + box.height/2);
  await page.mouse.down();
  await page.mouse.move(box.x + box.width/2, c.y + 6, { steps: 15 });
  await page.waitForTimeout(100);
  await page.mouse.up();
  await page.waitForTimeout(300);
  console.log('after up-drag:', JSON.stringify(await read()));

  await browser.close();
})();