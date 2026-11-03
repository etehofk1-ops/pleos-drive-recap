import { mkdtempSync, mkdirSync, writeFileSync, readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import test from 'node:test';
import assert from 'node:assert/strict';

const workspace = dirname(dirname(fileURLToPath(import.meta.url)));
const checker = join(workspace, 'scripts', 'check-submission.ps1');
const fixtures = join(workspace, '.local');
mkdirSync(fixtures, { recursive: true });
const readme = '# 오운복\n\n' + ['앱 이름 및 한 줄 소개', '실행 방법', '테스트 환경 및 필요한 설정', 'APK 위치']
  .map(h => '## ' + h + '\n\n설명.\n').join('\n') + '\napk/\n';
function fixture() {
  const root = mkdtempSync(join(fixtures, 'submission-test-'));
  mkdirSync(join(root, 'apk')); mkdirSync(join(root, '.local'));
  writeFileSync(join(root, 'README.md'), readme);
  writeFileSync(join(root, 'apk', '.gitkeep'), '');
  return root;
}
function run(root, args = ['-SourceOnly']) {
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', checker, '-Root', root, ...args],
    { cwd: workspace, encoding: 'utf8', windowsHide: true });
  if (result.error) throw result.error;
  return { code: result.status, output: result.stdout + result.stderr };
}
test('source-only succeeds without claiming an APK or build exists', () => {
  const r = run(fixture()); assert.equal(r.code, 0, r.output); assert.match(r.output, /Source scope only/);
});
test('missing required README section fails', () => {
  const root = fixture(); writeFileSync(join(root, 'README.md'), readme.replace('## 실행 방법', '## 다른 항목'));
  const r = run(root); assert.equal(r.code, 1); assert.match(r.output, /README section missing/);
});
test('APK folder and marker are checked even in source-only mode', () => {
  const root = mkdtempSync(join(fixtures, 'submission-test-'));
  writeFileSync(join(root, 'README.md'), readme);
  const r = run(root); assert.equal(r.code, 1); assert.match(r.output, /apk\/ directory missing/);
});
test('PII and credential findings report categories without echoing the matching text', () => {
  const root = fixture();
  const values = ['person' + '@' + 'sample.invalid', ['010', '1234', '5678'].join('-'),
    ['s', 'k', '-'].join('') + 'X'.repeat(28), 'Bearer' + ' ' + 'Y'.repeat(28),
    '-----BEGIN ' + 'PRIVATE KEY-----', 'C:' + '\\Users\\' + 'sampleuser\\file'];
  writeFileSync(join(root, 'leak.txt'), values.join('\n'));
  const r = run(root); assert.equal(r.code, 1);
  for (const category of ['email', 'phone', 'key-prefix', 'bearer-value', 'private-key', 'personal-home-path'])
    assert.ok(r.output.includes('category=' + category), 'missing category ' + category);
  assert.ok(values.every(value => !r.output.includes(value)), 'matched content must stay private');
});
test('unlabelled names can be checked with an excluded local name list', () => {
  const root = fixture(), name = ['가', '나', '다'].join(''), names = join(root, '.local', 'names.txt');
  writeFileSync(names, name); writeFileSync(join(root, 'comment.txt'), '// ' + name);
  const r = run(root, ['-SourceOnly', '-NameListFile', names]);
  assert.equal(r.code, 1); assert.match(r.output, /category=provided-name/); assert.ok(!r.output.includes(name));
});
test('local secrets and dev notes are excluded but a secret file in submission scope fails', () => {
  const root = fixture(); mkdirSync(join(root, 'docs', 'dev-notes'), { recursive: true });
  writeFileSync(join(root, '.local', 'release.properties'), 'private local material');
  writeFileSync(join(root, 'docs', 'dev-notes', 'note.txt'), 'person' + '@' + 'sample.invalid');
  assert.equal(run(root).code, 0);
  writeFileSync(join(root, 'release.properties'), 'private material must not be read');
  const r = run(root); assert.equal(r.code, 1); assert.match(r.output, /local-secret-file/);
});
test('default final check fails when no APK exists', () => {
  const r = run(fixture(), []); assert.equal(r.code, 1); assert.match(r.output, /Exactly one signed release APK/);
});
function mockApkTools(root, { debug = false, version = '27', versionName = '1.9.3', debugCertificate = false } = {}) {
  const apk = join(root, 'apk', '시연팀_오운복_release.apk');
  writeFileSync(apk, 'fixture only - not an actual APK');
  const aapt = join(root, '.local', 'aapt.cmd'), signer = join(root, '.local', 'signer.cmd');
  writeFileSync(aapt, '@echo off\r\necho package: name=\'lab.pleos.observe\' versionCode=\'' + version +
    '\' versionName=\'' + versionName + '\'\r\n' + (debug ? 'echo application-debuggable\r\n' : '') + 'exit /b 0\r\n');
  writeFileSync(signer, '@echo off\r\necho Signer #1 certificate DN: CN=' + (debugCertificate ? 'Android Debug' : 'Submission') + '\r\nexit /b 0\r\n');
  return ['-Aapt2Path', aapt, '-ApkSignerPath', signer];
}
test('mock tool responses exercise version and non-debug checks, not real APK validation', () => {
  const valid = fixture(); const r = run(valid, mockApkTools(valid)); assert.equal(r.code, 0, r.output);
  for (const option of [{ debug: true }, { version: '26' }, { versionName: '1.9.2' }, { debugCertificate: true }]) {
    const root = fixture(); assert.equal(run(root, mockApkTools(root, option)).code, 1);
  }
});
test('tool failure output cannot echo a credential into checker logs', () => {
  const root = fixture(), args = mockApkTools(root), secret = ['s', 'k', '-'].join('') + 'Z'.repeat(28);
  writeFileSync(args[3], '@echo off\r\necho ' + secret + ' 1>&2\r\nexit /b 1\r\n');
  const r = run(root, args); assert.equal(r.code, 1); assert.match(r.output, /APK signature verification failed/);
  assert.ok(!r.output.includes(secret), 'tool stderr must never be echoed');
});
test('network policy allows cleartext only for the exact emulator host in both variants', () => {
  for (const variant of ['main', 'debug']) {
    const xml = readFileSync(join(workspace, 'app', 'src', variant, 'res', 'xml', 'network_security_config.xml'), 'utf8');
    assert.match(xml, /base-config cleartextTrafficPermitted="false"/);
    assert.equal((xml.match(/<domain /g) || []).length, 1);
    assert.match(xml, /<domain includeSubdomains="false">10\.0\.2\.2<\/domain>/);
  }
});
