// Holds Karma's start until kolt.wasm is up: the common tests call Jolt.init() without waiting.
(function () {
    var loaded = __karma__.loaded.bind(__karma__);
    __karma__.loaded = function () {
        createKoltModule().then(function () { loaded(); });
    };
}());
