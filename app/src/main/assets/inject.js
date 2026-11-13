console.log=console.error=console.warn=console.info=function(){};
/* =====================================================================
 * WebAutoAnswer - inject.js
 * 注入通道自检脚本（占位版）
 * ---------------------------------------------------------------------
 * 说明：这一版只做「注入链路自检」：确认 JS 真的进了页面、确认 SPA 路由
 * 切换能被捕获。确认链路通了之后，把你完整的答题脚本替换到下面的
 * ANSWER_ENGINE 位置即可，不需要重新打包 APK（脚本在 assets 里）。
 *
 * 结构：
 *   1. 防重复注入
 *   2. SPA 路由监听（pushState / replaceState / hashchange / popstate）
 *   3. ANSWER_ENGINE 挂载点（你的脚本放这里）
 *   4. 可选角标，用于肉眼确认注入是否生效
 * ===================================================================== */
(function () {
    'use strict';

    var TAG = '[WAA]';
    var VERSION = 'boot-1';

    // 1) 防重复注入：页面内只允许一份实例
    if (window.__WAA__ && window.__WAA__.version) {
        return;
    }

    window.__WAA__ = {
        version: VERSION,
        injectedAt: Date.now(),
        routeListeners: [],
        onRoute: function (fn) {
            if (typeof fn === 'function') {
                this.routeListeners.push(fn);
            }
        }
    };

    // 2) SPA 路由监听
    var lastUrl = location.href;

    function fireRoute(reason) {
        var url = location.href;
        if (url === lastUrl && reason !== 'force') {
            return;
        }
        lastUrl = url;
        var hooks = window.__WAA__.routeListeners;
        for (var i = 0; i < hooks.length; i++) {
            try {
                hooks[i](url, reason);
            } catch (e) {
                /* 单个监听器异常不影响其它监听器 */
            }
        }
        // 给答题引擎留的钩子
        try {
            if (typeof window.__WAA__.onPageReady === 'function') {
                window.__WAA__.onPageReady(url, reason);
            }
        } catch (e2) {}
    }

    try {
        var rawPush = history.pushState;
        history.pushState = function () {
            var r = rawPush.apply(this, arguments);
            setTimeout(function () { fireRoute('pushState'); }, 0);
            return r;
        };
        var rawReplace = history.replaceState;
        history.replaceState = function () {
            var r = rawReplace.apply(this, arguments);
            setTimeout(function () { fireRoute('replaceState'); }, 0);
            return r;
        };
    } catch (e) {}

    window.addEventListener('popstate', function () { fireRoute('popstate'); }, true);
    window.addEventListener('hashchange', function () { fireRoute('hashchange'); }, true);

    // 3) ANSWER_ENGINE：把你的完整脚本内容放进来（或直接在上面 return 之前整段粘贴）
    //    约定：实现 window.__WAA__.onPageReady(url, reason) 或 window.__WAA__.onRoute(fn)
    window.__WAA__.onPageReady = function (url, reason) {
        // TODO: 在这里接你的答题逻辑入口
        // 例如：window.__WAA__.start(url) / 逐题模式 tick() ...
    };

    // 页面本身第一次就是 ready 状态，先触发一次
    setTimeout(function () { fireRoute('force'); }, 300);

    // 4) 可选角标：肉眼确认注入生效，链路验证完成后把 SHOW_BADGE 改 false
    var SHOW_BADGE = true;
    if (SHOW_BADGE) {
        var paint = function () {
            var el = document.getElementById('__waa_badge__');
            if (!el) {
                el = document.createElement('div');
                el.id = '__waa_badge__';
                el.style.cssText = [
                    'position:fixed', 'right:6px', 'bottom:6px', 'z-index:2147483647',
                    'padding:2px 6px', 'border-radius:4px', 'font:11px/1.4 monospace',
                    'background:rgba(0,0,0,.55)', 'color:#7CFC00', 'pointer-events:none'
                ].join(';');
                (document.body || document.documentElement).appendChild(el);
            }
            el.textContent = 'WAA ' + VERSION + ' | ' + (location.href.length > 42
                ? location.href.slice(0, 42) + '…' : location.href);
        };
        if (document.body) {
            paint();
        } else {
            document.addEventListener('DOMContentLoaded', paint, false);
        }
    }
})();
