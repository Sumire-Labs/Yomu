// Release preparation and read-only publication checks; uploads are handled by Gradle.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const assert = require('node:assert/strict');

function validateVersion(version) {
    assert.equal(typeof version, 'string');
    assert(version.length <= 128 && /^(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)\.(?:0|[1-9]\d*)(?:-(?:alpha|beta|rc))?$/.test(version), 'Expected a Cleanroom tagged release version');
}
function validateReleaseMetadata(metadata, tag) {
    assert.equal(metadata.tagBuild, true, 'Cleanroom must validate a GitHub tag build');
    assert.equal(metadata.tag, tag, 'Computed metadata belongs to another tag');
    assert(/^v?\d+\.\d+\.\d+(?:-(?:alpha|beta|rc|release))?$/i.test(tag), 'Use a Cleanroom release tag, with an optional v prefix');
    validateVersion(metadata.version);
    assert(['alpha', 'beta', 'rc', 'release'].includes(metadata.stage), 'Unknown Cleanroom stage');
    const suffix = metadata.version.includes('-') ? metadata.version.split('-')[1] : 'release';
    assert.equal(suffix, metadata.stage, 'Keep versioning.stageInVersion=true');
    return { version: metadata.version, prerelease: metadata.stage !== 'release' };
}
const sha256 = bytes => crypto.createHash('sha256').update(bytes).digest('hex');

function prepare(root, version) {
    validateVersion(version);
    const files = [], jars = [];
    for (const artifact of ['yomu', 'yomu-mapper']) {
        const directory = `com/sumirelabs/${artifact}/${version}`;
        for (const suffix of ['.jar', '-sources.jar', '-javadoc.jar', '.pom', '.module']) {
            const name = artifact + '-' + version + suffix;
            const relative = directory + '/' + name;
            const local = 'build/release-repository/' + relative;
            const bytes = fs.readFileSync(path.join(root, local));
            assert(bytes.length > 0, 'Empty publication: ' + name);
            if (suffix === '.pom') {
                const pom = bytes.toString('utf8');
                for (const value of ['<groupId>com.sumirelabs</groupId>', `<artifactId>${artifact}</artifactId>`, `<version>${version}</version>`]) assert(pom.includes(value), 'Unexpected POM: ' + name);
                if (artifact === 'yomu-mapper') {
                    const dependencies = [...pom.matchAll(/<dependency>([\s\S]*?)<\/dependency>/g)].map(x => x[1]);
                    assert(dependencies.some(x => x.includes('<groupId>com.sumirelabs</groupId>') && x.includes('<artifactId>yomu</artifactId>') && x.includes(`<version>${version}</version>`)), 'Mapper must depend on the same core version');
                }
            }
            if (suffix === '.module') {
                const metadata = JSON.parse(bytes);
                assert.deepEqual([metadata.component.group, metadata.component.module, metadata.component.version], ['com.sumirelabs', artifact, version]);
                const runtime = metadata.variants.find(x => x.name === 'runtimeElements');
                assert.equal(runtime.attributes['org.gradle.jvm.version'], 8, 'Publication must target Java 8');
                if (artifact === 'yomu-mapper') assert(runtime.dependencies.some(x => x.group === 'com.sumirelabs' && x.module === 'yomu' && x.version.requires === version), 'Incorrect mapper module dependency');
            }
            files.push({ local, remote: relative, sha256: sha256(bytes) });
            if (suffix.endsWith('.jar')) jars.push({ name, bytes });
        }
    }
    const assets = path.join(root, 'build/release-assets', version);
    fs.mkdirSync(assets, { recursive: true });
    const names = [...jars.map(x => x.name), 'SHA256SUMS'];
    assert(fs.readdirSync(assets).every(x => names.includes(x)), 'Unexpected file in release assets');
    for (const jar of jars) fs.writeFileSync(path.join(assets, jar.name), jar.bytes);
    fs.writeFileSync(path.join(assets, 'SHA256SUMS'), jars.map(x => `${sha256(x.bytes)}  ${x.name}`).join('\n') + '\n');
    const manifest = { version, files };
    fs.writeFileSync(path.join(root, 'build/release-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
    return manifest;
}

async function alreadyPublished(manifest, endpoint, username, password, request = fetch) {
    const url = new URL(endpoint);
    assert(url.protocol === 'https:' && !url.username && !url.password && !url.search && !url.hash, 'Use an HTTPS Maven repository URL without credentials, query or fragment');
    assert(username, 'MAVEN_USER is required');
    assert(password, 'MAVEN_PASSWORD is required');
    const headers = { Authorization: 'Basic ' + Buffer.from(username + ':' + password).toString('base64') };
    let found = 0;
    for (const file of manifest.files) {
        const response = await request(url.href.replace(/\/$/, '') + '/' + file.remote, { headers, redirect: 'error', signal: AbortSignal.timeout(60000) });
        if (response.status === 404) continue;
        assert.equal(response.status, 200, 'Cannot inspect Maven publication (HTTP ' + response.status + ')');
        assert.equal(sha256(Buffer.from(await response.arrayBuffer())), file.sha256, 'Existing Maven version differs: ' + file.remote);
        found++;
    }
    assert(found === 0 || found === manifest.files.length, 'Maven version is partially published. Resolve the partial upload before retrying; existing files will not be overwritten automatically.');
    return found === manifest.files.length;
}

function output(key, value) {
    if (process.env.GITHUB_OUTPUT) fs.appendFileSync(process.env.GITHUB_OUTPUT, `${key}=${value}\n`);
}
async function main() {
    switch (process.argv[2]) {
        case 'version': {
            assert.equal(process.env.GITHUB_REF_TYPE, 'tag', 'Run this workflow on a version tag');
            const result = validateReleaseMetadata(JSON.parse(fs.readFileSync('build/release-version.json', 'utf8')), process.env.GITHUB_REF_NAME || '');
            output('version', result.version); output('prerelease', result.prerelease);
            console.log('Release version: ' + result.version); break;
        }
        case 'prepare': {
            const result = validateReleaseMetadata(JSON.parse(fs.readFileSync('build/release-version.json', 'utf8')), process.env.GITHUB_REF_NAME || '');
            assert.equal(result.version, process.env.RELEASE_VERSION, 'Build and release job versions differ');
            prepare(process.cwd(), result.version); console.log('Prepared six JARs, checksums and Maven metadata'); break;
        }
        case 'check': {
            const manifest = JSON.parse(fs.readFileSync('build/release-manifest.json', 'utf8'));
            const published = await alreadyPublished(manifest, process.env.MAVEN_URL, process.env.MAVEN_USER, process.env.MAVEN_PASSWORD);
            output('published', published); console.log(published ? 'Identical Maven publication already exists' : 'Maven version is available'); break;
        }
        default: throw Error('Expected version, prepare or check');
    }
}
module.exports = { validateReleaseMetadata, validateVersion, sha256, prepare, alreadyPublished };
if (require.main === module) main().catch(error => { console.error(error.message); process.exitCode = 1; });
