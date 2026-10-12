// kolt.js (defines createKoltModule) and the bootstrap that waits for it go before the tests; kolt.wasm is only
// served, for kolt.js to fetch next to itself. All three are test resources, under the test package's `kotlin/`.
config.files = [
    { pattern: 'kotlin/kolt.js', watched: false, included: true, served: true, nocache: true },
    { pattern: 'kotlin/kolt.wasm', watched: false, included: false, served: true, nocache: true },
    { pattern: 'kotlin/kolt-karma-bootstrap.js', watched: false, included: true, served: true, nocache: true },
].concat(config.files || []);
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
