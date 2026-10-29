// Мінімальний генератор лінійних графіків у SVG без зовнішніх залежностей (працює всередині k6).
// Графіки зберігаються в results/ і вставляються в README як зображення.
//
// Правила оформлення: одна вісь Y (різні величини — окремі графіки), тонкі лінії 2px,
// маркери 8px з кільцем кольору фону, приглушена сітка, легенда + прямі підписи серій,
// різний тип штриху для кожної серії (графік читається і в чорно-білому друці).

const SURFACE = '#fcfcfb';
const TEXT_PRIMARY = '#0b0b0b';
const TEXT_SECONDARY = '#52514e';
const GRID = '#e4e3df';
const AXIS = '#9b9a95';

// Категоріальна палітра у фіксованому порядку (слоти 1–3) і відповідні штрихи
export const SERIES_STYLE = [
    { color: '#2a78d6', dash: '' },
    { color: '#eb6834', dash: '7 4' },
    { color: '#1baf7a', dash: '2 3' },
];

function esc(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function niceMax(v) {
    if (!(v > 0)) return 1;
    const exp = Math.pow(10, Math.floor(Math.log10(v)));
    const f = v / exp;
    const nice = f <= 1 ? 1 : f <= 2 ? 2 : f <= 2.5 ? 2.5 : f <= 5 ? 5 : 10;
    return nice * exp;
}

function fmt(v) {
    if (v >= 100) return Math.round(v).toString();
    if (v >= 10) return v.toFixed(1);
    return v.toFixed(2);
}

/**
 * @param opts.title    заголовок графіка
 * @param opts.subtitle підзаголовок (умови вимірювання)
 * @param opts.xLabel   підпис осі X
 * @param opts.yLabel   підпис осі Y (з одиницями)
 * @param opts.x        значення по X (категорії, напр. [10, 25, 50, 100, 200])
 * @param opts.series   [{ name, values }] — до 3 серій
 */
export function lineChartSvg(opts) {
    const W = 680, H = 380;
    const m = { top: 78, right: 92, bottom: 56, left: 64 };
    const pw = W - m.left - m.right, ph = H - m.top - m.bottom;
    const xs = opts.x;
    const series = opts.series.slice(0, SERIES_STYLE.length);

    const all = [];
    series.forEach((s) => s.values.forEach((v) => { if (Number.isFinite(v)) all.push(v); }));
    const yMax = niceMax(Math.max(...all, 0) * 1.05);
    const xPos = (i) => m.left + (xs.length === 1 ? pw / 2 : (i * pw) / (xs.length - 1));
    const yPos = (v) => m.top + ph - (v / yMax) * ph;

    const out = [];
    out.push(`<svg xmlns="http://www.w3.org/2000/svg" width="${W}" height="${H}" viewBox="0 0 ${W} ${H}" `
        + `font-family="system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif" role="img" `
        + `aria-label="${esc(opts.title)}">`);
    out.push(`<rect width="${W}" height="${H}" fill="${SURFACE}"/>`);
    out.push(`<text x="${m.left}" y="26" font-size="16" font-weight="600" fill="${TEXT_PRIMARY}">${esc(opts.title)}</text>`);
    if (opts.subtitle) {
        out.push(`<text x="${m.left}" y="45" font-size="12" fill="${TEXT_SECONDARY}">${esc(opts.subtitle)}</text>`);
    }

    // Легенда (рядок над графіком)
    if (series.length > 1) {
        let lx = m.left;
        series.forEach((s, i) => {
            const st = SERIES_STYLE[i];
            out.push(`<line x1="${lx}" y1="63" x2="${lx + 22}" y2="63" stroke="${st.color}" stroke-width="2" `
                + `stroke-dasharray="${st.dash}"/>`);
            out.push(`<circle cx="${lx + 11}" cy="63" r="4" fill="${st.color}" stroke="${SURFACE}" stroke-width="2"/>`);
            out.push(`<text x="${lx + 28}" y="67" font-size="12" fill="${TEXT_PRIMARY}">${esc(s.name)}</text>`);
            lx += 40 + s.name.length * 7;
        });
    }

    // Сітка та вісь Y
    const ticks = 5;
    const step = yMax / ticks;
    const tickDigits = step >= 1 ? 0 : step >= 0.1 ? 1 : 2;
    for (let t = 0; t <= ticks; t++) {
        const v = step * t;
        const y = yPos(v);
        out.push(`<line x1="${m.left}" y1="${y}" x2="${m.left + pw}" y2="${y}" stroke="${t === 0 ? AXIS : GRID}" stroke-width="1"/>`);
        out.push(`<text x="${m.left - 8}" y="${y + 4}" font-size="11" text-anchor="end" fill="${TEXT_SECONDARY}">${v.toFixed(tickDigits)}</text>`);
    }
    out.push(`<text transform="translate(16 ${m.top + ph / 2}) rotate(-90)" font-size="12" text-anchor="middle" `
        + `fill="${TEXT_SECONDARY}">${esc(opts.yLabel)}</text>`);

    // Вісь X
    xs.forEach((x, i) => {
        out.push(`<text x="${xPos(i)}" y="${m.top + ph + 18}" font-size="11" text-anchor="middle" fill="${TEXT_SECONDARY}">${esc(x)}</text>`);
    });
    out.push(`<text x="${m.left + pw / 2}" y="${H - 14}" font-size="12" text-anchor="middle" fill="${TEXT_SECONDARY}">${esc(opts.xLabel)}</text>`);

    // Серії: лінії, маркери з підказками (<title>), прямі підписи справа
    const labels = [];
    series.forEach((s, i) => {
        const st = SERIES_STYLE[i];
        const pts = s.values.map((v, k) => (Number.isFinite(v) ? [xPos(k), yPos(v), v, xs[k]] : null)).filter(Boolean);
        if (pts.length > 1) {
            out.push(`<polyline fill="none" stroke="${st.color}" stroke-width="2" stroke-linejoin="round" `
                + `stroke-linecap="round" stroke-dasharray="${st.dash}" points="${pts.map((p) => `${p[0]},${p[1]}`).join(' ')}"/>`);
        }
        pts.forEach((p) => {
            out.push(`<circle cx="${p[0]}" cy="${p[1]}" r="4" fill="${st.color}" stroke="${SURFACE}" stroke-width="2">`
                + `<title>${esc(s.name)} · ${esc(p[3])} ${esc(opts.xLabel)}: ${fmt(p[2])} ${esc(opts.unit || '')}</title></circle>`);
        });
        if (pts.length) {
            const last = pts[pts.length - 1];
            labels.push({ y: last[1], text: `${s.name} ${fmt(last[2])}`, x: last[0] + 10 });
        }
    });
    // Розведення прямих підписів, щоб не накладалися (мінімум 14px по вертикалі)
    labels.sort((a, b) => a.y - b.y);
    for (let k = 1; k < labels.length; k++) {
        if (labels[k].y - labels[k - 1].y < 14) labels[k].y = labels[k - 1].y + 14;
    }
    labels.forEach((l) => {
        out.push(`<text x="${l.x}" y="${l.y + 4}" font-size="11" fill="${TEXT_PRIMARY}">${esc(l.text)}</text>`);
    });

    out.push('</svg>');
    return out.join('\n');
}
