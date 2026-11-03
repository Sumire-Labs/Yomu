const { test } = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { validateReleaseMetadata, validateVersion, prepare, sha256, alreadyPublished } = require('./release.cjs');

test('release metadata uses the Cleanroom result, including inherited stages', () => {
    const info = (tag, version, stage) => ({tag, version, stage, tagBuild: true});
    assert.deepEqual(validateReleaseMetadata(info('v0.1.0-release', '0.1.0', 'release'), 'v0.1.0-release'), {version: '0.1.0', prerelease: false});
    assert.deepEqual(validateReleaseMetadata(info('v0.1.0', '0.1.0-beta', 'beta'), 'v0.1.0'), {version: '0.1.0-beta', prerelease: true});
    assert.deepEqual(validateReleaseMetadata(info('0.1.0', '0.1.0-beta', 'beta'), '0.1.0'), {version: '0.1.0-beta', prerelease: true});
    assert.deepEqual(validateReleaseMetadata(info('0.1.0-release', '0.1.0', 'release'), '0.1.0-release'), {version: '0.1.0', prerelease: false});
    assert.deepEqual(validateReleaseMetadata(info('v1.2.3-rc', '1.2.3-rc', 'rc'), 'v1.2.3-rc'), {version: '1.2.3-rc', prerelease: true});
    assert.throws(() => validateReleaseMetadata(info('v1.2.3', '1.2.3', 'beta'), 'v1.2.3'));
    assert.throws(() => validateReleaseMetadata(info('v1.2.3', '1.2.3', 'release'), 'v1.2.4'));
    assert.throws(() => validateReleaseMetadata({...info('v1.2.3', '1.2.3', 'release'), tagBuild: false}, 'v1.2.3'));
    for (const version of ['main', '01.2.3', '1.2', '1.2.3-SNAPSHOT', '1.2.3-rc.1', '1.2.3+run.1', '1.2.3;echo bad', '1.2.3\n']) assert.throws(() => validateVersion(version), version);
});

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'yomu-release-'));
    t.after(() => {
        assert.equal(path.dirname(path.resolve(root)), path.resolve(os.tmpdir()));
        assert(path.basename(root).startsWith('yomu-release-'));
        fs.rmSync(root, { recursive: true, force: true });
    });
    for (const artifact of ['yomu', 'yomu-mapper']) {
        const dir = path.join(root, 'build/release-repository/com/sumirelabs', artifact, '1.2.3');
        fs.mkdirSync(dir, { recursive: true });
        const dependency = '<dependency><groupId>com.sumirelabs</groupId><artifactId>yomu</artifactId><version>1.2.3</version></dependency>';
        fs.writeFileSync(path.join(dir, artifact + '-1.2.3.pom'), `<project><groupId>com.sumirelabs</groupId><artifactId>${artifact}</artifactId><version>1.2.3</version><dependencies>${artifact === 'yomu-mapper' ? dependency : ''}</dependencies></project>`);
        fs.writeFileSync(path.join(dir, artifact + '-1.2.3.module'), JSON.stringify({component: {group: 'com.sumirelabs', module: artifact, version: '1.2.3'}, variants: [{name: 'runtimeElements', attributes: {'org.gradle.jvm.version': 8}, dependencies: artifact === 'yomu-mapper' ? [{group: 'com.sumirelabs', module: 'yomu', version: {requires: '1.2.3'}}] : []}]}));
        for (const suffix of ['.jar', '-sources.jar', '-javadoc.jar']) fs.writeFileSync(path.join(dir, artifact + '-1.2.3' + suffix), artifact + suffix);
    }
    return root;
}

test('staging selects exactly six JARs and checksums, excluding development agents', t => {
    const root = fixture(t);
    fs.writeFileSync(path.join(root, 'build/release-repository/agent.jar'), 'not a release artifact');
    const manifest = prepare(root, '1.2.3');
    assert.equal(manifest.files.length, 10);
    const assets = path.join(root, 'build/release-assets/1.2.3');
    assert.equal(fs.readdirSync(assets).length, 7);
    for (const line of fs.readFileSync(path.join(assets, 'SHA256SUMS'), 'utf8').trim().split('\n')) {
        const [hash, name] = line.split('  '); assert.equal(hash, sha256(fs.readFileSync(path.join(assets, name))));
    }
});

test('a mismatched mapper dependency is rejected before upload', t => {
    const root = fixture(t), pom = path.join(root, 'build/release-repository/com/sumirelabs/yomu-mapper/1.2.3/yomu-mapper-1.2.3.pom');
    fs.writeFileSync(pom, fs.readFileSync(pom, 'utf8').replace('<version>1.2.3</version></dependency>', '<version>0.0.1</version></dependency>'));
    assert.throws(() => prepare(root, '1.2.3'), /same core version/);
});

test('missing JARs or non-Java-8 metadata prevent preparation', t => {
    const root = fixture(t), dir = path.join(root, 'build/release-repository/com/sumirelabs/yomu/1.2.3');
    const metadata = path.join(dir, 'yomu-1.2.3.module');
    fs.writeFileSync(metadata, fs.readFileSync(metadata, 'utf8').replace('"org.gradle.jvm.version":8', '"org.gradle.jvm.version":17'));
    assert.throws(() => prepare(root, '1.2.3'), /Java 8/);
    fs.unlinkSync(path.join(dir, 'yomu-1.2.3.jar'));
    assert.throws(() => prepare(root, '1.2.3'), /ENOENT/);
});

test('remote checks distinguish new, identical, conflicting and partial publications', async () => {
    const manifest = { files: [{remote: 'a', sha256: sha256('a')}, {remote: 'b', sha256: sha256('b')}] };
    const check = request => alreadyPublished(manifest, 'https://example.invalid/maven/releases', 'ci', 'test-token', request);
    assert.equal(await check(async () => new Response(null, { status: 404 })), false);
    assert.equal(await check(async (url, options) => {
        assert.equal(options.headers.Authorization, 'Basic ' + Buffer.from('ci:test-token').toString('base64'));
        assert.equal(options.redirect, 'error'); return new Response(url.endsWith('/a') ? 'a' : 'b');
    }), true);
    await assert.rejects(check(async () => new Response('different')), /differs/);
    await assert.rejects(check(async url => url.endsWith('/a') ? new Response('a') : new Response(null, { status: 404 })), /partially published/);
    await assert.rejects(check(async () => new Response(null, { status: 401 })), /HTTP 401/);
    await assert.rejects(alreadyPublished(manifest, 'http://example.invalid', 'ci', 'token'), /HTTPS/);
    await assert.rejects(alreadyPublished(manifest, 'https://example.invalid', 'ci', ''), /MAVEN_PASSWORD/);
    await assert.rejects(alreadyPublished(manifest, 'https://example.invalid', '', 'test-password'), /MAVEN_USER/);
});
