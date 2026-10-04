const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const designDir = path.resolve(__dirname, '../../design');
const prototypePath = path.join(designDir, 'food-entry-prototype.html');
const evidencePath = path.join(designDir, 'food-browser-verification.json');
const evidence = {
  checked_at: new Date().toISOString(),
  prototype_sha256: null,
  scope: 'Offline food-entry HTML concept in Chromium. Synthetic food fixtures and local image preview only; no image recognition, external nutrition lookup, Android rendering, insulin calculation, carb delivery, pump behavior, or persistent treatment recording tested.',
  checks: [], passed: 0, javascript_errors: [], external_requests: [], screenshots: [],
};
let browser;
let prototype;
function check(condition, message) {
  if (!condition) throw new Error(message);
  evidence.checks.push(message);
}
function number(text) {
  const match = String(text).trim().match(/[-+]?\d+(?:[.,]\d+)?(?:e[-+]?\d+)?/i);
  return match ? Number(match[0].replace(',', '.')) : NaN;
}
function roundCarbs(value) { return Math.round((value + Number.EPSILON) * 100) / 100; }
const items = page => page.locator('#food-items > [data-item-id]');
const field = (row, name) => row.locator(`[data-field="${name}"]`);
async function value(locator) {
  return locator.evaluate(element => 'value' in element ? element.value : element.textContent);
}
async function nutrition(row) {
  const custom = field(row, 'carbs100');
  return number(await value(await custom.count() ? custom : row.locator('[data-nutrition-value]')));
}
async function setRaw(locator, text) {
  await locator.evaluate((element, text) => {
    element.value = text;
    element.dispatchEvent(new Event('input', { bubbles: true }));
    element.dispatchEvent(new Event('change', { bubbles: true }));
  }, text);
}
async function reset(page) {
  // A new document clears top-level const bindings as well as DOM and draft state.
  await page.goto('about:blank');
  await page.setContent(prototype, { waitUntil: 'load' });
}
async function parse(page, text) {
  await page.locator('#mode-description').click();
  await page.locator('#food-description').fill(text);
  await page.locator('#parse-description').click();
}
async function chooseState(row, state) {
  const control = field(row, 'state');
  const options = await control.evaluate(select => [...select.options].map(option => ({ value: option.value, text: option.textContent, disabled: option.disabled })));
  const selected = options.find(option => !option.disabled && option.value === state);
  check(!!selected, `row supports explicit ${state} preparation state`);
  await control.selectOption(selected.value);
}
async function resolveStates(page, state = 'cooked') {
  for (const row of await items(page).all()) {
    const control = field(row, 'state');
    if (await control.count() && !(await value(control))) await chooseState(row, state);
  }
}
async function expectedCarbs(page) {
  let sum = 0;
  for (const row of await items(page).all()) {
    const mass = number(await value(field(row, 'mass')));
    const carbs100 = await nutrition(row);
    check(Number.isFinite(mass) && mass > 0 && Number.isFinite(carbs100) && carbs100 >= 0, 'resolved draft rows have finite positive mass and known nonnegative nutrition');
    sum += mass * carbs100 / 100;
  }
  return roundCarbs(sum);
}
async function assertTotal(page, message) {
  const expected = await expectedCarbs(page);
  const actual = number(await page.locator('#draft-carbs').innerText());
  check(Number.isFinite(actual) && Math.abs(actual - expected) < 0.00001, `${message}: weighted sum displayed to 0.01 g (expected ${expected}, got ${actual})`);
  return expected;
}
async function blocked(page, message) {
  check(await page.locator('#review-meal').isDisabled(), message);
  check(!!(await page.locator('#draft-status').innerText()).trim(), `${message}: draft status provides feedback`);
}
async function savedCount(page) {
  return page.locator('#saved-meals [data-saved-meal]').count();
}
async function screenshot(page, filename) {
  await page.evaluate(() => { document.activeElement?.blur(); });
  await page.locator('.app-body').evaluate(element => { element.scrollTop = 0; });
  const target = path.join(designDir, filename);
  if (filename === 'food-entry-mobile.png') await page.locator('.device').screenshot({ path: target });
  else await page.screenshot({ path: target, fullPage: true });
  evidence.screenshots.push(filename);
}
async function modalVisible(page) { return page.locator('#meal-modal').isVisible(); }
async function assertTrap(page) {
  const visibleIds = await page.locator('#meal-modal').evaluate(modal => [...modal.querySelectorAll('button,input,select,textarea,a[href],[tabindex]:not([tabindex="-1"])')]
    .filter(element => !element.disabled && element.type !== 'hidden' && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden')
    .map(element => element.id));
  check(visibleIds.length >= 2 && visibleIds.every(Boolean), 'review dialog visible focus controls have stable IDs');
  const first = page.locator(`#${visibleIds[0]}`), last = page.locator(`#${visibleIds.at(-1)}`);
  await last.focus();
  await page.keyboard.press('Tab');
  check(await first.evaluate(element => element === document.activeElement), 'review dialog traps Tab at its last visible control');
  await page.keyboard.press('Shift+Tab');
  check(await last.evaluate(element => element === document.activeElement), 'review dialog traps Shift+Tab at its first visible control');
}
async function catalogAdd(page, query) {
  await page.locator('#mode-catalog').click();
  await page.locator('#catalog-query').fill(query);
  const matches = page.locator('[data-add-food]:visible');
  check(await matches.count() > 0, `catalog search finds ${query}`);
  await matches.first().click();
}
async function validFruitDraft(page) {
  await parse(page, 'банан 80,5 г, яблоко 100,25 г');
  check(await items(page).count() === 2, 'decimal fruit description retains both food entries');
  return assertTotal(page, 'comma decimal gram amounts');
}

(async () => {
  prototype = fs.readFileSync(prototypePath, 'utf8');
  evidence.prototype_sha256 = crypto.createHash('sha256').update(prototype).digest('hex');
  browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox', '--disable-crashpad-for-testing'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1100 }, deviceScaleFactor: 1 });
  page.setDefaultTimeout(10000);
  page.on('pageerror', error => evidence.javascript_errors.push(error.message));
  await page.route(/^https?:\/\//, route => {
    evidence.external_requests.push({ url: route.request().url(), method: route.request().method() });
    return route.abort();
  });
  await reset(page);
  check(await items(page).count() === 0 && await savedCount(page) === 0, 'initial food draft and saved demo meals are empty');
  await blocked(page, 'empty food draft cannot reach meal confirmation');
  const backlink = page.locator('a[href*="overview-prototype.html"]');
  check(await backlink.count() > 0 && !!(await backlink.first().innerText()).trim(), 'food entry has a named backlink to the overview prototype');
  for (const mode of ['photo', 'description', 'catalog']) {
    const tab = page.locator(`#mode-${mode}`);
    check(await tab.getAttribute('data-mode') === mode, `${mode} tab has its declared mode identity`);
    await tab.click();
  }

  await parse(page, 'гречка 150 г, куриная грудка 100 г, банан 80 г');
  check(await items(page).count() === 3, 'supported gram description retains each named food');
  await blocked(page, 'grains and meat require an explicit raw or cooked state');
  const buckwheat = items(page).nth(0);
  check(!Number.isFinite(await nutrition(buckwheat)), 'unresolved preparation has no invented zero or nutrition amount');
  await chooseState(buckwheat, 'raw');
  const rawCarbs = await nutrition(items(page).nth(0));
  await chooseState(items(page).nth(0), 'cooked');
  const cookedCarbs = await nutrition(items(page).nth(0));
  check(Number.isFinite(rawCarbs) && Number.isFinite(cookedCarbs) && rawCarbs !== cookedCarbs, 'raw and cooked buckwheat choose distinct nutrition data');
  await chooseState(items(page).nth(1), 'cooked');
  check(!(await page.locator('#review-meal').isDisabled()), 'resolved food preparation permits review');
  const mixedTotal = await assertTotal(page, 'supported description calculation');
  await chooseState(items(page).nth(0), 'raw');
  check(await assertTotal(page, 'calculation after cooked-to-raw preparation change') !== mixedTotal, 'changing preparation updates the entire meal total');
  await chooseState(items(page).nth(0), 'cooked');
  await screenshot(page, 'food-entry-preview.png');

  await page.locator('#review-meal').click();
  check(await modalVisible(page), 'review opens a separate confirmation dialog');
  const dialog = page.locator('#meal-modal [role="dialog"], #meal-modal[role="dialog"]').first();
  check(await dialog.count() === 1 && await dialog.getAttribute('aria-modal') === 'true', 'meal confirmation has modal dialog semantics');
  check(number(await page.locator('#review-carbs').innerText()) === mixedTotal, 'meal confirmation presents the calculated carbohydrate total');
  check(await savedCount(page) === 0, 'opening meal review does not save a meal');
  await assertTrap(page);
  await screenshot(page, 'food-entry-review.png');
  await page.locator('#meal-back').click();
  check(!(await modalVisible(page)) && await savedCount(page) === 0, 'Back closes review without recording and preserves the draft');
  check(await items(page).count() === 3, 'Back retains all draft ingredients');
  await page.locator('#review-meal').click();
  await page.keyboard.press('Escape');
  check(!(await modalVisible(page)) && await savedCount(page) === 0, 'Escape closes review without saving');
  check(await page.locator('#review-meal').evaluate(element => element === document.activeElement), 'Escape restores focus to the meal review control');

  await page.locator('#review-meal').click();
  await setRaw(field(items(page).nth(0), 'mass'), '');
  check(!(await modalVisible(page)) || await page.locator('#meal-save').isDisabled(), 'editing a reviewed draft invalidates its previous confirmation');
  await blocked(page, 'a newly missing mass blocks the stale meal confirmation');
  check(await savedCount(page) === 0, 'editing a reviewed draft cannot create a saved meal');
  if (await modalVisible(page)) await page.locator('#meal-back').click();
  await setRaw(field(items(page).nth(0), 'mass'), '150');
  for (const invalid of ['', '0', '-1', 'NaN', 'Infinity', '1e309']) {
    await setRaw(field(items(page).nth(0), 'mass'), invalid);
    await blocked(page, `mass ${JSON.stringify(invalid)} cannot reach confirmation`);
  }
  await setRaw(field(items(page).nth(0), 'mass'), '150.5');
  await assertTotal(page, 'dot decimal mass edits');
  await page.locator('#review-meal').click();
  await items(page).nth(2).locator('[data-remove-item]').evaluate(element => element.click());
  check(!(await modalVisible(page)) || await page.locator('#meal-save').isDisabled(), 'removing a reviewed ingredient invalidates its previous confirmation');
  check(await items(page).count() === 2 && await savedCount(page) === 0, 'ingredient removal updates the draft without saving');
  await assertTotal(page, 'calculation after ingredient removal');

  await reset(page);
  await parse(page, 'банан 80 г, таинственное блюдо 100 г');
  check(await items(page).count() === 2, 'unknown described food is retained rather than silently dropped');
  const unknown = items(page).nth(1);
  check(/таинственное/i.test(await unknown.locator('.original-text').innerText()), 'unknown entry preserves the described food name');
  check(!Number.isFinite(await nutrition(unknown)), 'unknown food has unknown nutrition rather than zero carbohydrates');
  await blocked(page, 'unknown food or nutrition source blocks the entire meal confirmation');
  await reset(page);
  await parse(page, 'банан 80, яблоко 100 г');
  check(await items(page).count() === 2 && !(await value(field(items(page).first(), 'mass'))), 'description does not silently interpret a unitless number as grams');
  await blocked(page, 'unitless weight requires clarification');
  await reset(page);
  await parse(page, 'банан 80 г, тост с джемом, яблоко 100 г');
  check(await items(page).count() === 3, 'whole description preserves an unweighted unsupported middle ingredient');
  await blocked(page, 'an unresolved description fragment blocks partial meal confirmation');
  await reset(page);
  await parse(page, 'банан около 80 г');
  check(await items(page).count() === 1, 'approximate gram description retains its food entry');
  await blocked(page, 'approximate described mass requires a measured value before confirmation');
  await setRaw(field(items(page).first(), 'mass'), '82');
  check(!(await page.locator('#review-meal').isDisabled()), 'explicit mass edit resolves an approximate description');

  await reset(page);
  await catalogAdd(page, 'рис');
  check(await items(page).count() === 1, 'catalog selection creates one editable food row');
  const rice = items(page).first();
  await blocked(page, 'catalog rice requires both preparation and mass');
  await chooseState(rice, 'cooked');
  await setRaw(field(items(page).first(), 'mass'), '125.5');
  await assertTotal(page, 'catalog selection with edited gram mass');
  await field(items(page).first(), 'food').selectOption('');
  await blocked(page, 'removing catalog identity invalidates the nutrition source');
  check(!Number.isFinite(await nutrition(items(page).first())), 'cleared food identity cannot retain stale known nutrition');
  await field(items(page).first(), 'food').selectOption('rice');
  await chooseState(items(page).first(), 'cooked');
  await page.locator('#add-food').click();
  check(await items(page).count() === 2, 'manual add retains an empty unknown ingredient');
  await blocked(page, 'empty manually added food cannot disappear from meal validation');
  await items(page).last().locator('[data-remove-item]').click();
  check(!(await page.locator('#review-meal').isDisabled()), 'removing the unresolved row restores review of the resolved draft');

  await reset(page);
  await catalogAdd(page, 'йогурт');
  check(await items(page).count() === 1 && await value(field(items(page).first(), 'state')) === 'label', 'packaged catalog example selects its package nutrition state automatically');
  check(await field(items(page).first(), 'carbs100').count() === 0 && Number.isFinite(await nutrition(items(page).first())), 'known package example supplies nutrition without requiring a manual carbohydrate value');
  await setRaw(field(items(page).first(), 'mass'), '180');
  check(await assertTotal(page, 'packaged yogurt example with mass only') === 21.6, 'packaged yogurt example calculates 21.6 g carbohydrates from 180 g of food');

  await reset(page);
  await page.locator('#mode-catalog').click();
  await page.locator('#add-label-food').click();
  check(await items(page).count() === 1, 'package label adds a custom nutrition row');
  await setRaw(field(items(page).first(), 'name'), 'Напиток без углеводов');
  await setRaw(field(items(page).first(), 'mass'), '250.5');
  await blocked(page, 'blank package nutrition remains unknown');
  await setRaw(field(items(page).first(), 'carbs100'), '0');
  check(!(await page.locator('#review-meal').isDisabled()), 'explicit package nutrition zero is valid known data');
  check(await assertTotal(page, 'known zero carbohydrate package label') === 0, 'known zero carbohydrate meal retains a real zero total');
  await setRaw(field(items(page).first(), 'carbs100'), '2,35');
  await assertTotal(page, 'custom package label with decimal nutrition and mass');
  for (const invalid of ['-1', '101', 'NaN', 'Infinity']) {
    await setRaw(field(items(page).first(), 'carbs100'), invalid);
    await blocked(page, `package nutrition ${JSON.stringify(invalid)} cannot reach confirmation`);
  }
  await setRaw(field(items(page).first(), 'carbs100'), '');
  await blocked(page, 'clearing package nutrition invalidates the previously known source');

  await reset(page);
  await page.locator('#mode-photo').click();
  const image = Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="160" height="100"><rect width="160" height="100" fill="#e8f3ed"/><circle cx="80" cy="50" r="30" fill="#416c5a"/></svg>');
  await page.locator('#photo-file').setInputFiles({ name: 'arbitrary-local-fixture.svg', mimeType: 'image/svg+xml', buffer: image });
  const preview = page.locator('#uploaded-preview');
  check(await preview.isVisible(), 'arbitrary local photo upload shows a preview');
  check(/^(blob:|data:)/.test(await preview.getAttribute('src')), 'uploaded image preview uses an in-browser local URL');
  await preview.evaluate(image => image.decode());
  check(await preview.evaluate(image => image.naturalWidth > 0), 'local image preview renders the uploaded fixture');
  check(await items(page).count() === 0, 'arbitrary uploaded photo does not inject canned recognition results');
  check(/локаль|устройств|браузер|не отправ|предпросмотр/i.test(await page.locator('#photo-status').innerText()), 'photo status describes local preview behavior');
  await blocked(page, 'photo preview alone provides no inferred food mass or nutrition');
  await page.locator('#photo-demo').click();
  check(await items(page).count() > 0, 'explicit sample photo action creates a separate sample draft');
  check(await page.locator('#sample-preview').isVisible() && /пример|образец|демо|учебн/i.test(await page.locator('#sample-preview').innerText()), 'sample photo result explicitly identifies canned demonstration data');
  await screenshot(page, 'food-entry-photo.png');

  await reset(page);
  const finalTotal = await validFruitDraft(page);
  await page.locator('#review-meal').click();
  check(await savedCount(page) === 0, 'a valid review still requires a separate Save confirmation');
  await page.locator('#meal-save').click();
  check(await page.locator('#meal-receipt').isVisible(), 'confirmed meal shows a receipt');
  check(number(await page.locator('#receipt-carbs').innerText()) === finalTotal, 'receipt uses the reviewed weighted carbohydrate total');
  check(await savedCount(page) === 1, 'exactly one demo meal is recorded after explicit confirmation');
  check(/демо|пример|макет|учебн/i.test(await page.locator('#meal-receipt').innerText()), 'receipt identifies the record as a demonstration');
  await page.locator('#receipt-done').click();
  check(await savedCount(page) === 1, 'closing the receipt does not create another meal record');

  await reset(page);
  await validFruitDraft(page);
  const themeToggle = page.locator('#food-theme');
  check(await themeToggle.count() === 1, 'food entry exposes a theme control');
  const lightBackground = await page.locator('body').evaluate(body => getComputedStyle(body).backgroundColor);
  await themeToggle.click();
  const darkBackground = await page.locator('body').evaluate(body => getComputedStyle(body).backgroundColor);
  check(lightBackground !== darkBackground, 'dark theme changes the effective page background');
  for (const dark of [true, false]) {
    if (!dark) await themeToggle.click();
    for (const width of [320, 360, 430, 768]) {
      await page.setViewportSize({ width, height: 940 });
      check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${dark ? 'dark' : 'light'} layout has no horizontal overflow at ${width}px`);
      check(await page.locator('#food-items input, #food-items select, #review-meal').evaluateAll(elements => elements.filter(element => element.getClientRects().length).every(element => {
        const bounds = element.getBoundingClientRect();
        return bounds.left >= -0.5 && bounds.right <= innerWidth + 0.5;
      })), `food fields and review control fit the viewport at ${width}px`);
      await page.locator('#review-meal').click();
      check(await modalVisible(page), `review remains reachable at ${width}px in ${dark ? 'dark' : 'light'} mode`);
      check(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `review dialog has no horizontal page overflow at ${width}px`);
      await page.locator('#meal-back').click();
    }
  }
  await page.setViewportSize({ width: 360, height: 940 });
  await screenshot(page, 'food-entry-mobile.png');
  check(evidence.external_requests.length === 0, 'all entry modes including arbitrary photo upload make no external HTTP requests');
  check(evidence.javascript_errors.length === 0, 'all food-entry scenarios complete without browser JavaScript errors');
  check(crypto.createHash('sha256').update(fs.readFileSync(prototypePath)).digest('hex') === evidence.prototype_sha256, 'verified HTML revision stayed unchanged throughout the browser scenarios');
  evidence.passed = evidence.checks.length;
  evidence.status = 'passed';
})().catch(error => {
  evidence.status = 'failed';
  evidence.failure = error.stack || String(error);
  evidence.passed = evidence.checks.length;
  process.exitCode = 1;
}).finally(async () => {
  fs.writeFileSync(evidencePath, JSON.stringify(evidence, null, 2) + '\n');
  if (browser) await browser.close();
  console.log(JSON.stringify({ status: evidence.status, passed: evidence.passed, failure: evidence.failure, evidence: evidencePath }, null, 2));
});
