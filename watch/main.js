/*
 * BLRando watch.
 * Shows the north-up map sent by the phone, turned with the watch compass; time, duration, distance.
 * Alarms (vibration + message): off track, link with the phone lost, GPS lost on the phone, low battery.
 * Tap on the map (back key in the Tizen app): menu to start / stop the recording, center or fit the map.
 */
(function () {
    'use strict';

    var $ = function (id) { return document.getElementById(id); };
    function pad(n) { return (n < 10 ? '0' : '') + n; }
    function vibrate(pattern) { try { navigator.vibrate(pattern); } catch (e) { /* not supported */ } }

    var ALARM = [500, 200, 500, 200, 500];   // something is wrong
    var OK_PULSE = 200;                      // back to normal
    var LOST_MS = 30000;                     // no message from the phone for this long = link lost
    var REMIND_MS = 120000;                  // repeat the link alarm every 2 min

    var state = { rec: false, hasRef: false, guiding: false };
    var lastMsg = Date.now();
    var linkLost = false, linkRemindAt = 0;
    var wasOff = false, wasGpsLost = false;
    var phoneBattAlerted = 100, watchBattAlerted = 100;
    var zoomTimer = null, alertTimer = null;

    // ------------------------------------------------------------------ alerts banner

    /** Short message in the middle of the screen; sticky ones stay until cleared. */
    function showAlert(text, sticky) {
        $('status').textContent = text;
        $('status').style.display = 'block';
        clearTimeout(alertTimer);
        if (!sticky) alertTimer = setTimeout(function () { refreshSticky(); }, 5000);
    }

    /** Sticky alerts, most important first. */
    function refreshSticky() {
        if (linkLost) showAlert('⚠ Plus de liaison avec le téléphone', true);
        else if (wasGpsLost) showAlert('⚠ GPS perdu sur le téléphone', true);
        else $('status').style.display = 'none';
    }

    // ------------------------------------------------------------------ clock and link watchdog

    var lastTick = Date.now();

    function tick() {
        var d = new Date();
        $('time').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
        var now = Date.now();
        // Page frozen while the screen was off (the tick runs every second otherwise): the watch
        // is waking up and its Wi-Fi needs time to come back, so the silence doesn't count.
        if (now - lastTick > 5000) lastMsg = Math.max(lastMsg, now);
        lastTick = now;
        if (!linkLost && now - lastMsg > LOST_MS) {
            linkLost = true;
            linkRemindAt = now + REMIND_MS;
            vibrate(ALARM);
            refreshSticky();
        } else if (linkLost && now > linkRemindAt) {
            linkRemindAt = now + REMIND_MS;
            vibrate(ALARM);
        }
    }

    // ------------------------------------------------------------------ compass: map aligned with the ground

    var gpsHeading = null;   // walking direction, degrees from north
    var compass = 0;         // where the top of the watch points, degrees from north (smoothed, unwrapped)
    var compassSeen = false;

    /**
     * In the watch web browser there is no compass event: the map then turns with the walking
     * direction (GPS heading), so the arrow points up and the N marker shows where north is.
     */
    var inBrowser = typeof tizen === 'undefined' && location.protocol.indexOf('http') === 0
        && location.pathname.indexOf('watch-preview') < 0 && window.parent === window;

    var fitView = false; // view moved or "englober": north up, the position is drawn on the image by the phone

    // ------------------------------------------------------------------ pan: drag the map with a finger
    var panX = 0, panY = 0, dragging = false, panSent = 0;

    (function () {
        var x0 = 0, y0 = 0, touching = false, startX = 0, startY = 0;
        document.addEventListener('touchstart', function (ev) {
            if (menuItems || ev.touches.length !== 1) { touching = false; return; }
            touching = true; dragging = false;
            // Offset already shown (previous drag not yet in an image) is kept; only this drag is sent
            startX = panX; startY = panY;
            x0 = ev.touches[0].clientX - panX; y0 = ev.touches[0].clientY - panY;
        }, { passive: true });
        document.addEventListener('touchmove', function (ev) {
            if (!touching) return;
            var dx = ev.touches[0].clientX - x0, dy = ev.touches[0].clientY - y0;
            if (!dragging && Math.abs(dx - panX) + Math.abs(dy - panY) < 10) return; // still a tap
            dragging = true;
            ev.preventDefault(); // no page scroll (that would count as the bezel)
            panX = dx; panY = dy;
            applyRotation();
        }, { passive: false });
        document.addEventListener('touchend', function () {
            if (!touching) return;
            touching = false;
            if (!dragging) return;
            dragging = false;
            // The phone works on the north-up image: turn the finger move back by the map rotation
            var c = (fitView ? 0 : compass % 360) * Math.PI / 180;
            var mx = panX - startX, my = panY - startY;
            var ix = mx * Math.cos(c) - my * Math.sin(c);
            var iy = mx * Math.sin(c) + my * Math.cos(c);
            panSent++;
            Link.send({ t: 'pan', dx: Math.round(ix), dy: Math.round(iy), seq: panSent });
            justDragged = Date.now();
        });
    })();
    var justDragged = 0; // the click that may follow a drag must not open the menu

    function applyRotation() {
        if (inBrowser && !compassSeen && gpsHeading !== null) compass = gpsHeading;
        var c = fitView ? 0 : compass % 360;
        // panX/panY: finger offset, until the phone sends the moved map
        $('map').style.transform = 'translate(' + panX + 'px,' + panY + 'px) rotate(' + (-c) + 'deg)';
        $('northRing').style.transform = 'rotate(' + (-c) + 'deg)';
        // North near the top: hide the "N" (the triangle stays) so it doesn't cover the time
        var fromTop = Math.abs(((c % 360) + 540) % 360 - 180); // degrees between north and the top, 0..180
        $('northLetter').style.display = fromTop < 30 ? 'none' : '';
        if (fitView) {
            $('hiker').style.display = 'none';
            $('hikerDot').style.display = 'none';
        } else if (gpsHeading === null) {
            // Direction unknown (standing still since the start): a dot shows the position
            $('hiker').style.display = 'none';
            $('hikerDot').style.display = 'block';
        } else {
            $('hikerDot').style.display = 'none';
            $('hiker').style.display = 'block';
            $('hiker').style.transform = 'rotate(' + (gpsHeading - c) + 'deg)';
        }
    }

    function onOrientation(ev) {
        if (ev.alpha === null || ev.alpha === undefined) return;
        // alpha turns counter-clockwise; compass heading turns clockwise
        var target = (360 - ev.alpha) % 360;
        if (!compassSeen) { compass = target; compassSeen = true; }
        // Shortest way round, then a light low-pass so the map doesn't shake
        var cur = ((compass % 360) + 360) % 360;
        var diff = ((target - cur + 540) % 360) - 180;
        compass += diff * 0.35;
        applyRotation();
    }
    window.addEventListener('deviceorientationabsolute', onOrientation);
    window.addEventListener('deviceorientation', onOrientation);
    // No compass on this watch: the map simply stays north up (compass = 0)

    // ------------------------------------------------------------------ messages from the phone

    /** Bezel click: "zoom +" or "zoom −" at once (the new map arrives a moment later). */
    function showZoomTip(delta) {
        $('zoomTip').textContent = delta > 0 ? 'zoom +' : 'zoom −';
        $('zoomTip').style.display = 'block';
        clearTimeout(zoomTimer);
        zoomTimer = setTimeout(function () { $('zoomTip').style.display = 'none'; zoomTimer = null; }, 1500);
    }

    /** Fields common to frames and heartbeats. */
    var firstMsg = true, pageVer = null;

    function onStatus(m) {
        lastMsg = Date.now();
        // BLRando was reinstalled on the phone: reload to get the new page (only in the browser)
        if (m.ver !== undefined) {
            if (pageVer === null) pageVer = m.ver;
            else if (m.ver !== pageVer && inBrowser) { location.reload(); return; }
        }
        if (linkLost) { linkLost = false; vibrate(OK_PULSE); refreshSticky(); }
        if (firstMsg) { firstMsg = false; refreshSticky(); } // hides "Connexion au téléphone…"

        state.rec = !!m.rec;
        state.hasRef = !!m.hasRef; state.guiding = !!m.guiding;
        if (menuItems && !armed) drawMenu(); // recording started/stopped from the phone
        if (m.heading !== undefined) gpsHeading = m.heading;
        if (m.fit !== undefined) fitView = !!m.fit; // only in frames
        applyRotation();

        $('rec').style.display = m.rec ? 'inline-block' : 'none';
        if (m.rec) {
            $('bottom').textContent = m.dur + ' · ' + m.dist;
            $('bottom').style.display = 'block';
        } else {
            $('bottom').style.display = 'none';
        }

        // Off track (lifebuoy)
        var off = m.off !== null && m.off !== undefined;
        $('offRing').style.display = off ? 'block' : 'none';
        $('offText').style.display = off ? 'block' : 'none';
        if (off) $('offText').textContent = '⚠ ' + m.off + ' m';
        if (off && !wasOff) vibrate([700, 300, 700, 300, 700]);
        if (!off && wasOff) vibrate(OK_PULSE);
        wasOff = off;

        // GPS lost on the phone
        var gpsLost = !!m.gpsLost;
        if (gpsLost && !wasGpsLost) { wasGpsLost = true; vibrate(ALARM); refreshSticky(); }
        if (!gpsLost && wasGpsLost) { wasGpsLost = false; vibrate(OK_PULSE); refreshSticky(); }

        // Phone battery: alert once at 15 %, again at 5 %
        if (m.phoneBatt >= 0) checkBattery(m.phoneBatt, 'Téléphone', function (v) {
            if (v === undefined) return phoneBattAlerted; phoneBattAlerted = v; return v;
        });
    }

    function checkBattery(level, who, alerted) {
        var steps = [15, 5];
        for (var i = 0; i < steps.length; i++) {
            if (level <= steps[i] && alerted() > steps[i]) {
                alerted(steps[i]);
                vibrate([300, 150, 300]);
                showAlert('🔋 ' + who + ' ' + level + ' %', false);
                return;
            }
        }
        if (level > 20) alerted(100); // recharged: alerts again next time
    }

    function onFrame(m) {
        // An image drawn before our last finger move would make the map jump back: wait for the next one
        if (m.panSeq !== undefined && m.panSeq < panSent) { onStatus(m); return; }
        if (m.img) $('map').src = 'data:image/jpeg;base64,' + m.img;
        else if (m.src) $('map').src = m.src;
        if (!dragging) { panX = 0; panY = 0; } // the new image includes the move
        onStatus(m);
    }

    Link.onMessage(function (m) {
        if (m.t === 'frame') onFrame(m);
        else if (m.t === 'beat') onStatus(m);
        else if (m.t === 'vibrate') vibrate(m.pattern);
    });

    // ------------------------------------------------------------------ watch battery

    try {
        tizen.systeminfo.addPropertyValueChangeListener('BATTERY', function (b) {
            checkBattery(Math.round(b.level * 100), 'Montre', function (v) {
                if (v === undefined) return watchBattAlerted; watchBattAlerted = v; return v;
            });
        });
    } catch (e) { /* preview */ }

    // ------------------------------------------------------------------ menu (tap on the map, or back key)
    // Graphic buttons like the phone app: left = back arrow (close the menu), right = record button,
    // stop button while recording; center / fit at the bottom. Start and stop need a second tap to confirm.

    var menuItems = null;  // non-null while the menu is open
    var armed = null;      // action waiting for its confirmation tap

    var ICONS = {
        back: '<svg viewBox="0 0 24 24"><path d="M20 12H5M11 5l-7 7 7 7" fill="none" stroke="#fff" stroke-width="2.6" stroke-linecap="round" stroke-linejoin="round"/></svg>',
        start: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="7.5" fill="#FF3B30"/></svg>',
        stop: '<svg viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1.5" fill="#FF3B30"/></svg>',
        // Same icons as the phone app (ic_recenter, ic_fit)
        center: '<svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="7" fill="none" stroke="#fff" stroke-width="2"/>' +
                '<path d="M12 1.5V6M12 18v4.5M1.5 12H6M18 12h4.5" stroke="#fff" stroke-width="2" stroke-linecap="round"/>' +
                '<circle cx="12" cy="12" r="2.2" fill="#fff"/></svg>',
        fit: '<svg viewBox="0 0 24 24"><path d="M3 9V3h6M15 3h6v6M21 15v6h-6M9 21H3v-6M3 3l6 6M21 3l-6 6M21 21l-6-6M3 21l6-6" ' +
             'fill="none" stroke="#fff" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"/></svg>'
    };

    var CONFIRM = {
        start: function () { return 'Démarrer ?'; },
        stop: function () { return 'Arrêter ?'; }
    };

    function menuTitle() {
        if (!state.rec) return state.hasRef ? 'Trace chargée' : 'Pas d\'enregistrement';
        return '● Enregistrement';
    }

    function el(tag, cls, html) {
        var d = document.createElement(tag);
        d.className = cls;
        if (html) d.innerHTML = html;
        return d;
    }

    /** A tappable part of the menu: first tap arms the action, second tap on the same part runs it. */
    function actionPart(cls, act) {
        var b = el('div', cls + (armed === act ? ' armed' : ''), ICONS[act]);
        b.addEventListener('click', function (ev) {
            ev.stopPropagation(); // the tap must not reach the menu background (close) or the map
            if (armed === act) { runAction(act); return; }
            armed = act;
            vibrate(60);
            drawMenu();
        });
        return b;
    }

    /** View buttons (center, fit): no confirmation, they only change what the watch shows. */
    function viewButton(cls, act) {
        var b = el('div', 'mbtn msmall ' + cls, ICONS[act]);
        b.addEventListener('click', function (ev) {
            ev.stopPropagation();
            // "Fit": hide the center arrow at once. "Center": keep the moved view until the new image
            // arrives, otherwise its drawn arrow and the center arrow show together for a moment.
            if (act === 'fit') { fitView = true; applyRotation(); }
            runAction(act);
        });
        return b;
    }

    function drawMenu() {
        var box = $('menu');
        box.innerHTML = '';
        // Top line: the state, or the action waiting for confirmation
        box.appendChild(el('div', 'mtitle' + (armed ? ' armed' : ''))).textContent =
            armed ? CONFIRM[armed]() : menuTitle();

        var back = el('div', 'mbtn mleft', ICONS.back);
        back.addEventListener('click', function (ev) { ev.stopPropagation(); closeMenu(); });
        box.appendChild(back);

        // Record button: start, or stop while recording
        box.appendChild(actionPart('mbtn mright', state.rec ? 'stop' : 'start'));

        // Waiting for confirmation: big hint at the bottom (where the screen is wide) instead of the view buttons
        if (armed) {
            box.appendChild(el('div', 'mhint')).textContent = 'Toucher encore';
            return;
        }
        // "Fit" only with a track loaded; "center" alone is then in the middle
        box.appendChild(viewButton(state.hasRef ? 'mcenter' : 'mmid', 'center'));
        if (state.hasRef) box.appendChild(viewButton('mfit', 'fit'));
    }

    // Tap beside the buttons: cancel a pending confirmation, or close the menu
    $('menu').addEventListener('click', function (ev) {
        ev.stopPropagation();
        if (armed) { armed = null; drawMenu(); } else closeMenu();
    });

    function openMenu() {
        armed = null;
        menuItems = true;
        $('menu').style.display = 'block';
        drawMenu();
    }

    function closeMenu() {
        menuItems = null;
        armed = null;
        $('menu').style.display = 'none';
    }

    function runAction(act) {
        closeMenu();
        Link.send({ t: 'cmd', action: act });
        vibrate(OK_PULSE);
    }

    // Bezel: zoom (clockwise = zoom in); ignored while the menu is open
    document.addEventListener('rotarydetent', function (ev) {
        if (menuItems) return;
        var delta = ev.detail.direction === 'CW' ? 1 : -1;
        Link.send({ t: 'zoom', delta: delta });
        showZoomTip(delta);
    });

    // Tizen app: the back key opens / closes the menu
    document.addEventListener('tizenhwkey', function (ev) {
        var name = ev.keyName || (ev.detail && ev.detail.keyName);
        if (name !== 'back') return;
        if (menuItems) closeMenu(); else openMenu();
    });

    // ------------------------------------------------------------------ web browser: bezel = page scroll, tap = menu

    /** True once the browser delivers real bezel events: then the page scroll is no longer counted. */
    var nativeBezel = false;
    document.addEventListener('rotarydetent', function (ev) {
        if (!(ev.detail && ev.detail.fromScroll)) nativeBezel = true;
    }, true);

    function detent(delta) {
        if (nativeBezel) return;
        document.dispatchEvent(new CustomEvent('rotarydetent', { detail: { direction: delta > 0 ? 'CW' : 'CCW', fromScroll: true } }));
    }

    if (inBrowser) {
        // A tall invisible page: the browser scrolls it with the bezel, we turn each scroll into a detent
        document.documentElement.style.overflowY = 'scroll';
        document.body.style.overflow = 'visible';
        document.body.style.height = '20000px';
        // One bezel click scrolls the page by a fixed distance (in several events, animated).
        // Detents are counted from the distance only, no timing: every PX_PER_DETENT pixels = one detent.
        // ?px=NN in the address overrides it, to tune on the watch.
        var MID = 10000, FAR = 8000;
        var pxParam = /[?&]px=(\d+)/.exec(location.search);
        var PX_PER_DETENT = pxParam ? +pxParam[1] : 120; // measured on the SM-R800: 5 clicks = 600 px
        var lastY = MID, acc = 0, loadedAt = Date.now();
        window.bezelPx = 0; // total pixels scrolled, shown by ?debug to measure one click
        window.scrollTo(0, MID);
        window.addEventListener('scroll', function () {
            var y = window.scrollY, d = y - lastY;
            lastY = y;
            // A huge jump is not the bezel (page reload puts the scroll back to the top...),
            // nor is the page settling in the middle just after loading
            if (Math.abs(d) > 2000 || Date.now() - loadedAt < 1500) {
                acc = 0;
            } else {
                window.bezelPx += d;
                acc += d;
                // Bezel clockwise scrolls down
                while (acc >= PX_PER_DETENT) { acc -= PX_PER_DETENT; detent(1); }
                while (acc <= -PX_PER_DETENT) { acc += PX_PER_DETENT; detent(-1); }
            }
            // Far from the middle: jump back (the jump itself is not counted)
            if (Math.abs(y - MID) > FAR) { lastY = MID; window.scrollTo(0, MID); }
        });
        // Tap: open the menu (no back key in the browser); tapping an item chooses it
        document.addEventListener('click', function (ev) {
            if (menuItems || (ev.target && ev.target.id === 'dbg')) return;
            if (Date.now() - justDragged < 500) return; // end of a drag, not a tap
            openMenu();
        });
    }

    // ------------------------------------------------------------------ screen on / off (battery)

    document.addEventListener('visibilitychange', function () {
        var on = !document.hidden;
        // Screen back on: the phone sends a map at once; don't take the silence for a lost link
        // Screen back on: the Wi-Fi may need a while to come back, give it the full delay
        if (on) lastMsg = Math.max(lastMsg, Date.now());
        Link.send({ t: 'screen', on: on });
    });

    // ------------------------------------------------------------------ diagnostics (?debug in the address)
    // Shows which events the browser really delivers: compass, bezel, keys

    if (location.search.indexOf('debug') >= 0) {
        var dbg = document.createElement('div');
        dbg.id = 'dbg';
        dbg.addEventListener('click', function () { vibrate(500); n.tap++; });
        dbg.style.cssText = 'position:fixed;top:120px;left:40px;right:40px;z-index:8;font:15px monospace;' +
            'color:#0f0;background:rgba(0,0,0,.75);padding:6px;border-radius:8px;white-space:pre';
        document.body.appendChild(dbg);
        var n = { ori: 0, abs: 0, rot: 0, key: 0, wheel: 0, scroll: 0, tap: 0 }, lastAlpha = '-', lastKey = '-', lastRot = '-';
        window.addEventListener('scroll', function () { n.scroll++; });
        window.addEventListener('deviceorientation', function (e) { n.ori++; lastAlpha = e.alpha === null ? 'null' : Math.round(e.alpha); });
        window.addEventListener('deviceorientationabsolute', function (e) { n.abs++; lastAlpha = e.alpha === null ? 'null' : Math.round(e.alpha); });
        document.addEventListener('rotarydetent', function (e) { n.rot++; lastRot = e.detail && e.detail.direction; });
        document.addEventListener('tizenhwkey', function (e) { n.key++; lastKey = e.keyName; });
        window.addEventListener('wheel', function () { n.wheel++; });
        document.addEventListener('keydown', function (e) { n.key++; lastKey = 'kd:' + e.keyCode; });
        setInterval(function () {
            dbg.textContent = 'orient ' + n.ori + ' abs ' + n.abs + ' a=' + lastAlpha +
                '\nlunette ' + n.rot + ' ' + lastRot + ' wheel ' + n.wheel + ' scroll ' + n.scroll +
                '\npixels ' + Math.round(window.bezelPx || 0) +
                '\nappui vibre: ' + n.tap +
                '\ntouches ' + n.key + ' ' + lastKey +
                '\ntizen ' + (typeof tizen) + ' vib ' + (typeof navigator.vibrate);
        }, 500);
    }

    tick();
    setInterval(tick, 1000);
    Link.send({ t: 'hello' });
})();
