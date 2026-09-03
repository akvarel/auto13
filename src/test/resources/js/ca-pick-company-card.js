const wanted = arguments[0];
const els = [...document.querySelectorAll('a.stretched-link,a,button,div,span,li,td')].filter(e => e.offsetParent !== null);
const norm = e => String(e.innerText || '').replace(/\s+/g, ' ').trim().toLowerCase();
const hit = els.find(e => { const t = norm(e); return t.length > 0 && t.indexOf(wanted) >= 0 && t.length < wanted.length + 60; });
if (!hit) return '';
hit.click();
return hit.tagName + ':' + norm(hit).substring(0, 40);