// The DOS picture on the phone's screen (ScreenRenderer.java), GLSL ES 1.00.
// Modes: 0 sharp pixels (all of one width: integer-sharp inside, one blended screen pixel at
// the edges), 1 VGA monitor (lines doubled, dot mask), 2 colour TV (one beam per line, colour
// smeared wider than brightness, slot mask), 3 black-and-white TV, 4 green and 5 amber monitor.
// Everything that looks like the tube (scanlines, mask) lives in "tube" coordinates, so the
// pause magnifier enlarges it like a magnifying glass on a real screen.
// GPL-2.0-or-later, see dos/COPYING.
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;   // positions on a 1440-pixel tube need more than half floats
#else
precision mediump float;
#endif

varying vec2 v_uv;
uniform sampler2D u_tex;
uniform vec2 u_texSize;   // source picture, pixels (320x200 …)
uniform vec2 u_outSize;   // picture on the phone, screen pixels (without zoom)
uniform float u_mode;
uniform float u_zoom;     // 1 … 8 (pause magnifier)
uniform vec2 u_center;    // source point (0..1) in the middle of the view

const vec3 LUMA = vec3(0.299, 0.587, 0.114);

// the uploaded ints are ARGB in memory order B, G, R, A
vec3 texel(vec2 p) {
    vec2 c = (clamp(floor(p), vec2(0.0), u_texSize - 1.0) + 0.5) / u_texSize;
    return texture2D(u_tex, c).bgr;
}

// a source row, smoothed horizontally like a video amplifier: Gaussian of sigma texels
vec3 row(float x, float y, float sigma) {
    float ix = floor(x);
    vec3 sum = vec3(0.0);
    float wsum = 0.0;
    for (int k = -2; k <= 2; k++) {
        float cx = ix + float(k);
        float d = (x - (cx + 0.5)) / sigma;
        float w = exp(-0.5 * d * d);
        sum += texel(vec2(cx, y)) * w;
        wsum += w;
    }
    return sum / wsum;
}

// colour TV: brightness kept sharper than colour (PAL/NTSC chroma is narrow-band)
vec3 tvRow(float x, float y) {
    vec3 narrow = row(x, y, 0.45);
    vec3 wide = row(x, y, 1.1);
    return wide - dot(wide, LUMA) + dot(narrow, LUMA);
}

// a beam's brightness across its line: wider for brighter colours (as on real tubes)
float beam(float d, float lum, float thin, float thick) {
    float w = mix(thin, thick, lum);
    return exp(-0.5 * (d * d) / (w * w));
}

vec3 sharp(vec2 uv) {
    vec2 t = uv * u_texSize;
    vec2 i = floor(t);
    vec2 s = t - i;
    vec2 scale = max(u_outSize * u_zoom / u_texSize, vec2(1.0));
    vec2 range = 0.5 - 0.5 / scale;
    vec2 d = s - 0.5;
    vec2 f = (d - clamp(d, -range, range)) * scale + 0.5;
    return texture2D(u_tex, (i + f) / u_texSize).bgr;
}

void main() {
    vec2 uv = u_center + (v_uv - 0.5) / u_zoom;
    if (uv.x < 0.0 || uv.y < 0.0 || uv.x > 1.0 || uv.y > 1.0) {
        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    int mode = int(u_mode + 0.5);
    if (mode == 0) {
        gl_FragColor = vec4(sharp(uv), 1.0);
        return;
    }

    // the tube: where on its face this screen pixel is (in screen pixels at zoom 1)
    vec2 tube = uv * u_outSize;
    bool vga = mode == 1 || mode == 4 || mode == 5;
    // beam lines: VGA doubles 200/240-line modes to 400/480; a TV shows 200 (400-line text
    // modes take two source rows per line)
    float rowsPerLine = 1.0;
    float lines = u_texSize.y;
    if (vga && u_texSize.y <= 300.0) lines = u_texSize.y * 2.0;
    if (!vga && u_texSize.y > 300.0) { lines = u_texSize.y * 0.5; rowsPerLine = 2.0; }
    float pxPerLine = u_outSize.y * u_zoom / lines;
    // too few screen pixels per line: the lines would only flicker — fade them out
    float lineStrength = smoothstep(1.6, 3.2, pxPerLine);

    float x = uv.x * u_texSize.x;
    float ly = uv.y * lines;
    float li = floor(ly);
    float d = ly - li - 0.5;                    // -0.5 … 0.5 across the line
    float nb = d < 0.0 ? li - 1.0 : li + 1.0;   // the neighbouring line
    float sigma = vga ? 0.3 : 0.6;

    vec3 c0, c1;
    float srcRow0, srcRow1;
    if (vga && u_texSize.y <= 300.0) {
        srcRow0 = floor(li * 0.5);
        srcRow1 = floor(clamp(nb, 0.0, lines - 1.0) * 0.5);
    } else {
        srcRow0 = li * rowsPerLine;
        srcRow1 = clamp(nb, 0.0, lines - 1.0) * rowsPerLine;
    }
    if (mode == 2) {
        c0 = tvRow(x, srcRow0);
        c1 = tvRow(x, srcRow1);
        if (rowsPerLine > 1.0) { c0 = 0.5 * (c0 + tvRow(x, srcRow0 + 1.0)); c1 = 0.5 * (c1 + tvRow(x, srcRow1 + 1.0)); }
    } else {
        c0 = row(x, srcRow0, mode == 3 ? 0.7 : sigma);
        c1 = row(x, srcRow1, mode == 3 ? 0.7 : sigma);
        if (rowsPerLine > 1.0) {
            c0 = 0.5 * (c0 + row(x, srcRow0 + 1.0, mode == 3 ? 0.7 : sigma));
            c1 = 0.5 * (c1 + row(x, srcRow1 + 1.0, mode == 3 ? 0.7 : sigma));
        }
    }

    // monochrome tubes: brightness only, in the phosphor's colour
    vec3 phosphor = vec3(1.0);
    if (mode == 3) phosphor = vec3(0.92, 0.95, 1.0);   // bluish white of a B&W set
    if (mode == 4) phosphor = vec3(0.25, 1.0, 0.44);   // P1 green (as the Specialist's monitor)
    if (mode == 5) phosphor = vec3(1.0, 0.69, 0.19);   // amber
    if (mode >= 3) {
        c0 = vec3(dot(c0, LUMA));
        c1 = vec3(dot(c1, LUMA));
    }

    // beams: a VGA monitor's lines nearly touch (line-doubled), a TV's leave dark gaps
    float thin = vga ? 0.30 : 0.20;
    float thick = vga ? 0.48 : 0.36;
    vec3 col = c0 * beam(d, dot(c0, LUMA), thin, thick) + c1 * beam(1.0 - abs(d), dot(c1, LUMA), thin, thick);
    vec3 plain = c0;
    col = mix(plain, col * (vga ? 1.15 : 1.35), lineStrength);

    // the mask: colour tubes only; a triad is 3 tube pixels wide (3 screen pixels at zoom 1), so
    // the magnifier enlarges it; it fades out when a picture dot gets fewer than ~3 screen pixels
    if (mode == 1 || mode == 2) {
        float dots = u_texSize.x * (u_texSize.x <= 400.0 ? 2.0 : 1.0);
        float pxPerDot = u_outSize.x * u_zoom / dots;
        float maskStrength = smoothstep(1.2, 2.6, pxPerDot) * (mode == 1 ? 0.35 : 0.45);
        float shift = mode == 1 ? mod(floor(tube.y / 3.0), 2.0) * 1.5 : 0.0;   // dot triads stagger
        float m = mod(floor(tube.x + shift), 3.0);
        vec3 mask = vec3(1.0 - maskStrength);
        if (m < 0.5) mask.r = 1.0; else if (m < 1.5) mask.g = 1.0; else mask.b = 1.0;
        col *= mask * (1.0 + maskStrength * 0.6);
    }

    col *= phosphor;
    // a touch of glow so the brightest parts bloom a little over the gaps
    col = col + 0.05 * plain * phosphor;
    gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}
