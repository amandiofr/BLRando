/*
 * Texts of the watch page in the phone's language (field "lang" of the phone messages: en, fr, de, pt).
 * Until the first message, the watch browser's own language; English when none of the four.
 */
var I18n = (function () {
    'use strict';
    var TEXTS = {
        en: {
            connecting: 'Connecting to the phone…',
            linkLost: '⚠ No link with the phone',
            gpsLost: '⚠ GPS lost on the phone',
            phone: 'Phone', watch: 'Watch',
            startQ: 'Start?', stopQ: 'Stop?',
            trackLoaded: 'Track loaded', notRecording: 'Not recording', recording: '● Recording',
            tapAgain: 'Tap again'
        },
        fr: {
            connecting: 'Connexion au téléphone…',
            linkLost: '⚠ Plus de liaison avec le téléphone',
            gpsLost: '⚠ GPS perdu sur le téléphone',
            phone: 'Téléphone', watch: 'Montre',
            startQ: 'Démarrer ?', stopQ: 'Arrêter ?',
            trackLoaded: 'Trace chargée', notRecording: 'Pas d\'enregistrement', recording: '● Enregistrement',
            tapAgain: 'Toucher encore'
        },
        de: {
            connecting: 'Verbindung zum Telefon…',
            linkLost: '⚠ Keine Verbindung zum Telefon',
            gpsLost: '⚠ GPS auf dem Telefon verloren',
            phone: 'Telefon', watch: 'Uhr',
            startQ: 'Starten?', stopQ: 'Beenden?',
            trackLoaded: 'Route geladen', notRecording: 'Keine Aufzeichnung', recording: '● Aufzeichnung',
            tapAgain: 'Nochmal tippen'
        },
        pt: {
            connecting: 'A ligar ao telemóvel…',
            linkLost: '⚠ Sem ligação ao telemóvel',
            gpsLost: '⚠ GPS perdido no telemóvel',
            phone: 'Telemóvel', watch: 'Relógio',
            startQ: 'Iniciar?', stopQ: 'Parar?',
            trackLoaded: 'Trilho carregado', notRecording: 'Sem gravação', recording: '● A gravar',
            tapAgain: 'Toca outra vez'
        }
    };

    function pick(code) {
        code = (code || '').slice(0, 2).toLowerCase();
        return TEXTS[code] ? code : 'en';
    }

    var lang = pick(navigator.language);

    return {
        /** Text for [key] in the current language. */
        t: function (key) { return TEXTS[lang][key] || TEXTS.en[key] || key; },
        /** Language sent by the phone; returns true when it changed. */
        set: function (code) {
            var l = pick(code);
            if (l === lang) return false;
            lang = l;
            return true;
        }
    };
})();
