const { chromium } = require('playwright');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const designDir = path.resolve(__dirname, '../../design');
const prototype = fs.readFileSync(path.join(designDir, 'overview-prototype.html'), 'utf8');
const evidence = {
  checked_at: new Date().toISOString(),
  prototype_sha256: crypto.createHash('sha256').update(prototype).digest('hex'),
  checks: [], passed: 0, javascript_errors: [], external_requests: [], screenshots: [],
  scope: 'Offline HTML concept in Chromium; entry confirmation saves demo history in memory. No native Android rendering, insulin calculation, dose delivery, or pump behavior tested.'
};
let browser;
function check(condition, message) {
  if (!condition) throw Error(message);
  evidence.checks.push(message);
}
async function screenshot(page, name, options = { fullPage: true }) {
  await page.locator('.app-body').evaluate(element => { element.scrollTop = 0; });
  if (!options.fullPage) await page.locator('.device').evaluate(element => element.scrollIntoView({ block: 'start', behavior: 'instant' }));
  await page.screenshot({ path: path.join(designDir, name), ...options });
  evidence.screenshots.push(name);
}
async function screenshotDevice(page, name) {
  await page.locator('.app-body').evaluate(element => { element.scrollTop = 0; });
  await page.locator('.device').evaluate(element => { if (element.contains(document.activeElement)) document.activeElement.blur(); });
  await page.locator('.device').screenshot({ path: path.join(designDir, name) });
  evidence.screenshots.push(name);
}
async function assertNoOverflow(page, message) {
  check(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth), message);
}
async function historyRows(page) {
  await page.locator('[data-detail="history"]').first().click();
  const rows = await page.locator('#dialog-list .dialog-row').allTextContents();
  await page.keyboard.press('Escape');
  return rows;
}
async function openEntry(page, type) {
  await page.locator(`[data-entry="${type}"]`).click();
  check(await page.locator('#entry-modal').isVisible(), `${type} entry opens`);
}
async function closeEntry(page) {
  await page.keyboard.press('Escape');
  check(!(await page.locator('#entry-modal').isVisible()), 'Escape closes treatment entry');
}
async function assertHistoryCount(page, count, message) {
  const rows = await historyRows(page);
  check(rows.length === count, `${message} (expected ${count} history rows, got ${rows.length})`);
  return rows;
}
async function assertFocusTrap(page, message) {
  const focusableElements = await page.locator('#entry-modal').evaluate(modal => {
    return [...modal.querySelectorAll('input,button,a[href],select,textarea,[tabindex]:not([tabindex="-1"])')]
      .filter(element => !element.disabled && element.type !== 'hidden' && element.getClientRects().length && getComputedStyle(element).visibility !== 'hidden')
      .map(element => element.id);
  });
  check(focusableElements.length >= 2 && focusableElements.every(Boolean), `${message}: visible controls have stable IDs`);
  const first = page.locator(`#${focusableElements[0]}`), last = page.locator(`#${focusableElements.at(-1)}`);
  await last.focus();
  await page.keyboard.press('Tab');
  check(await first.evaluate(element => element === document.activeElement), `${message}: Tab wraps to first visible control`);
  await page.keyboard.press('Shift+Tab');
  check(await last.evaluate(element => element === document.activeElement), `${message}: Shift+Tab wraps to last visible control`);
}
async function assertEntryValidation(page, type, field, value) {
  await openEntry(page, type);
  await page.locator(field).fill(value);
  if (type === 'carbs') await page.locator('#entry-insulin').fill('0');
  await page.locator('#entry-next').click();
  check(!(await page.locator('#entry-summary').isVisible()), `${type}: invalid ${field}=${JSON.stringify(value)} cannot reach confirmation`);
  const feedback = await page.locator('#entry-modal').evaluate(modal => {
    return [...modal.querySelectorAll('#entry-error,[role="alert"],[aria-invalid="true"],:invalid')].some(element =>
      element.getClientRects().length && (element.matches(':invalid,[aria-invalid="true"]') || element.textContent.trim())
    );
  });
  check(feedback, `${type}: invalid value has visible or native validation feedback`);
  await closeEntry(page);
}
(async () => {
  browser = await chromium.launch({ executablePath: '/usr/bin/chromium', headless: true, args: ['--no-sandbox', '--disable-crashpad-for-testing'] });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1150 }, deviceScaleFactor: 1 });
  page.on('pageerror', error => evidence.javascript_errors.push(error.message));
  page.on('request', request => { if (/^https?:/.test(request.url())) evidence.external_requests.push(request.url()); });
  await page.setContent(prototype);
  await screenshot(page, 'overview-preview.png');
  for (const state of ['20', '40', '60', 'recovered', 'connected']) {
    await page.locator(`.scenario[data-state="${state}"]`).click();
    check(await page.locator(`.scenario[data-state="${state}"]`).getAttribute('aria-pressed') === 'true', `connection state ${state} selected`);
    check(await page.locator('#connection-notice').isVisible() === (state !== 'connected'), `connection notice visibility for ${state}`);
  }
  await page.locator('[data-state="40"]').click();
  await page.locator('#notice-action').click();
  await page.locator('#dialog-recover').click();
  check((await page.locator('#notice-title').innerText()).includes('восстановлена'), 'demo pump recovery action');
  await page.locator('#notice-dismiss').click();
  check(!(await page.locator('#connection-notice').isVisible()), 'recovered notice dismissal');
  for (const range of ['6', '12', '3']) {
    await page.locator(`[data-range="${range}"]`).click();
    check((await page.locator('.chart').getAttribute('aria-label')).includes(range + ' часов'), `chart range ${range} hours`);
  }
  for (const type of ['iob', 'cob', 'activity', 'history', 'pump', 'profile', 'loop', 'overview']) {
    await page.locator(`[data-detail="${type}"]`).first().click();
    check(await page.locator('#modal').isVisible(), `detail dialog ${type} opens`);
    await page.keyboard.press('Escape');
    check(!(await page.locator('#modal').isVisible()), `detail dialog ${type} dismisses with Escape`);
  }
  await page.locator('.theme-toggle').click();
  check((await page.locator('body').getAttribute('class')).includes('dark'), 'dark theme selection');
  await screenshotDevice(page, 'overview-dark.png');
  await page.locator('.theme-toggle').click();
  await page.locator('[data-state="40"]').click();
  await screenshotDevice(page, 'overview-disconnected.png');
  await page.locator('[data-state="connected"]').click();
  const heights = await page.locator('.metric-mini').evaluateAll(elements => elements.map(element => element.getBoundingClientRect().height));
  check(heights.every(height => height === 4), 'metric bars are 4px and fit inside cards');
  const initialRows = await historyRows(page);
  let expectedRows = initialRows.length;
  const entryControls = await page.locator('.treatment-actions [data-entry]').evaluateAll(elements => elements.map(element => element.getAttribute('aria-label') || element.textContent));
  check(entryControls.some(label => label.includes('Ввести инсулин')) && entryControls.some(label => label.includes('Ввести углеводы')), 'bottom treatment buttons have explicit insulin and carbohydrate labels');
  const targetSizes = await page.locator('.treatment-actions [data-entry]').evaluateAll(elements => elements.map(element => ({ height: element.getBoundingClientRect().height, width: element.getBoundingClientRect().width })));
  check(targetSizes.every(size => size.height >= 44 && size.width >= 44), 'bottom treatment buttons have touch targets of at least 44px');
  await openEntry(page, 'carbs');
  check(await page.locator('#entry-carbs').isVisible() && await page.locator('#entry-insulin').isVisible(), 'carbohydrate action shows grams and desired insulin together');
  check(await page.locator('#entry-food-link').isVisible(), 'carbohydrate entry offers food selection');
  check(await page.locator('#entry-food-link').getAttribute('href') === 'food-entry-prototype.html', 'food selection links to the offline meal prototype');
  const semantics = await page.locator('#entry-modal').evaluate(modal => {
    const dialog = modal.matches('[role="dialog"]') ? modal : modal.querySelector('[role="dialog"]');
    const named = dialog && (dialog.getAttribute('aria-label') || (dialog.getAttribute('aria-labelledby') || '').split(/\s+/).some(id => document.getElementById(id)?.textContent.trim()));
    return !!(dialog && dialog.getAttribute('aria-modal') === 'true' && named);
  });
  check(semantics, 'treatment entry has a named modal dialog');
  for (const field of ['#entry-carbs', '#entry-insulin']) {
    const labelled = await page.locator(field).evaluate(input =>
      !!([...input.labels || []].some(label => label.textContent.trim()) || input.getAttribute('aria-label') || (input.getAttribute('aria-labelledby') || '').split(/\s+/).some(id => document.getElementById(id)?.textContent.trim()))
    );
    check(labelled, `${field} has an accessible label`);
  }
  await assertFocusTrap(page, 'entry form');
  await page.locator('#entry-carbs').fill('25');
  await page.locator('#entry-insulin').fill('1');
  await page.locator('#entry-cancel').click();
  check(!(await page.locator('#entry-modal').isVisible()), 'entry form Cancel closes without recording');
  await assertHistoryCount(page, expectedRows, 'Cancel does not add a history record');
  for (const value of ['', '0', '-1', 'abc']) {
    await assertEntryValidation(page, 'insulin', '#entry-insulin', value);
    await assertHistoryCount(page, expectedRows, 'Invalid insulin does not add a history record');
  }
  for (const value of ['', '0', '-1', 'abc', '1.5', '1,5']) {
    await assertEntryValidation(page, 'carbs', '#entry-carbs', value);
    await assertHistoryCount(page, expectedRows, 'Invalid carbohydrates do not add a history record');
  }
  await openEntry(page, 'carbs');
  await page.locator('#entry-carbs').fill('25');
  await page.locator('#entry-insulin').fill('-1');
  await page.locator('#entry-next').click();
  check(!(await page.locator('#entry-summary').isVisible()), 'negative desired insulin cannot reach carbohydrate confirmation');
  await closeEntry(page);
  await assertHistoryCount(page, expectedRows, 'Negative insulin does not add a history record');
  await openEntry(page, 'insulin');
  check(!(await page.locator('#entry-food-link').isVisible()), 'manual insulin entry keeps the food shortcut hidden');
  await page.locator('#entry-insulin').fill('1,25');
  await page.locator('#entry-next').click();
  check(await page.locator('#entry-summary').isVisible(), 'comma decimal insulin reaches a separate confirmation');
  check(/1[,.]25/.test(await page.locator('#entry-summary').innerText()), 'confirmation shows the entered insulin dose');
  await assertFocusTrap(page, 'confirmation');
  await page.locator('#entry-back').click();
  check(await page.locator('#entry-insulin').inputValue() === '1,25', 'Back preserves the entered insulin amount');
  await page.locator('#entry-next').click();
  await closeEntry(page);
  check(await page.locator('[data-entry="insulin"]').evaluate(element => element === document.activeElement), 'Escape restores focus to the insulin action');
  await assertHistoryCount(page, expectedRows, 'Confirmation and Escape do not record insulin');
  await page.setViewportSize({ width: 430, height: 940 });
  await openEntry(page, 'insulin');
  await page.locator('#entry-insulin').fill('1.25');
  await screenshot(page, 'overview-insulin-entry.png', { fullPage: false });
  await page.locator('#entry-next').click();
  check(await page.locator('#entry-summary').isVisible(), 'dot decimal insulin reaches confirmation');
  await page.locator('#entry-confirm').click();
  check(await page.locator('#entry-result').isVisible(), 'second insulin confirmation shows a demo result');
  check(/демо|демонстрац/i.test(await page.locator('#entry-result').innerText()), 'insulin result explicitly identifies a demo entry');
  await closeEntry(page);
  expectedRows++;
  const insulinRows = await assertHistoryCount(page, expectedRows, 'Insulin records only after the second confirmation');
  check(insulinRows.some(row => /1[,.]25/.test(row)), 'confirmed insulin amount is present in demo history');
  await openEntry(page, 'carbs');
  await page.locator('#entry-carbs').fill('24');
  await page.locator('#entry-insulin').fill('1,5');
  await screenshot(page, 'overview-carbs-entry.png', { fullPage: false });
  await page.locator('#entry-next').click();
  check(await page.locator('#entry-summary').isVisible(), 'combined carbohydrates and insulin reaches confirmation');
  const combinedSummary = await page.locator('#entry-summary').innerText();
  check(/\b24\b/.test(combinedSummary) && /1[,.]5/.test(combinedSummary), 'combined confirmation shows integer grams and manually entered insulin');
  await closeEntry(page);
  await assertHistoryCount(page, expectedRows, 'Combined action does not record before the second confirmation');
  await openEntry(page, 'carbs');
  await page.locator('#entry-carbs').fill('24');
  await page.locator('#entry-insulin').fill('1,5');
  await page.locator('#entry-next').click();
  await page.locator('#entry-confirm').click();
  check(await page.locator('#entry-result').isVisible(), 'combined second confirmation shows a demo result');
  await closeEntry(page);
  expectedRows++;
  const combinedRows = await assertHistoryCount(page, expectedRows, 'Combined action adds exactly one history event');
  check(combinedRows.some(row => /\b24\b/.test(row) && /1[,.]5/.test(row)), 'one demo history event contains integer carbohydrates and insulin');
  await openEntry(page, 'carbs');
  await page.locator('#entry-carbs').fill('15');
  await page.locator('#entry-insulin').fill('0');
  await page.locator('#entry-next').click();
  check(await page.locator('#entry-summary').isVisible(), 'carbohydrates with zero insulin can be confirmed');
  await page.locator('#entry-confirm').click();
  await closeEntry(page);
  expectedRows++;
  await assertHistoryCount(page, expectedRows, 'Carbohydrates-only action creates one demo history event');
  for (const width of [320, 360, 430, 768]) {
    await page.setViewportSize({ width, height: 1150 });
    await assertNoOverflow(page, `no horizontal dashboard overflow at ${width}px`);
    if (width === 430) await screenshotDevice(page, 'overview-mobile.png');
    await openEntry(page, 'carbs');
    await assertNoOverflow(page, `no horizontal entry overflow at ${width}px`);
    await page.locator('#entry-carbs').fill('20');
    await page.locator('#entry-insulin').fill('1');
    await page.locator('#entry-next').click();
    await assertNoOverflow(page, `no horizontal confirmation overflow at ${width}px`);
    await closeEntry(page);
  }
  await assertHistoryCount(page, expectedRows, 'Responsive review does not create extra history events');
  check(evidence.external_requests.length === 0, 'entry flows make no external network requests');
  check(evidence.javascript_errors.length === 0, 'no JavaScript exceptions');
  evidence.passed = evidence.checks.length;
  evidence.ok = true;
  console.log(`PASS: ${evidence.passed} offline prototype checks; insulin / carbohydrate entry, separate confirmation, cancellation, demo history, accessibility, keyboard focus, and responsive widths 320/360/430/768px.`);
})().catch(error => {
  evidence.ok = false;
  evidence.failure = error.message;
  evidence.passed = evidence.checks.length;
  console.error(error);
  process.exitCode = 1;
}).finally(async () => {
  fs.writeFileSync(path.join(designDir, 'browser-verification.json'), JSON.stringify(evidence, null, 2) + '\n');
  if (browser) await browser.close();
});
