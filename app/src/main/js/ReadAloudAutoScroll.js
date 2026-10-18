/**
 * Auto-scroll script for SkyRead Read Aloud feature.
 *
 * Scrolls the page smoothly at a configurable speed to keep the current
 * reading position visible. The scroll position is reported back to
 * Android via console messages prefixed with 'readaloud:'.
 *
 * Usage:
 *   window.ReadAloudAutoScroll.start(speedPxPerSecond);
 *   window.ReadAloudAutoScroll.stop();
 *   window.ReadAloudAutoScroll.scrollToElement(element);
 *   window.ReadAloudAutoScroll.scrollBy(deltaY);
 */
(function () {
    'use strict';

    var scrollInterval = null;
    var isScrolling = false;
    var currentSpeed = 0;
    var targetSpeed = 0;
    var acceleration = 0.5;
    var deceleration = 0.7;
    var maxSpeed = 200;

    function logPosition() {
        var scrollTop = window.scrollY || document.documentElement.scrollTop;
        var docHeight = document.body.scrollHeight - window.innerHeight;
        var progress = docHeight > 0 ? Math.min(1, scrollTop / docHeight) : 0;
        console.debug('readaloud: scroll ' + Math.round(scrollTop) + ' / ' + Math.round(docHeight) + ' progress=' + progress.toFixed(2));
    }

    function scrollStep() {
        if (!isScrolling) return;
        currentSpeed += (targetSpeed - currentSpeed) * acceleration;
        if (Math.abs(currentSpeed) < 0.1 && Math.abs(targetSpeed) < 0.1) {
            currentSpeed = 0;
        }

        window.scrollBy(0, currentSpeed);
        logPosition();

        var scrollTop = window.scrollY || document.documentElement.scrollTop;
        var docHeight = document.body.scrollHeight - window.innerHeight;
        if (scrollTop >= docHeight - 20) {
            stop();
            console.debug('readaloud: end-of-page');
        }
    }

    function start(speedPxPerSecond) {
        if (isScrolling) return;
        isScrolling = true;
        targetSpeed = speedPxPerSecond || 50;
        currentSpeed = 0;
        scrollInterval = setInterval(scrollStep, 16);
        console.debug('readaloud: started');
    }

    function stop() {
        if (!isScrolling) return;
        isScrolling = false;
        targetSpeed = 0;
        currentSpeed = 0;
        if (scrollInterval) {
            clearInterval(scrollInterval);
            scrollInterval = null;
        }
        console.debug('readaloud: stopped');
    }

    function setSpeed(speedPxPerSecond) {
        targetSpeed = speedPxPerSecond;
    }

    function scrollBy(deltaY) {
        window.scrollBy(0, deltaY);
        logPosition();
    }

    function scrollToElement(selector) {
        var el = typeof selector === 'string' ? document.querySelector(selector) : selector;
        if (el) {
            var rect = el.getBoundingClientRect();
            var scrollTop = window.scrollY || document.documentElement.scrollTop;
            var elTop = rect.top + scrollTop;
            var offset = rect.height / 3;
            window.scrollTo(0, elTop - offset);
            logPosition();
        }
    }

    function highlightNextSentence() {
        var selection = window.getSelection();
        var range = selection.getRangeAt(0);
    }

    window.ReadAloudAutoScroll = {
        start: start,
        stop: stop,
        setSpeed: setSpeed,
        scrollBy: scrollBy,
        scrollToElement: scrollToElement,
        logPosition: logPosition,
        isScrolling: function () { return isScrolling; }
    };
})();
