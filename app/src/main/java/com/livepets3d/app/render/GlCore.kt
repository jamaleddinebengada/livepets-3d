package com.livepets3d.app.render

import android.opengl.GLES30
import android.opengl.Matrix
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Small matrix stack (column-major, android.opengl.Matrix compatible). */
class MatStack(private val depth: Int = 32) {
    private val m = FloatArray(16 * depth)
    private var top = 0
    val array: FloatArray get() = m
    val offset: Int get() = top * 16

    fun reset() { top = 0; Matrix.setIdentityM(m, 0) }
    fun push() { System.arraycopy(m, top * 16, m, (top + 1) * 16, 16); top++ }
    fun pop() { if (top > 0) top-- }
    fun t(x: Float, y: Float, z: Float) = Matrix.translateM(m, top * 16, x, y, z)
    fun s(x: Float, y: Float, z: Float) = Matrix.scaleM(m, top * 16, x, y, z)
    fun s(v: Float) = Matrix.scaleM(m, top * 16, v, v, v)
    fun rx(deg: Float) { if (deg != 0f) Matrix.rotateM(m, top * 16, deg, 1f, 0f, 0f) }
    fun ry(deg: Float) { if (deg != 0f) Matrix.rotateM(m, top * 16, deg, 0f, 1f, 0f) }
    fun rz(deg: Float) { if (deg != 0f) Matrix.rotateM(m, top * 16, deg, 0f, 0f, 1f) }
}

class Program(vs: String, fs: String) {
    val id: Int
    private val cache = HashMap<String, Int>()

    init {
        val v = compile(GLES30.GL_VERTEX_SHADER, vs)
        val f = compile(GLES30.GL_FRAGMENT_SHADER, fs)
        id = GLES30.glCreateProgram()
        GLES30.glAttachShader(id, v); GLES30.glAttachShader(id, f)
        GLES30.glBindAttribLocation(id, 0, "aPos")
        GLES30.glBindAttribLocation(id, 1, "aNor")
        GLES30.glBindAttribLocation(id, 2, "aUV")
        GLES30.glLinkProgram(id)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(id, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("LivePetsGL", "link: " + GLES30.glGetProgramInfoLog(id))
    }

    fun u(name: String): Int = cache.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun use() = GLES30.glUseProgram(id)

    private fun compile(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) Log.e("LivePetsGL", "shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }
}

/** Interleaved mesh: position(3) normal(3) uv(2). */
class Mesh(verts: FloatArray, idx: ShortArray) {
    private val vao = IntArray(1)
    private val buf = IntArray(2)
    private val count = idx.size

    init {
        val vb: FloatBuffer = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(verts)
        vb.position(0)
        val ib: ShortBuffer = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().put(idx)
        ib.position(0)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glBindVertexArray(vao[0])
        GLES30.glGenBuffers(2, buf, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buf[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, verts.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, buf[1])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)
        val stride = 8 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindVertexArray(0)
    }

    fun draw(instances: Int = 1) {
        GLES30.glBindVertexArray(vao[0])
        if (instances <= 1) GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0)
        else GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, 0, instances)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        /**
         * Builds a mesh from a parametric surface f(u,v) with u,v in [0,1].
         * Normals come from finite differences so seams/poles stay smooth.
         */
        fun parametric(nu: Int, nv: Int, f: (Float, Float, FloatArray) -> Unit): Mesh {
            val verts = FloatArray((nu + 1) * (nv + 1) * 8)
            val p = FloatArray(3); val pu = FloatArray(3); val pv = FloatArray(3)
            var k = 0
            val e = 1e-3f
            for (j in 0..nv) for (i in 0..nu) {
                val u = i.toFloat() / nu; val v = j.toFloat() / nv
                f(u, v, p)
                f(u + e, v, pu)
                val vv = if (v + e > 1f) v - e else v + e
                f(u, vv, pv)
                val sgn = if (v + e > 1f) -1f else 1f
                val ax = pu[0] - p[0]; val ay = pu[1] - p[1]; val az = pu[2] - p[2]
                val bx = (pv[0] - p[0]) * sgn; val by = (pv[1] - p[1]) * sgn; val bz = (pv[2] - p[2]) * sgn
                var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
                var len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len < 1e-9f) { // pole: fall back to radial direction
                    nx = p[0]; ny = p[1]; nz = p[2]; len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                }
                verts[k++] = p[0]; verts[k++] = p[1]; verts[k++] = p[2]
                verts[k++] = nx / len; verts[k++] = ny / len; verts[k++] = nz / len
                verts[k++] = u; verts[k++] = v
            }
            val idx = ShortArray(nu * nv * 6)
            var n = 0
            for (j in 0 until nv) for (i in 0 until nu) {
                val a = (j * (nu + 1) + i).toShort(); val b = (a + 1).toShort()
                val c = (a + nu + 1).toShort(); val d = (c + 1).toShort()
                idx[n++] = a; idx[n++] = c; idx[n++] = b
                idx[n++] = b; idx[n++] = c; idx[n++] = d
            }
            return Mesh(verts, idx)
        }

        /** Unit sphere; u wraps around Y, v goes bottom (0) to top (1). */
        fun sphere(nu: Int = 32, nv: Int = 20) = parametric(nu, nv) { u, v, o ->
            val th = u * 2f * PI.toFloat(); val ph = v * PI.toFloat()
            val r = sin(ph)
            o[0] = r * sin(th); o[1] = -cos(ph); o[2] = r * cos(th)
        }

        /** Rounded teardrop that narrows to a point at +Y (ears, horns, spikes). */
        fun taper(amount: Float, power: Float, nu: Int = 24, nv: Int = 16) = parametric(nu, nv) { u, v, o ->
            val th = u * 2f * PI.toFloat(); val ph = v * PI.toFloat()
            val y = -cos(ph)
            val k = 1f - amount * ((y + 1f) / 2f).pow(power)
            val r = sin(ph) * k
            o[0] = r * sin(th); o[1] = y; o[2] = r * cos(th)
        }

        fun torus(minor: Float, nu: Int = 32, nv: Int = 12) = parametric(nu, nv) { u, v, o ->
            val a = u * 2f * PI.toFloat(); val b = v * 2f * PI.toFloat()
            val r = 1f + minor * cos(b)
            o[0] = r * cos(a); o[1] = minor * sin(b); o[2] = -r * sin(a)
        }

        /** Flat quad on XZ plane, uv 0..1 — used for the soft contact shadow. */
        fun quad() = parametric(1, 1) { u, v, o -> o[0] = u * 2f - 1f; o[1] = 0f; o[2] = v * 2f - 1f }

        /** Bat-like wing membrane with a scalloped trailing edge, lying on the XY plane. */
        fun wing() = parametric(20, 6) { u, v, o ->
            val x = u
            val scallop = 0.18f * abs(sin(u * PI.toFloat() * 3f))
            val top = 0.55f * sin(u * PI.toFloat() * 0.5f + 0.4f) + 0.25f * u
            val bottom = -0.25f + 0.45f * u + scallop
            o[0] = x; o[1] = bottom + (top - bottom) * v; o[2] = 0.03f * sin(v * PI.toFloat()) * (1f - u * 0.5f)
        }
    }

}

/** Converts 0xRRGGBB to a float RGB triple. */
fun rgb(c: Int): FloatArray = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
