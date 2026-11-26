package com.livepets3d.app.render

/**
 * GLSL ES 3.00 shaders.
 *
 * FUR: classic shell-texturing. Each part is drawn once, instanced N+1 times; instance i is
 * pushed out along the normal by furLength * i/N and pixels whose strand height (noise) is
 * below the shell height are discarded, producing individual tapered hair strands with
 * self-shadowing at the roots, a soft rim/back light and an anisotropic-ish sheen.
 *
 * SOLID: eyes (procedural iris, pupil, double catch-light), glossy nose/collar/horns,
 * dragon scales, wing membranes and the contact shadow.
 */
object Shaders {
    const val COMMON_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNor;
layout(location=2) in vec2 aUV;
uniform mat4 uModel;
uniform mat4 uNormalM;
uniform mat4 uVP;
uniform float uFurLen;
uniform float uShells;
uniform vec3 uForce;
uniform vec4 uBald0;
uniform vec4 uBald1;
out vec3 vN;
out vec3 vLN;
out vec3 vLP;
out vec2 vUV;
out vec3 vW;
out float vH;
void main() {
    float h = uShells > 0.5 ? float(gl_InstanceID) / uShells : 0.0;
    vec3 n = normalize(mat3(uNormalM) * aNor);
    vec4 wp = uModel * vec4(aPos, 1.0);
    float k = 1.0;
    if (uBald0.w > 0.0) k *= smoothstep(uBald0.w * 0.55, uBald0.w, distance(aPos, uBald0.xyz));
    if (uBald1.w > 0.0) k *= smoothstep(uBald1.w * 0.55, uBald1.w, distance(aPos, uBald1.xyz));
    float fl = uFurLen * mix(0.15, 1.0, k);
    vec3 p = wp.xyz + n * (fl * h) + uForce * (h * h * fl);
    vN = n; vLN = aNor; vLP = aPos; vUV = aUV; vW = p; vH = h;
    gl_Position = uVP * vec4(p, 1.0);
}"""

    const val FUR_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec3 vLN; in vec3 vLP; in vec2 vUV; in vec3 vW; in float vH;
uniform sampler2D uNoise;
uniform vec2 uDensity;
uniform vec3 uCol;
uniform vec3 uCol2;
uniform vec3 uCol3;
uniform vec4 uMask;      // xyz = local direction, w = strength
uniform vec2 uMaskEdge;  // smoothstep edges
uniform float uStripes;
uniform vec3 uStripeDir;
uniform vec3 uCam;
uniform vec3 uLight;
uniform float uSoft;     // extra brightness for very fluffy parts
out vec4 o;
void main() {
    vec2 g = vUV * uDensity * 256.0;
    vec2 cell = floor(g);
    vec4 nz = texture(uNoise, (cell + 0.5) / 256.0);
    float strand = nz.r;
    if (vH > 0.0) {
        // round, tapered strands with a little positional jitter per strand
        vec2 f = fract(g) - 0.5 - (vec2(nz.b, nz.g) - 0.5) * 0.35;
        float radius = mix(0.62, 0.12, vH);
        if (strand < vH * 0.9 + 0.05 || length(f) > radius) discard;
    }
    vec3 ln = normalize(vLN);
    float m = smoothstep(uMaskEdge.x, uMaskEdge.y, dot(ln, normalize(uMask.xyz))) * uMask.w;
    vec3 col = mix(uCol, uCol2, m);
    if (uStripes > 0.0) {
        float s = sin(dot(vLP, uStripeDir) * uStripes + nz.g * 2.2);
        float top = smoothstep(-0.3, 0.35, ln.y);
        col = mix(col, uCol3, smoothstep(0.25, 0.75, s) * top * (1.0 - m) * 0.8);
    }
    col *= 0.93 + 0.14 * nz.g;
    vec3 N = normalize(vN);
    vec3 V = normalize(uCam - vW);
    vec3 L = normalize(uLight);
    float wrap = clamp(dot(N, L) * 0.55 + 0.45, 0.0, 1.0);
    float ao = mix(0.5, 1.0, pow(vH, 0.6));
    vec3 H = normalize(L + V);
    // sheen along the strand tips
    float spec = pow(max(dot(N, H), 0.0), 18.0) * 0.16 * vH;
    float rim = pow(1.0 - max(dot(N, V), 0.0), 2.6);
    vec3 fill = vec3(0.55, 0.5, 0.85) * (0.5 - 0.5 * N.y) * 0.18; // purple bounce from the scene
    vec3 c = col * (0.30 + 0.86 * wrap * wrap + uSoft) * ao
           + col * fill
           + vec3(1.0, 0.92, 0.85) * spec
           + mix(col, vec3(1.0, 0.85, 0.95), 0.45) * rim * 0.42 * (0.3 + vH);
    o = vec4(c, 1.0);
}"""

    const val SOLID_FS = """#version 300 es
precision highp float;
in vec3 vN; in vec3 vLN; in vec3 vLP; in vec2 vUV; in vec3 vW; in float vH;
uniform int uKind;       // 0 glossy, 1 eye, 2 scales, 3 matte, 4 shadow, 5 membrane
uniform vec3 uCol;
uniform vec3 uCol2;
uniform vec4 uMask;
uniform vec2 uMaskEdge;
uniform vec2 uDensity;
uniform float uAlpha;
uniform vec3 uCam;
uniform vec3 uLight;
out vec4 o;
void main() {
    if (uKind == 4) {
        vec2 d = vUV * 2.0 - 1.0;
        float a = (1.0 - smoothstep(0.0, 1.0, length(d))) * uAlpha;
        o = vec4(0.0, 0.0, 0.0, a * 0.55);
        return;
    }
    vec3 N = normalize(vN);
    vec3 V = normalize(uCam - vW);
    if (uKind == 5 && dot(N, V) < 0.0) N = -N;
    vec3 L = normalize(uLight);
    vec3 H = normalize(L + V);
    float diff = clamp(dot(N, L) * 0.5 + 0.5, 0.0, 1.0);
    float rim = pow(1.0 - max(dot(N, V), 0.0), 3.0);
    vec3 col = uCol;
    if (uKind == 1) {
        vec3 ln = normalize(vLN);
        float a = acos(clamp(ln.z, -1.0, 1.0));
        float iris = 1.0 - smoothstep(0.80, 0.84, a);
        float pupil = 1.0 - smoothstep(0.40, 0.44, a);
        float grad = smoothstep(-0.7, 0.6, -ln.y);
        vec3 irisCol = mix(uCol * 0.45, uCol * 1.35 + 0.08, grad);
        irisCol *= 0.75 + 0.25 * smoothstep(0.82, 0.45, a);           // dark limbal ring
        float fibers = 0.06 * sin(atan(ln.y, ln.x) * 28.0);
        irisCol += fibers * uCol;
        col = mix(uCol2, irisCol, iris);
        col = mix(col, vec3(0.02, 0.02, 0.03), pupil);
        float s1 = smoothstep(0.955, 0.975, dot(N, normalize(H + vec3(-0.25, 0.25, 0.0))));
        float s2 = smoothstep(0.985, 0.992, dot(N, normalize(H + vec3(0.35, -0.35, 0.0))));
        vec3 c = col * (0.55 + 0.45 * diff) + pow(max(dot(N, H), 0.0), 80.0) * 0.4;
        c = mix(c, vec3(1.0), max(s1, s2 * 0.85));
        o = vec4(c, 1.0);
        return;
    }
    if (uKind == 2) {
        vec3 ln = normalize(vLN);
        float m = smoothstep(uMaskEdge.x, uMaskEdge.y, dot(ln, normalize(uMask.xyz))) * uMask.w;
        vec2 g = vUV * uDensity;
        g.x += floor(g.y) * 0.5;
        vec2 cell = fract(g) - 0.5;
        float d = length(cell * vec2(1.0, 1.5));
        float edge = smoothstep(0.32, 0.52, d);
        col = mix(uCol, uCol2, m);
        col *= 1.0 - edge * 0.16;
        col *= 0.96 + 0.08 * (0.5 - cell.y);
    }
    float specPow = uKind == 3 ? 6.0 : (uKind == 2 ? 36.0 : 60.0);
    float specAmt = uKind == 3 ? 0.04 : (uKind == 2 ? 0.35 : 0.6);
    vec3 c = col * (0.35 + 0.8 * diff * diff) + vec3(1.0) * pow(max(dot(N, H), 0.0), specPow) * specAmt;
    c += mix(col, vec3(0.9, 0.7, 1.0), 0.5) * rim * (uKind == 2 ? 0.55 : 0.25);
    if (uKind == 5) c += uCol * 0.25 * (1.0 - diff);   // translucent membrane
    o = vec4(c, 1.0);
}"""
}
