/*
 * Link with the phone. Messages from the phone (JSON):
 *   { t:"frame", img:"<jpeg base64>", rec:true|false,
 *     dist:"12.4 km", dur:"3 h 25", off:62 | null,
 *     northUp: true, heading: walking direction in degrees from north (GPS) | null }
 *     car, flag: [x, y] pixels of the track ends from the image center | null (frames only)
 *     sun: {az, alt}, moon: {az, alt, phase} in degrees, phase 0..1 (0.5 = full) | absent
 *   The map is north up and centered on the hiker; the watch turns it with its compass
 *   and draws the arrow.
 *   { t:"vibrate", pattern:[...] }
 * Messages to the phone:
 *   { t:"zoom", delta:+1|-1 }   { t:"hello" }
 *
 * On the watch: Samsung Accessory (SAP) — to be written once the certificate works.
 * In the browser preview: a fake phone that sends a sample map.
 */
var Link = (function () {
    'use strict';
    var handlers = [];
    // This page's id: several watches can share the phone, each one is told apart by it
    var ID = Math.random().toString(36).slice(2, 10);
    var onTizen = typeof tizen !== 'undefined' && typeof webapis !== 'undefined' && webapis.sa;

    function emit(msg) { handlers.forEach(function (h) { h(msg); }); }

    function fakePhone() {
        var zoom = 15, t0 = Date.now(), dist = 0;
        function frame() {
            var min = Math.floor((Date.now() - t0) / 60000) + 205;  // 3 h 25 and counting
            dist += 0.01;
            // A real frame captured from the phone, when the preview provides one
            var real = window.parent && window.parent.SAMPLE_FRAME;
            if (real) {
                var f = JSON.parse(JSON.stringify(real));
                f.off = (Math.floor((Date.now() - t0) / 10000) % 2 === 1) ? 62 : null;
                emit(f);
                return;
            }
            emit({
                t: 'frame', img: null, src: '../watch-preview/sample-map.jpg',
                rec: true,
                dist: (12.4 + dist).toFixed(1) + ' km',
                dur: Math.floor(min / 60) + ' h ' + ('0' + min % 60).slice(-2),
                off: (Math.floor((Date.now() - t0) / 10000) % 2 === 1) ? 62 : null,  // alert every other 10 s
                zoom: zoom,
                heading: 35  // walking north-east on the sample map
            });
        }
        setTimeout(frame, 800);
        setInterval(frame, 5000);
        return {
            send: function (msg) {
                if (msg.t === 'zoom') { zoom = Math.max(10, Math.min(18, zoom + msg.delta)); frame(); }
            }
        };
    }

    /* Preview served by watch-preview/bridge.py: live frames from the real phone over adb */
    function bridge() {
        var last = {};
        function poll(path) {
            var x = new XMLHttpRequest();
            x.open('GET', path, true);
            x.onload = function () {
                if (x.status === 200 && x.responseText.length > 2 && x.responseText !== last[path]) {
                    last[path] = x.responseText;
                    try { emit(JSON.parse(x.responseText)); } catch (e) { /* partial file */ }
                }
            };
            x.send();
        }
        setInterval(function () { poll('/frame?id=' + ID); poll('/beat?id=' + ID); }, 1000);
        poll('/frame?id=' + ID);
        return {
            // Any watch message goes to the phone (base64 JSON -> adb broadcast -> WatchLink)
            send: function (msg) {
                var x = new XMLHttpRequest();
                x.open('GET', '/msg?b=' + encodeURIComponent(btoa(unescape(encodeURIComponent(JSON.stringify(msg))))), true);
                x.send();
            }
        };
    }

    var transport = onTizen ? null /* SAP, later */
        : (location.protocol.indexOf('http') === 0 ? bridge() : fakePhone());

    return {
        id: ID,
        onMessage: function (h) { handlers.push(h); },
        send: function (msg) { msg.id = ID; if (transport) transport.send(msg); }
    };
})();
