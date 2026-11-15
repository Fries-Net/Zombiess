import {chromium} from 'playwright';
import assert from 'node:assert/strict';
import {spawn} from 'node:child_process';
import {mkdir} from 'node:fs/promises';
import {fileURLToPath} from 'node:url';

const root = fileURLToPath(new URL('../../', import.meta.url));
const server = spawn(process.env.PYTHON || 'python', ['tools/model_preview.py', '--port', '0'], {cwd: root});
let browser;
try {
  const url = await new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error('Preview startup timed out')), 30000);
    let output = '';
    server.stdout.on('data', data => {
      output += data;
      const match = output.match(/http:\/\/127\.0\.0\.1:\d+/);
      if (match) {clearTimeout(timeout); resolve(match[0]);}
    });
    server.stderr.on('data', data => process.stderr.write(data));
    server.on('exit', code => {clearTimeout(timeout); reject(new Error('Preview exited: ' + code));});
  });
  browser = await chromium.launch({headless: true,
    ...(process.env.PREVIEW_CHROME ? {executablePath: process.env.PREVIEW_CHROME} : {}),
    args: ['--enable-unsafe-swiftshader']});
  const page = await browser.newPage({viewport: {width: 1440, height: 1100}});
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  page.on('response', response => {if (response.status() >= 400) errors.push(response.status() + ' ' + response.url());});
  await page.goto(url);
  await page.waitForFunction(() => document.querySelector('#asset').options.length === 71);
  await mkdir(root + 'resource-pack/build/review', {recursive: true});
  const contexts = ['neutral', 'gui', 'firstperson_righthand', 'firstperson_lefthand',
    'thirdperson_righthand', 'thirdperson_lefthand', 'ground', 'fixed'];
  for (const weapon of ['ak47', 'glock18', 'awp', 'mac10', 'raygun']) {
    await page.selectOption('#asset', 'friesnet:weapon/' + weapon);
    for (const context of contexts) {
      await page.selectOption('#context', context);
      await page.waitForTimeout(150);
      assert.equal(await page.locator('#crosshair').isVisible(), context.startsWith('firstperson'));
      assert.equal(await page.locator('input').count(), context === 'neutral' ? 0 : 9);
      if (['neutral', 'firstperson_righthand', 'thirdperson_righthand'].includes(context))
        await page.screenshot({path: root + `resource-pack/build/review/${weapon}-${context}.png`});
    }
  }
  assert.deepEqual(errors, []);
  const denied = await page.request.post(url + '/pose', {data: {}});
  assert.equal(denied.status(), 403);
  const data = await (await page.request.get(url + '/data.json')).json();
  const invalid = await page.request.post(url + '/pose', {headers: {'X-Preview-Token': data.token},
    data: {asset: 'friesnet:weapon/ak47', context: 'gui', pose: {rotation: [0, 0, 0], translation: [0, 0, 0], scale: [5, 1, 1]}}});
  assert.equal(invalid.status(), 400);
  console.log('40 source/export contexts rendered; no page/asset errors; invalid pose writes rejected. Minecraft acceptance remains pending.');
} finally {
  await browser?.close();
  server.kill();
}
