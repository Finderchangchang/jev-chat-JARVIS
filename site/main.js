/* Jev 聊天助手 官网脚本：版本注入 / 主题 / 导航 / 复制 / 进场动效 */
(function () {
  "use strict";

  var JEV = window.JEV || {};
  var repo = JEV.repo || "https://github.com/Finderchangchang/jev-chat-JARVIS";
  var version = JEV.version || "";
  var apkUrl = JEV.apkUrl || repo;

  /* ---------- 1. 把版本号和链接填到页面所有出现处 ---------- */
  function fill() {
    var i, els;

    els = document.querySelectorAll("[data-jev-version]");
    for (i = 0; i < els.length; i++) els[i].textContent = version;

    els = document.querySelectorAll("[data-jev-apk]");
    for (i = 0; i < els.length; i++) {
      els[i].setAttribute("href", apkUrl);
      els[i].setAttribute("rel", "noopener");
    }

    var map = [
      ["[data-jev-repo]", repo],
      ["[data-jev-readme]", repo + "#readme"],
      ["[data-jev-issues]", repo + "/issues"],
      ["[data-jev-commits]", repo + "/commits/main"]
    ];
    for (var m = 0; m < map.length; m++) {
      els = document.querySelectorAll(map[m][0]);
      for (i = 0; i < els.length; i++) els[i].setAttribute("href", map[m][1]);
    }
  }
  fill();

  /* ---------- 2. 主题切换 ---------- */
  var root = document.documentElement;
  var themeBtn = document.getElementById("themeBtn");

  function currentTheme() {
    var set = root.getAttribute("data-theme");
    if (set) return set;
    return window.matchMedia && window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  }

  if (themeBtn) {
    themeBtn.addEventListener("click", function () {
      var next = currentTheme() === "dark" ? "light" : "dark";
      root.setAttribute("data-theme", next);
      try { localStorage.setItem("jev-theme", next); } catch (e) {}
    });
  }

  /* ---------- 3. 导航：汉堡菜单 + 滚动描边 ---------- */
  var burger = document.getElementById("burger");
  var navMenu = document.getElementById("navMenu");
  var nav = document.getElementById("nav");

  if (burger && navMenu) {
    burger.addEventListener("click", function () {
      var open = navMenu.classList.toggle("open");
      burger.setAttribute("aria-expanded", open ? "true" : "false");
      burger.setAttribute("aria-label", open ? "关闭菜单" : "打开菜单");
    });
    navMenu.addEventListener("click", function (e) {
      if (e.target.tagName === "A") {
        navMenu.classList.remove("open");
        burger.setAttribute("aria-expanded", "false");
        burger.setAttribute("aria-label", "打开菜单");
      }
    });
  }

  if (nav) {
    var onScroll = function () {
      if (window.scrollY > 8) nav.classList.add("is-stuck");
      else nav.classList.remove("is-stuck");
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    onScroll();
  }

  /* ---------- 4. 命令行复制 ---------- */
  var copyBtn = document.getElementById("copyBtn");
  var cmdText = document.getElementById("cmdText");

  if (copyBtn && cmdText) {
    copyBtn.addEventListener("click", function () {
      var text = cmdText.textContent.trim();
      var label = copyBtn.querySelector(".cmd-copy-label");

      var done = function (ok) {
        if (!label) return;
        label.textContent = ok ? "已复制" : "复制失败";
        copyBtn.classList.toggle("done", ok);
        setTimeout(function () {
          label.textContent = "复制";
          copyBtn.classList.remove("done");
        }, 1800);
      };

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      } else {
        try {
          var ta = document.createElement("textarea");
          ta.value = text;
          ta.setAttribute("readonly", "");
          ta.style.position = "fixed";
          ta.style.opacity = "0";
          document.body.appendChild(ta);
          ta.select();
          document.execCommand("copy");
          document.body.removeChild(ta);
          done(true);
        } catch (e) { done(false); }
      }
    });
  }

  /* ---------- 5. 进场动效 ---------- */
  var items = document.querySelectorAll(".reveal");
  var reduce = window.matchMedia && window.matchMedia("(prefers-reduced-motion: reduce)").matches;

  if (reduce || !("IntersectionObserver" in window)) {
    for (var k = 0; k < items.length; k++) items[k].classList.add("in");
  } else {
    var io = new IntersectionObserver(function (entries) {
      for (var j = 0; j < entries.length; j++) {
        if (entries[j].isIntersecting) {
          entries[j].target.classList.add("in");
          io.unobserve(entries[j].target);
        }
      }
    }, { rootMargin: "0px 0px -8% 0px", threshold: 0.08 });

    for (var n = 0; n < items.length; n++) {
      // 同一容器内的卡片错开一点点
      var sibs = items[n].parentElement ? items[n].parentElement.children : [];
      var idx = Array.prototype.indexOf.call(sibs, items[n]);
      if (idx > 0 && idx < 6) items[n].style.transitionDelay = (idx * 70) + "ms";
      io.observe(items[n]);
    }
  }
})();
